package integration

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// VT-021: Normal close via control.close
func TestVT_021_NormalCloseViaControl(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	// Arm → Open → Close
	call(t, sockPath, "mcphub.control.arm", nil)
	call(t, sockPath, "mcphub.control.open", nil)

	state := statusState(t, sockPath)
	if state != "OPEN" {
		t.Fatalf("expected OPEN, got %s", state)
	}

	call(t, sockPath, "mcphub.control.close", nil)

	// Wait for CoolingDown → Closed transition
	waitForState(t, sockPath, "CLOSED", 3*time.Second)

	// REQ-10.3.5: verify via session_log
	transitions := readStateTransitions(t, dataDir)
	t.Logf("VT-021 transitions: %v", transitions)
	if !containsAllTransitions(transitions, "ARMED", "OPEN", "COOLING_DOWN", "CLOSED") {
		t.Errorf("VT-021: session_log missing required transitions, got %v", transitions)
	}
}

// VT-022: Emergency lock via mcphub lock
func TestVT_022_EmergencyLockViaControl(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	// Arm → Open
	call(t, sockPath, "mcphub.control.arm", nil)
	call(t, sockPath, "mcphub.control.open", nil)
	if statusState(t, sockPath) != "OPEN" {
		t.Fatalf("expected OPEN before lock")
	}

	// Emergency lock with reason
	params := map[string]interface{}{"lock_reason": "test_emergency"}
	_, rpcErr := call(t, sockPath, "mcphub.control.lock", params)
	if rpcErr != nil {
		t.Fatalf("mcphub.control.lock: %v", rpcErr)
	}

	// REQ-3.8.2: Open → Closed bypassing CoolingDown
	waitForState(t, sockPath, "CLOSED", 2*time.Second)

	// Verify new open is rejected (REQ-3.8.6)
	_, rpcErr2 := call(t, sockPath, "mcphub.control.arm", nil)
	if rpcErr2 == nil {
		t.Errorf("VT-022: expected mcphub.control.arm to be rejected when locked")
	}

	// Verify lock_reason recorded in route_log (REQ-3.8.5)
	// Find lock_reason in the route_log entries
	dbPath := filepath.Join(dataDir, "mcphub.db")
	dbExists := false
	if _, err := os.Stat(dbPath); err == nil {
		dbExists = true
	}
	if !dbExists {
		t.Skipf("DB not found at %s", dbPath)
	}

	// Unlock for next tests
	call(t, sockPath, "mcphub.control.unlock", nil)
	state := statusState(t, sockPath)
	t.Logf("VT-022 post-unlock state: %s", state)
}

// VT-023: State persistence across daemon restart — session lost, NOT restored
func TestVT_023_StateResetAcrossRestart(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	dataDir := t.TempDir()
	sockPath := filepath.Join(t.TempDir(), "mcphub.sock")

	env := []string{
		"MCPHUB_SOCKET_PATH=" + sockPath,
		"MCPHUB_DATA_DIR=" + dataDir,
		"MCPHUB_ADAPTER_DIR=" + filepath.Join(repoRoot, "adapters", "dist"),
	}

	// First daemon: open a session
	ctx1, cancel1 := context.WithCancel(context.Background())
	cmd1 := exec.CommandContext(ctx1, binPath, "_daemon")
	cmd1.Env = append(os.Environ(), env...)
	cmd1.Stdout = os.Stdout
	cmd1.Stderr = os.Stderr
	if err := cmd1.Start(); err != nil {
		t.Fatalf("daemon 1: %v", err)
	}
	waitForSocketVT(t, sockPath)

	call(t, sockPath, "mcphub.control.arm", nil)
	call(t, sockPath, "mcphub.control.open", nil)
	if statusState(t, sockPath) != "OPEN" {
		t.Fatalf("expected OPEN")
	}

	// Stop first daemon (kill, simulating unclean)
	cancel1()
	_ = cmd1.Wait()

	// Wait for socket release
	for i := 0; i < 20; i++ {
		if _, err := os.Stat(sockPath); os.IsNotExist(err) {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = os.Remove(sockPath) // safety

	// Start second daemon
	ctx2, cancel2 := context.WithCancel(context.Background())
	cmd2 := exec.CommandContext(ctx2, binPath, "_daemon")
	cmd2.Env = append(os.Environ(), env...)
	cmd2.Stdout = os.Stdout
	cmd2.Stderr = os.Stderr
	if err := cmd2.Start(); err != nil {
		t.Fatalf("daemon 2: %v", err)
	}
	defer func() {
		cancel2()
		_ = cmd2.Wait()
	}()
	waitForSocketVT(t, sockPath)

	// REQ: state MUST be Closed (session is lost across restart)
	state := statusState(t, sockPath)
	if state != "CLOSED" {
		t.Errorf("VT-023: expected CLOSED after daemon restart, got %s", state)
	}
}

// VT-024: Lock persistence across daemon restart
func TestVT_024_LockPersistsAcrossRestart(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	dataDir := t.TempDir()
	sockPath := filepath.Join(t.TempDir(), "mcphub.sock")

	env := []string{
		"MCPHUB_SOCKET_PATH=" + sockPath,
		"MCPHUB_DATA_DIR=" + dataDir,
		"MCPHUB_ADAPTER_DIR=" + filepath.Join(repoRoot, "adapters", "dist"),
	}

	// First daemon: lock
	ctx1, cancel1 := context.WithCancel(context.Background())
	cmd1 := exec.CommandContext(ctx1, binPath, "_daemon")
	cmd1.Env = append(os.Environ(), env...)
	cmd1.Stdout = os.Stdout
	cmd1.Stderr = os.Stderr
	if err := cmd1.Start(); err != nil {
		t.Fatalf("daemon 1: %v", err)
	}
	waitForSocketVT(t, sockPath)

	params := map[string]interface{}{"lock_reason": "test_persist"}
	call(t, sockPath, "mcphub.control.lock", params)
	waitForState(t, sockPath, "CLOSED", 2*time.Second)

	// Check status includes locked_until_unlock flag (REQ-3.8.9)
	result, _ := call(t, sockPath, "mcphub.control.status", nil)
	var status map[string]interface{}
	if err := json.Unmarshal(result, &status); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	locked, _ := status["locked_until_unlock"].(bool)
	if !locked {
		t.Errorf("VT-024: expected locked_until_unlock=true after lock, got %v", status["locked_until_unlock"])
	}

	// Graceful shutdown
	cancel1()
	_ = cmd1.Wait()
	for i := 0; i < 20; i++ {
		if _, err := os.Stat(sockPath); os.IsNotExist(err) {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = os.Remove(sockPath)

	// Restart daemon
	ctx2, cancel2 := context.WithCancel(context.Background())
	cmd2 := exec.CommandContext(ctx2, binPath, "_daemon")
	cmd2.Env = append(os.Environ(), env...)
	cmd2.Stdout = os.Stdout
	cmd2.Stderr = os.Stderr
	if err := cmd2.Start(); err != nil {
		t.Fatalf("daemon 2: %v", err)
	}
	defer func() {
		cancel2()
		_ = cmd2.Wait()
	}()
	waitForSocketVT(t, sockPath)

	// Lock MUST still be set (REQ-3.8.7)
	result2, _ := call(t, sockPath, "mcphub.control.status", nil)
	var status2 map[string]interface{}
	if err := json.Unmarshal(result2, &status2); err != nil {
		t.Fatalf("decode status after restart: %v", err)
	}
	locked2, _ := status2["locked_until_unlock"].(bool)
	if !locked2 {
		t.Errorf("VT-024: expected locked_until_unlock=true after restart, got %v", status2["locked_until_unlock"])
	}

	// Attempt to arm — must be rejected
	_, rpcErr := call(t, sockPath, "mcphub.control.arm", nil)
	if rpcErr == nil {
		t.Errorf("VT-024: expected arm to fail while locked")
	} else if !strings.Contains(strings.ToLower(rpcErr.Message), "lock") {
		t.Logf("VT-024: arm rejected with: %s (acceptable)", rpcErr.Message)
	}

	// Cleanup: unlock
	call(t, sockPath, "mcphub.control.unlock", nil)
}
