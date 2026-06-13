package integration

import (
	"context"
	"encoding/json"
	"math"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

var mcphubRoutingFailureCodes = map[string]struct{}{
	"session_not_open":     {},
	"tool_not_found":       {},
	"tool_denied":          {},
	"provider_unreachable": {},
	"internal_error":       {},
	// NOTE: provider_error is intentionally excluded — it represents downstream
	// provider execution failure, not a hub routing or state machine failure.
}

var vtTools = []struct {
	id          string
	name        string
	args        map[string]interface{}
	requiresNet string
}{
	{"VT-001", "webfetch", map[string]interface{}{"url": "https://httpbin.org/get"}, "httpbin.org:443"},
	{"VT-002", "websearch", map[string]interface{}{"query": "mcp protocol"}, "html.duckduckgo.com:443"},
	{"VT-003", "todowrite", map[string]interface{}{"todos": []map[string]interface{}{{"content": "vt test", "status": "pending", "priority": "low"}}}, ""},
	{"VT-004", "list", map[string]interface{}{"path": "/tmp"}, ""},
	{"VT-005", "apply_patch", map[string]interface{}{"patch": "--- /dev/null\n+++ b/vt_apply_patch_probe.txt\n@@ -0,0 +1 @@\n+vt\n", "cwd": "/tmp"}, ""},
	{"VT-006", "codesearch", map[string]interface{}{"pattern": "package", "path": "/tmp"}, ""},
	{"VT-007", "lsp", map[string]interface{}{"command": "hover", "file": "/tmp/test.go"}, ""},
	{"VT-008", "plan_enter", map[string]interface{}{"plan": "vt integration plan"}, ""},
	{"VT-009", "plan_exit", map[string]interface{}{"summary": "vt integration done"}, ""},
	{"VT-010", "skill", map[string]interface{}{"name": "nonexistent-vt-skill"}, ""},
	{"VT-011", "batch", map[string]interface{}{"operations": []map[string]interface{}{{"tool": "list", "arguments": map[string]interface{}{"path": "/tmp"}}}}, ""},
}

func findRepoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return filepath.Dir(filepath.Dir(file))
}

func ensureJavaJar(t *testing.T, repoRoot string) {
	t.Helper()
	cmd := exec.Command("./gradlew", "jar")
	cmd.Dir = filepath.Join(repoRoot, "java")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("java jar build failed: %v\n%s", err, string(out))
	}
}

func buildBinary(t *testing.T, repoRoot string) string {
	t.Helper()
	binPath := filepath.Join(t.TempDir(), "mcphub")
	cmd := exec.Command("go", "build", "-o", binPath, "./cmd/mcphub")
	cmd.Dir = repoRoot
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("go build failed: %v\n%s", err, string(out))
	}
	return binPath
}

func startDaemon(t *testing.T, binPath, repoRoot string) (sockPath, dataDir string, cleanup func()) {
	return startDaemonWithFixture(t, binPath, repoRoot, "")
}

// startDaemonWithFixture starts the daemon with an optional MCPHUB_TEST_FIXTURE yaml path.
// If fixturePath is non-empty, it is passed via env and Main.java loads it as additional
// capability entries (used for VT-017 synthetic_delay).
func startDaemonWithFixture(t *testing.T, binPath, repoRoot, fixturePath string) (sockPath, dataDir string, cleanup func()) {
	t.Helper()

	socketDir := t.TempDir()
	sockPath = filepath.Join(socketDir, "mcphub.sock")
	dataDir = t.TempDir()
	homeDir := t.TempDir()

	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, binPath, "_daemon")
	env := append(os.Environ(),
		"MCPHUB_SOCKET_PATH="+sockPath,
		"MCPHUB_DATA_DIR="+dataDir,
		"MCPHUB_ADAPTER_DIR="+filepath.Join(repoRoot, "adapters", "dist"),
		"HOME="+homeDir,
	)
	if fixturePath != "" {
		env = append(env, "MCPHUB_TEST_FIXTURE="+fixturePath)
	}
	cmd.Env = env
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatalf("daemon start failed: %v", err)
	}

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("unix", sockPath, 200*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if _, err := os.Stat(sockPath); err != nil {
		cancel()
		_ = cmd.Wait()
		t.Fatalf("daemon socket not ready: %v", err)
	}

	cleanup = func() {
		cancel()
		_ = cmd.Wait()
	}
	return sockPath, dataDir, cleanup
}

func call(t *testing.T, sockPath, method string, params interface{}) (json.RawMessage, *struct {
	Code    int
	Message string
}) {
	t.Helper()

	conn, err := net.DialTimeout("unix", sockPath, 3*time.Second)
	if err != nil {
		t.Fatalf("dial %s: %v", sockPath, err)
	}
	defer conn.Close()

	req := map[string]interface{}{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  method,
		"params":  params,
	}

	_ = conn.SetDeadline(time.Now().Add(35 * time.Second))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		t.Fatalf("write req (%s): %v", method, err)
	}

	var resp struct {
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatalf("decode resp (%s): %v", method, err)
	}
	if resp.Error != nil {
		return nil, &struct {
			Code    int
			Message string
		}{Code: resp.Error.Code, Message: resp.Error.Message}
	}
	return resp.Result, nil
}

func armAndOpen(t *testing.T, sockPath string) {
	t.Helper()
	if _, rpcErr := call(t, sockPath, "mcphub.control.arm", nil); rpcErr != nil {
		t.Fatalf("arm failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	time.Sleep(100 * time.Millisecond)
	if _, rpcErr := call(t, sockPath, "mcphub.control.open", nil); rpcErr != nil {
		t.Fatalf("open failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	// Adapter registration is async after OPEN.
	time.Sleep(2 * time.Second)
}

func closeSession(t *testing.T, sockPath string) {
	t.Helper()
	if _, rpcErr := call(t, sockPath, "mcphub.control.close", nil); rpcErr != nil {
		t.Fatalf("close failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
}

func statusState(t *testing.T, sockPath string) string {
	t.Helper()
	result, rpcErr := call(t, sockPath, "mcphub.control.status", nil)
	if rpcErr != nil {
		t.Fatalf("status failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	var status struct {
		State string `json:"state"`
	}
	if err := json.Unmarshal(result, &status); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	return status.State
}

func waitForState(t *testing.T, sockPath, want string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if got := statusState(t, sockPath); got == want {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	got := statusState(t, sockPath)
	t.Fatalf("state did not reach %s within %s; got %s", want, timeout, got)
}

func waitForSocketVT(t *testing.T, sockPath string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if c, err := net.DialTimeout("unix", sockPath, 100*time.Millisecond); err == nil {
			_ = c.Close()
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("socket %s did not become ready", sockPath)
}

// queryDB runs a read-only SQL query via the mcphub Java CLI (AMD-MCPHUB-001).
// Returns parsed JSON array of row objects.
func queryDB(t *testing.T, dataDir, sqlText string) []map[string]interface{} {
	t.Helper()
	repoRoot := findRepoRoot(t)
	jars, _ := filepath.Glob(filepath.Join(repoRoot, "java", "build", "libs", "*.jar"))
	if len(jars) == 0 {
		t.Fatal("mcphub-core.jar not found")
	}
	jar := jars[0]
	for _, j := range jars {
		if !strings.Contains(filepath.Base(j), "plain") {
			jar = j
			break
		}
	}

	cmd := exec.Command("java", "-jar", jar, "query", "--sql", sqlText, "--json")
	cmd.Env = append(os.Environ(), "MCPHUB_DATA_DIR="+dataDir)
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("queryDB failed: %v (sql: %s)", err, sqlText)
	}

	var rows []map[string]interface{}
	if err := json.Unmarshal(out, &rows); err != nil {
		t.Fatalf("queryDB unmarshal: %v (output: %s)", err, string(out))
	}
	return rows
}

func readStateTransitions(t *testing.T, dataDir string) []string {
	t.Helper()
	rows := queryDB(t, dataDir, "SELECT to_state FROM session_log WHERE event_type='state_change' ORDER BY id ASC")
	var transitions []string
	for _, row := range rows {
		if s, ok := row["to_state"].(string); ok {
			transitions = append(transitions, s)
		}
	}
	return transitions
}

func containsAllTransitions(got []string, want ...string) bool {
	set := make(map[string]struct{}, len(got))
	for _, s := range got {
		set[s] = struct{}{}
	}
	for _, w := range want {
		if _, ok := set[w]; !ok {
			return false
		}
	}
	return true
}

func probeNetwork(hostPort string) error {
	conn, err := net.DialTimeout("tcp", hostPort, 2*time.Second)
	if err != nil {
		return err
	}
	return conn.Close()
}

func runToolCall(t *testing.T, sockPath, toolName string, args map[string]interface{}) {
	t.Helper()
	params := map[string]interface{}{"name": toolName, "arguments": args}

	result, rpcErr := call(t, sockPath, "tools/call", params)
	if rpcErr != nil {
		t.Errorf("tools/call %q RPC error: %d %s", toolName, rpcErr.Code, rpcErr.Message)
		return
	}

	var out map[string]interface{}
	if err := json.Unmarshal(result, &out); err != nil {
		t.Errorf("tools/call %q decode result: %v", toolName, err)
		return
	}

	if errorCodeRaw, ok := out["error_code"]; ok {
		errorCode, _ := errorCodeRaw.(string)
		if _, blocked := mcphubRoutingFailureCodes[errorCode]; blocked {
			t.Errorf("tools/call %q MCPHUB routing failure: error_code=%s reason=%v", toolName, errorCode, out["reason"])
			return
		}
	}

	if _, ok := out["content"]; !ok {
		t.Errorf("tools/call %q missing content field: %v", toolName, out)
	}
}

func verifyRouteLog(t *testing.T, dataDir string, expectedTools []string) {
	t.Helper()

	deadline := time.Now().Add(8 * time.Second)
	for {
		rows := queryDB(t, dataDir, "SELECT tool_name FROM route_log WHERE route_decision='allowed' AND provider_type='builtin_hosted'")
		found := make(map[string]bool)
		for _, row := range rows {
			if name, ok := row["tool_name"].(string); ok {
				found[name] = true
			}
		}

		missing := make([]string, 0)
		for _, tool := range expectedTools {
			if !found[tool] {
				missing = append(missing, tool)
			}
		}

		if len(missing) == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("VT-012a failed: missing route_log allowed+builtin_hosted entries for: %v", missing)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func TestVT_001_through_012(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration VT test in short mode")
	}

	start := time.Now()
	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	executedTools := make([]string, 0, len(vtTools))

	for _, vt := range vtTools {
		vt := vt
		t.Run(vt.id+"_"+vt.name, func(t *testing.T) {
			if vt.requiresNet != "" {
				if err := probeNetwork(vt.requiresNet); err != nil {
					t.Skipf("%s skipped: network unavailable (%v)", vt.id, err)
				}
			}
			runToolCall(t, sockPath, vt.name, vt.args)
			executedTools = append(executedTools, vt.name)
		})
	}

	if len(executedTools) == 0 {
		t.Fatal("no VT tools executed")
	}

	t.Run("VT-012a_route_log_allowed_builtin_hosted", func(t *testing.T) {
		verifyRouteLog(t, dataDir, executedTools)
	})

	t.Logf("VT integration runtime: %s", time.Since(start).Round(time.Millisecond))
	t.Logf("VT integration executed tools: %d", len(executedTools))
}

func TestVT_016_LatencyP50P99(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	for i := 0; i < 100; i++ {
		params := map[string]interface{}{
			"name":      "list",
			"arguments": map[string]interface{}{"path": "/tmp"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("iteration %d: RPC error %d: %s", i, rpcErr.Code, rpcErr.Message)
		}

		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("iteration %d: decode result: %v", i, err)
		}
		if errorCodeRaw, ok := out["error_code"]; ok {
			errorCode, _ := errorCodeRaw.(string)
			if _, blocked := mcphubRoutingFailureCodes[errorCode]; blocked {
				t.Fatalf("iteration %d: MCPHUB routing failure: error_code=%s reason=%v", i, errorCode, out["reason"])
			}
		}
		if _, ok := out["content"]; !ok {
			t.Fatalf("iteration %d: missing content field: %v", i, out)
		}
	}

	// Route logging is async; allow writes to flush.
	time.Sleep(500 * time.Millisecond)

	rows := queryDB(t, dataDir, "SELECT latency_ms FROM route_log WHERE tool_name='list' AND route_decision='allowed' ORDER BY latency_ms ASC")
	var latencies []int64
	for _, row := range rows {
		if v, ok := row["latency_ms"].(float64); ok {
			latencies = append(latencies, int64(v))
		}
	}

	if len(latencies) < 100 {
		t.Fatalf("expected >=100 route_log entries, got %d", len(latencies))
	}

	n := len(latencies)
	p50 := latencies[n/2]
	p99Rank := int(math.Ceil(0.99 * float64(n)))
	if p99Rank < 1 {
		p99Rank = 1
	}
	p99 := latencies[p99Rank-1]

	t.Logf("VT-016: p50=%dms p99=%dms (N=%d)", p50, p99, n)

	if p50 >= 50 {
		t.Errorf("VT-016 FAIL: p50=%dms >= 50ms", p50)
	}
	if p99 >= 100 {
		t.Errorf("VT-016 FAIL: p99=%dms >= 100ms", p99)
	}
}

func TestVT_017_LatencyExcludesProviderTime(t *testing.T) {
	if testing.Short() {
		t.Skip()
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	// VT-017 requires a synthetic slow provider. The capability definition is kept
	// OUT of production capabilities.yaml for security — it lives only in the test
	// fixture. We load it via MCPHUB_TEST_FIXTURE env var (see Main.java).
	fixturePath := filepath.Join(repoRoot, "java", "src", "test", "resources",
		"capabilities-synthetic-fixture.yaml")
	if _, err := os.Stat(fixturePath); err != nil {
		t.Fatalf("VT-017 fixture not found at %s: %v", fixturePath, err)
	}
	sockPath, dataDir, cleanup := startDaemonWithFixture(t, binPath, repoRoot, fixturePath)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	params := map[string]interface{}{
		"name":      "synthetic_delay",
		"arguments": map[string]interface{}{"delay_ms": 500},
	}
	start := time.Now()
	_, rpcErr := call(t, sockPath, "tools/call", params)
	elapsed := time.Since(start)
	if rpcErr != nil {
		t.Fatalf("synthetic_delay call failed: %v", rpcErr)
	}

	if elapsed < 450*time.Millisecond {
		t.Errorf("VT-017: expected ~500ms elapsed, got %v", elapsed)
	}

	time.Sleep(500 * time.Millisecond)

	rows := queryDB(t, dataDir, "SELECT latency_ms FROM route_log WHERE tool_name='synthetic_delay' ORDER BY id DESC LIMIT 1")
	if len(rows) == 0 {
		t.Fatal("VT-017: no route_log entry for synthetic_delay")
	}
	latencyMs := int64(rows[0]["latency_ms"].(float64))

	t.Logf("VT-017: total elapsed=%v, latency_ms=%d", elapsed, latencyMs)

	if latencyMs >= 100 {
		t.Errorf("VT-017 FAIL: MCPHUB latency_ms=%d includes provider time (should be < 100ms)", latencyMs)
	}
}

func TestVT_018_NormalLifecycle(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration VT test in short mode")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	if got := statusState(t, sockPath); got != "CLOSED" {
		t.Fatalf("VT-018: initial state expected CLOSED, got %s", got)
	}

	if _, rpcErr := call(t, sockPath, "mcphub.control.arm", nil); rpcErr != nil {
		t.Fatalf("VT-018: arm failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	if got := statusState(t, sockPath); got != "ARMED" {
		t.Fatalf("VT-018: after arm expected ARMED, got %s", got)
	}

	if _, rpcErr := call(t, sockPath, "mcphub.control.open", nil); rpcErr != nil {
		t.Fatalf("VT-018: open failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	if got := statusState(t, sockPath); got != "OPEN" {
		t.Fatalf("VT-018: after open expected OPEN, got %s", got)
	}

	if _, rpcErr := call(t, sockPath, "mcphub.control.close", nil); rpcErr != nil {
		t.Fatalf("VT-018: close failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	waitForState(t, sockPath, "CLOSED", 3*time.Second)

	transitions := readStateTransitions(t, dataDir)
	t.Logf("VT-018: recorded transitions: %v", transitions)

	if len(transitions) < 4 {
		t.Fatalf("VT-018: expected at least 4 state_change entries, got %d", len(transitions))
	}
	if !containsAllTransitions(transitions, "ARMED", "OPEN", "COOLING_DOWN", "CLOSED") {
		t.Fatalf("VT-018: missing required transitions ARMED/OPEN/COOLING_DOWN/CLOSED in %v", transitions)
	}
}

func TestVT_019_IdleTimeoutAutoClose(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	dataDir := t.TempDir()

	configPath := filepath.Join(dataDir, "config.yaml")
	if err := os.WriteFile(configPath, []byte("session:\n  idle_timeout_seconds: 3\n  armed_timeout_seconds: 3\n"), 0644); err != nil {
		t.Fatalf("write config.yaml: %v", err)
	}

	sockPath := filepath.Join(t.TempDir(), "mcphub.sock")
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, binPath, "_daemon")
	cmd.Env = append(os.Environ(),
		"MCPHUB_SOCKET_PATH="+sockPath,
		"MCPHUB_DATA_DIR="+dataDir,
		"MCPHUB_ADAPTER_DIR="+filepath.Join(repoRoot, "adapters", "dist"),
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatalf("daemon start: %v", err)
	}
	defer func() {
		cancel()
		_ = cmd.Wait()
	}()

	waitForSocketVT(t, sockPath)

	if _, rpcErr := call(t, sockPath, "mcphub.control.arm", nil); rpcErr != nil {
		t.Fatalf("VT-019: arm failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	if _, rpcErr := call(t, sockPath, "mcphub.control.open", nil); rpcErr != nil {
		t.Fatalf("VT-019: open failed: %d %s", rpcErr.Code, rpcErr.Message)
	}

	state := statusState(t, sockPath)
	if state != "OPEN" {
		t.Fatalf("expected OPEN, got %s", state)
	}

	time.Sleep(6 * time.Second)

	state = statusState(t, sockPath)
	if state != "CLOSED" {
		t.Errorf("VT-019: expected CLOSED after idle timeout, got %s", state)
	}

	transitions := readStateTransitions(t, dataDir)
	t.Logf("VT-019: transitions = %v", transitions)
	found := false
	for _, s := range transitions {
		if s == "COOLING_DOWN" || s == "CLOSED" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("VT-019: expected COOLING_DOWN or CLOSED in session_log")
	}
}

func TestVT_020_ParentExitResumesIdleAutoClose(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration VT test in short mode")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	dataDir := t.TempDir()

	configPath := filepath.Join(dataDir, "config.yaml")
	if err := os.WriteFile(configPath, []byte("session:\n  idle_timeout_seconds: 3\n  armed_timeout_seconds: 3\n"), 0644); err != nil {
		t.Fatalf("VT-020: write config.yaml: %v", err)
	}

	sockPath := filepath.Join(t.TempDir(), "mcphub.sock")
	homeDir := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, binPath, "_daemon")
	cmd.Env = append(os.Environ(),
		"MCPHUB_SOCKET_PATH="+sockPath,
		"MCPHUB_DATA_DIR="+dataDir,
		"MCPHUB_ADAPTER_DIR="+filepath.Join(repoRoot, "adapters", "dist"),
		"HOME="+homeDir,
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatalf("VT-020: daemon start failed: %v", err)
	}
	defer func() {
		cancel()
		_ = cmd.Wait()
	}()

	waitForSocketVT(t, sockPath)

	armAndOpen(t, sockPath)
	if got := statusState(t, sockPath); got != "OPEN" {
		t.Fatalf("VT-020: expected OPEN before bridge exit probe, got %s", got)
	}

	bridge := exec.Command(binPath, "bridge")
	bridge.Env = append(os.Environ(), "MCPHUB_SOCKET_PATH="+sockPath)
	bridge.Stderr = os.Stderr

	stdin, err := bridge.StdinPipe()
	if err != nil {
		t.Fatalf("VT-020: stdin pipe: %v", err)
	}
	if err := bridge.Start(); err != nil {
		t.Fatalf("VT-020: bridge start failed: %v", err)
	}

	if _, err := stdin.Write([]byte(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}` + "\n")); err != nil {
		t.Fatalf("VT-020: write initialize: %v", err)
	}

	if err := stdin.Close(); err != nil {
		t.Fatalf("VT-020: close bridge stdin: %v", err)
	}
	if err := bridge.Wait(); err != nil {
		t.Fatalf("VT-020: bridge wait failed: %v", err)
	}

	if got := statusState(t, sockPath); got != "OPEN" {
		t.Fatalf("VT-020: expected OPEN immediately after bridge exit, got %s", got)
	}
	time.Sleep(6 * time.Second)
	if got := statusState(t, sockPath); got != "CLOSED" {
		t.Fatalf("VT-020: expected CLOSED after resumed idle timeout, got %s", got)
	}

	transitions := readStateTransitions(t, dataDir)
	t.Logf("VT-020: recorded transitions: %v", transitions)
	joined := strings.Join(transitions, ",")
	if !strings.Contains(joined, "COOLING_DOWN") || !strings.Contains(joined, "CLOSED") {
		t.Fatalf("VT-020: expected COOLING_DOWN and CLOSED transitions after resumed idle timeout, got %v", transitions)
	}
}

func TestVT_020a_ArmedTimeoutAutoClose(t *testing.T) {
	if testing.Short() {
		t.Skip("integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	dataDir := t.TempDir()

	configPath := filepath.Join(dataDir, "config.yaml")
	if err := os.WriteFile(configPath, []byte("session:\n  idle_timeout_seconds: 3\n  armed_timeout_seconds: 3\n"), 0644); err != nil {
		t.Fatalf("write config.yaml: %v", err)
	}

	sockPath := filepath.Join(t.TempDir(), "mcphub.sock")
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, binPath, "_daemon")
	cmd.Env = append(os.Environ(),
		"MCPHUB_SOCKET_PATH="+sockPath,
		"MCPHUB_DATA_DIR="+dataDir,
		"MCPHUB_ADAPTER_DIR="+filepath.Join(repoRoot, "adapters", "dist"),
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatalf("daemon start: %v", err)
	}
	defer func() {
		cancel()
		_ = cmd.Wait()
	}()
	waitForSocketVT(t, sockPath)

	if _, rpcErr := call(t, sockPath, "mcphub.control.arm", nil); rpcErr != nil {
		t.Fatalf("VT-020a: arm failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	state := statusState(t, sockPath)
	if state != "ARMED" {
		t.Fatalf("expected ARMED, got %s", state)
	}

	time.Sleep(5 * time.Second)

	state = statusState(t, sockPath)
	if state != "CLOSED" {
		t.Errorf("VT-020a: expected CLOSED after armed timeout, got %s", state)
	}
}

// TestNewToolSurface_SessionRecovery verifies:
// - Closed session returns session_not_open with mcphub.session.open recovery path
// - mcphub.session.open works to recover
// - nexus_issue_list works in OPEN state
// - task_list works in OPEN state
// - mcphub_checkpoint works in OPEN state
// - apply_patch works in OPEN state
func TestNewToolSurface_SessionRecovery(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, _, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	// --- CLOSED state: tools/list shows only mcphub.session.open ---
	t.Run("closed_tools_list_shows_session_open_only", func(t *testing.T) {
		result, rpcErr := call(t, sockPath, "tools/list", nil)
		if rpcErr != nil {
			t.Fatalf("tools/list RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var listResult struct {
			Tools []struct {
				Name string `json:"name"`
			} `json:"tools"`
		}
		if err := json.Unmarshal(result, &listResult); err != nil {
			t.Fatalf("unmarshal tools/list: %v", err)
		}
		if len(listResult.Tools) != 1 {
			t.Fatalf("expected 1 tool in CLOSED state, got %d", len(listResult.Tools))
		}
		if listResult.Tools[0].Name != "mcphub.session.open" {
			t.Fatalf("expected mcphub.session.open, got %s", listResult.Tools[0].Name)
		}
	})

	// --- CLOSED state: nexus_issue_list returns session_not_open with actionable recovery ---
	t.Run("closed_nexus_returns_session_not_open_with_recovery", func(t *testing.T) {
		params := map[string]interface{}{
			"name":      "nexus_issue_list",
			"arguments": map[string]interface{}{"project": "testproj"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("tools/call RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		if out["isError"] != true {
			t.Fatalf("expected isError=true, got %v", out["isError"])
		}
		contentArr, ok := out["content"].([]interface{})
		if !ok || len(contentArr) == 0 {
			t.Fatalf("expected content array, got %v", out["content"])
		}
		textContent := contentArr[0].(map[string]interface{})["text"].(string)
		// Verify actionable recovery: must mention mcphub.session.open
		if !strings.Contains(textContent, "mcphub.session.open") {
			t.Errorf("session_not_open error must mention mcphub.session.open for actionable recovery, got: %s", textContent)
		}
		if !strings.Contains(textContent, "cli_recovery_command:") || !strings.Contains(textContent, "mcphub open") {
			t.Errorf("session_not_open error must include exact CLI recovery command for clients that cannot call mcphub.session.open, got: %s", textContent)
		}
		// Verify next_action is not just "wait_session"
		if strings.Contains(textContent, "wait_session") && !strings.Contains(textContent, "mcphub.session.open") {
			t.Errorf("session_not_open error with wait_session alone (no actionable path) is not acceptable, got: %s", textContent)
		}
	})

	// --- Open session via mcphub.session.open ---
	t.Run("session_open_recovers", func(t *testing.T) {
		params := map[string]interface{}{
			"name":      "mcphub.session.open",
			"arguments": map[string]interface{}{},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("mcphub.session.open RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal session.open result: %v", err)
		}
		contentArr, ok := out["content"].([]interface{})
		if !ok || len(contentArr) == 0 {
			t.Fatalf("expected content in session.open result, got %v", out)
		}
		textContent := contentArr[0].(map[string]interface{})["text"].(string)
		if !strings.Contains(textContent, "OPEN") {
			t.Fatalf("expected OPEN state in session.open response, got: %s", textContent)
		}
	})

	// Wait for adapter registration
	time.Sleep(2 * time.Second)

	// --- OPEN state: nexus_issue_list works ---
	t.Run("open_nexus_issue_list_works", func(t *testing.T) {
		params := map[string]interface{}{
			"name":      "nexus_issue_list",
			"arguments": map[string]interface{}{"project": "testproj"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("nexus_issue_list RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		// Should not be an error
		if isError, _ := out["isError"].(bool); isError {
			contentArr, _ := out["content"].([]interface{})
			if len(contentArr) > 0 {
				t.Fatalf("nexus_issue_list failed in OPEN state: %v", contentArr[0])
			}
		}
		if out["content"] == nil {
			t.Fatalf("nexus_issue_list returned no content")
		}
	})

	// --- OPEN state: task_list works ---
	t.Run("open_task_list_works", func(t *testing.T) {
		params := map[string]interface{}{
			"name":      "task_list",
			"arguments": map[string]interface{}{"status": "all"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("task_list RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("task_list should succeed in OPEN state")
		}
		if out["content"] == nil {
			t.Fatalf("task_list returned no content")
		}
	})

	// --- OPEN state: mcphub_checkpoint works ---
	t.Run("open_checkpoint_works", func(t *testing.T) {
		params := map[string]interface{}{
			"name":      "mcphub_checkpoint",
			"arguments": map[string]interface{}{"message": "Test checkpoint from integration", "project": "testproj"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("mcphub_checkpoint RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("mcphub_checkpoint should succeed in OPEN state")
		}
		contentArr, ok := out["content"].([]interface{})
		if !ok || len(contentArr) == 0 {
			t.Fatalf("mcphub_checkpoint returned no content")
		}
		textContent := contentArr[0].(map[string]interface{})["text"].(string)
		if !strings.Contains(textContent, "checkpoint_time") {
			t.Errorf("mcphub_checkpoint response should contain checkpoint_time, got: %s", textContent[:200])
		}
	})

	// --- OPEN state: apply_patch still works ---
	t.Run("open_apply_patch_works", func(t *testing.T) {
		tmpPatch := "--- /dev/null\n+++ b/vt_surface_test.txt\n@@ -0,0 +1 @@\n+surface-test\n"
		params := map[string]interface{}{
			"name":      "apply_patch",
			"arguments": map[string]interface{}{"patch": tmpPatch, "cwd": "/tmp"},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("apply_patch RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		// apply_patch can succeed or fail depending on patch state; just verify it was routed
		if errorCode, ok := out["error_code"].(string); ok {
			if _, blocked := mcphubRoutingFailureCodes[errorCode]; blocked {
				t.Fatalf("apply_patch routing failure: %v", out)
			}
		}
		if out["content"] == nil {
			t.Fatalf("apply_patch returned no content")
		}
	})

	// Close
	closeSession(t, sockPath)
}

// TestApplyPatch_Classifications verifies deterministic failure diagnostics for
// apply_patch output. Covers: success with git-style headers, plain headers failure,
// file-not-found, and hunk failures. No external services required.
func TestApplyPatch_Classifications(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, _, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	scratchDir := t.TempDir()

	hunkFile := filepath.Join(scratchDir, "hunk_test.txt")
	if err := os.WriteFile(hunkFile, []byte("line1\nline2\nline3\n"), 0644); err != nil {
		t.Fatalf("write hunk_test.txt: %v", err)
	}

	type testCase struct {
		name           string
		patchContent   string
		cwd            string
		wantText       string
		wantNextAction string
		isError        bool
	}

	tests := []testCase{
		{
			name:           "success_git_style_headers",
			patchContent:   "--- /dev/null\n+++ b/success_test.txt\n@@ -0,0 +1 @@\n+hello\n",
			cwd:            scratchDir,
			wantText:       "",
			wantNextAction: "",
			isError:        false,
		},
		{
			// Plain headers are now auto-detected and applied with patch -p0.
			// file.txt does not exist in scratchDir, so patch will report can't find file.
			name:           "plain_headers_p0_file_not_found",
			patchContent:   "--- file.txt\n+++ file.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n",
			cwd:            scratchDir,
			wantText:       "Cannot find file",
			wantNextAction: "disambiguate",
			isError:        true,
		},
		{
			name:           "file_not_found",
			patchContent:   "--- a/nonexistent.txt\n+++ b/nonexistent.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n",
			cwd:            scratchDir,
			wantText:       "Cannot find file",
			wantNextAction: "disambiguate",
			isError:        true,
		},
		{
			name:           "hunk_failure_stale_context",
			patchContent:   "--- a/hunk_test.txt\n+++ b/hunk_test.txt\n@@ -1,3 +1,3 @@\n-wrong\n-line2\n-line3\n+correct\n+line2\n+line3\n",
			cwd:            scratchDir,
			wantText:       "hunk(s) failed",
			wantNextAction: "retry",
			isError:        true,
		},
		{
			// QA-F5: plain unified diff with ../escape path — preflight must reject before invoking patch.
			// wantText is specific to the safety error, not just "abort".
			name:           "plain_traversal_preflight_rejected",
			patchContent:   "--- ../escape.txt\n+++ ../escape.txt\n@@ -1,1 +1,1 @@\n-x\n+y\n",
			cwd:            scratchDir,
			wantText:       "Unsafe path",
			wantNextAction: "abort",
			isError:        true,
		},
		{
			// QA-F5: git-style unified diff with a/../escape path — strip first component leaves
			// ../escape which must be rejected by preflight path validation.
			// wantText is specific to the safety error, not just "abort".
			name:           "git_traversal_preflight_rejected",
			patchContent:   "--- a/../escape.txt\n+++ b/../escape.txt\n@@ -1,1 +1,1 @@\n-x\n+y\n",
			cwd:            scratchDir,
			wantText:       "Unsafe path",
			wantNextAction: "abort",
			isError:        true,
		},
		{
			// QA-Fix1: mixed git-style and plain-style headers in same patch — must reject without
			// invoking patch at a wrong strip level.
			name: "mixed_unified_diff_headers_rejected",
			patchContent: "--- a/foo.txt\n+++ b/foo.txt\n@@ -1 +1 @@\n-x\n+y\n" +
				"--- bar.txt\n+++ bar.txt\n@@ -1 +1 @@\n-a\n+b\n",
			cwd:            scratchDir,
			wantText:       "mixed",
			wantNextAction: "abort",
			isError:        true,
		},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			params := map[string]interface{}{
				"name":      "apply_patch",
				"arguments": map[string]interface{}{"patch": tc.patchContent, "cwd": tc.cwd},
			}

			result, rpcErr := call(t, sockPath, "tools/call", params)
			if rpcErr != nil {
				t.Errorf("RPC error: %d %s", rpcErr.Code, rpcErr.Message)
				return
			}

			var out map[string]interface{}
			if err := json.Unmarshal(result, &out); err != nil {
				t.Errorf("unmarshal result: %v", err)
				return
			}

			isError, _ := out["isError"].(bool)
			if isError != tc.isError {
				t.Errorf("isError: got %v, want %v", isError, tc.isError)
			}

			contentArr, ok := out["content"].([]interface{})
			if !ok || len(contentArr) == 0 {
				t.Errorf("expected content array, got %v", out)
				return
			}
			textContent, ok := contentArr[0].(map[string]interface{})["text"].(string)
			if !ok {
				t.Errorf("content[0].text not a string: %T", contentArr[0])
				return
			}

			if tc.wantText == "" && !tc.isError {
				if !strings.Contains(strings.ToLower(textContent), "patch") && !strings.Contains(strings.ToLower(textContent), "applied") {
					t.Errorf("expected success message containing 'patch' or 'applied', got: %s", textContent)
				}
			} else if tc.wantText != "" {
				if !strings.Contains(textContent, tc.wantText) {
					t.Errorf("content text does not contain %q, got: %s", tc.wantText, textContent)
				}
			}

			if tc.wantNextAction != "" {
				if !strings.Contains(textContent, "next_action: "+tc.wantNextAction) {
					t.Errorf("next_action not %q in text, got: %s", tc.wantNextAction, textContent)
				}
			}
		})
	}

	_ = os.Remove(filepath.Join(scratchDir, "success_test.txt"))
}

// TestApplyPatch_BeginPatch verifies the OpenAI/GPT-style "*** Begin Patch" format support.
// Tests: Add File, Update File, Delete File, context mismatch, path traversal rejection,
// plain unified diff (-p0), and git-style unified diff regression — all direct-to-adapter.
func TestApplyPatch_BeginPatch(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, _, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	scratchDir := t.TempDir()

	callApplyPatch := func(t *testing.T, patchContent, cwd string) map[string]interface{} {
		t.Helper()
		params := map[string]interface{}{
			"name":      "apply_patch",
			"arguments": map[string]interface{}{"patch": patchContent, "cwd": cwd},
		}
		result, rpcErr := call(t, sockPath, "tools/call", params)
		if rpcErr != nil {
			t.Fatalf("RPC error: %d %s", rpcErr.Code, rpcErr.Message)
		}
		var out map[string]interface{}
		if err := json.Unmarshal(result, &out); err != nil {
			t.Fatalf("unmarshal result: %v", err)
		}
		return out
	}

	getText := func(t *testing.T, out map[string]interface{}) string {
		t.Helper()
		contentArr, ok := out["content"].([]interface{})
		if !ok || len(contentArr) == 0 {
			t.Fatalf("expected content array, got %v", out)
		}
		text, ok := contentArr[0].(map[string]interface{})["text"].(string)
		if !ok {
			t.Fatalf("content[0].text not string")
		}
		return text
	}

	// --- Begin Patch: Add File ---
	t.Run("begin_patch_add_file", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_add.txt")
		_ = os.Remove(targetFile) // ensure clean
		patch := "*** Begin Patch\n*** Add File: bp_add.txt\n@@\n+line one\n+line two\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Begin Patch Add File should succeed, got error: %s", getText(t, out))
		}
		got, err := os.ReadFile(targetFile)
		if err != nil {
			t.Fatalf("added file not found: %v", err)
		}
		content := string(got)
		if !strings.Contains(content, "line one") || !strings.Contains(content, "line two") {
			t.Errorf("added file content unexpected: %q", content)
		}
		_ = os.Remove(targetFile)
	})

	// --- Begin Patch: Add File already exists ---
	t.Run("begin_patch_add_file_already_exists", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_exists.txt")
		if err := os.WriteFile(targetFile, []byte("existing\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		patch := "*** Begin Patch\n*** Add File: bp_exists.txt\n@@\n+new content\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected error when Add File target already exists")
		}
		text := getText(t, out)
		if !strings.Contains(text, "already exists") {
			t.Errorf("expected 'already exists' in error text, got: %s", text)
		}
	})

	// --- Begin Patch: Update File ---
	t.Run("begin_patch_update_file", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_update.txt")
		if err := os.WriteFile(targetFile, []byte("alpha\nbeta\ngamma\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		patch := "*** Begin Patch\n*** Update File: bp_update.txt\n@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Begin Patch Update File should succeed, got error: %s", getText(t, out))
		}
		got, err := os.ReadFile(targetFile)
		if err != nil {
			t.Fatalf("updated file not readable: %v", err)
		}
		content := string(got)
		if !strings.Contains(content, "BETA") || strings.Contains(content, "\nbeta\n") {
			t.Errorf("Update File content unexpected: %q", content)
		}
	})

	// --- Begin Patch: Delete File ---
	t.Run("begin_patch_delete_file", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_delete.txt")
		if err := os.WriteFile(targetFile, []byte("to be deleted\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		patch := "*** Begin Patch\n*** Delete File: bp_delete.txt\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Begin Patch Delete File should succeed, got error: %s", getText(t, out))
		}
		if _, err := os.Stat(targetFile); !os.IsNotExist(err) {
			t.Errorf("deleted file still exists at %s", targetFile)
			_ = os.Remove(targetFile)
		}
	})

	// --- Begin Patch: Delete File not found ---
	t.Run("begin_patch_delete_file_not_found", func(t *testing.T) {
		patch := "*** Begin Patch\n*** Delete File: bp_nonexistent.txt\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected error when Delete File target does not exist")
		}
		text := getText(t, out)
		if !strings.Contains(text, "does not exist") {
			t.Errorf("expected 'does not exist' in error text, got: %s", text)
		}
	})

	// --- Begin Patch: Context mismatch ---
	t.Run("begin_patch_context_mismatch", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_mismatch.txt")
		if err := os.WriteFile(targetFile, []byte("lineA\nlineB\nlineC\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		// Patch refers to context lines that don't match actual file content
		patch := "*** Begin Patch\n*** Update File: bp_mismatch.txt\n@@ -1,3 +1,3 @@\n wrongCtx\n-lineB\n+LINEB\n lineC\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected error for context mismatch in Begin Patch Update File")
		}
		text := getText(t, out)
		if !strings.Contains(text, "Context not found") && !strings.Contains(text, "Hunk failed") && !strings.Contains(text, "hunk") {
			t.Errorf("expected context mismatch error, got: %s", text)
		}
	})

	// --- Begin Patch: Path traversal rejection ---
	t.Run("begin_patch_path_traversal", func(t *testing.T) {
		patch := "*** Begin Patch\n*** Add File: ../escape.txt\n@@\n+evil\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected path traversal to be rejected")
		}
		text := getText(t, out)
		if !strings.Contains(text, "..") && !strings.Contains(text, "traversal") && !strings.Contains(text, "safety") {
			t.Errorf("expected path safety error, got: %s", text)
		}
	})

	// --- Begin Patch: Absolute path rejection ---
	t.Run("begin_patch_absolute_path_rejected", func(t *testing.T) {
		patch := "*** Begin Patch\n*** Add File: /tmp/absolute_escape.txt\n@@\n+evil\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected absolute path to be rejected")
		}
		text := getText(t, out)
		if !strings.Contains(text, "absolute") && !strings.Contains(text, "safety") {
			t.Errorf("expected absolute path error, got: %s", text)
		}
	})

	// --- Plain unified diff: success with -p0 (relative paths) ---
	t.Run("plain_unified_diff_p0_success", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "p0_target.txt")
		if err := os.WriteFile(targetFile, []byte("hello\nworld\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		// Plain header: no a/ b/ prefix — patch -p0 with relative path
		patch := "--- p0_target.txt\n+++ p0_target.txt\n@@ -1,2 +1,2 @@\n-hello\n+HELLO\n world\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Plain unified diff should succeed with -p0 (relative path), got error: %s", getText(t, out))
		}
		got, err := os.ReadFile(targetFile)
		if err != nil {
			t.Fatalf("target file not readable: %v", err)
		}
		if !strings.Contains(string(got), "HELLO") {
			t.Errorf("expected HELLO in patched file, got: %q", string(got))
		}
	})

	// --- Git-style unified diff: regression ---
	t.Run("git_style_unified_diff_regression", func(t *testing.T) {
		patch := "--- /dev/null\n+++ b/git_regression.txt\n@@ -0,0 +1 @@\n+regression-check\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Git-style unified diff should succeed, got error: %s", getText(t, out))
		}
		got, err := os.ReadFile(filepath.Join(scratchDir, "git_regression.txt"))
		if err != nil {
			t.Fatalf("created file not found: %v", err)
		}
		if !strings.Contains(string(got), "regression-check") {
			t.Errorf("expected 'regression-check' in file, got: %q", string(got))
		}
		_ = os.Remove(filepath.Join(scratchDir, "git_regression.txt"))
	})

	// --- F1: Begin Patch Add File without @@ header (bare + lines) ---
	t.Run("begin_patch_add_file_no_hunk_header", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_bare.txt")
		_ = os.Remove(targetFile)
		// GPT/OpenAI style: bare + lines, no @@ header
		patch := "*** Begin Patch\n*** Add File: bp_bare.txt\n+line alpha\n+line beta\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Add File bare + lines should succeed, got error: %s", getText(t, out))
		}
		got, err := os.ReadFile(targetFile)
		if err != nil {
			t.Fatalf("added file not found: %v", err)
		}
		content := string(got)
		if !strings.Contains(content, "line alpha") || !strings.Contains(content, "line beta") {
			t.Errorf("bare Add File content unexpected: %q", content)
		}
		_ = os.Remove(targetFile)
	})

	// --- F2: Begin Patch Update File with no hunks must fail ---
	t.Run("begin_patch_update_file_no_hunks", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_nohunks.txt")
		if err := os.WriteFile(targetFile, []byte("data\n"), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		// Update File directive with no @@ hunk content
		patch := "*** Begin Patch\n*** Update File: bp_nohunks.txt\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Update File with no hunks should fail, but succeeded")
		}
		text := getText(t, out)
		if !strings.Contains(text, "no hunks") && !strings.Contains(text, "hunk") {
			t.Errorf("expected 'no hunks' in error, got: %s", text)
		}
	})

	// --- F3: Missing *** End Patch marker must fail ---
	t.Run("begin_patch_missing_end_marker", func(t *testing.T) {
		patch := "*** Begin Patch\n*** Add File: bp_noend.txt\n+hello\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Missing End Patch should fail, but succeeded")
		}
		text := getText(t, out)
		if !strings.Contains(text, "End Patch") {
			t.Errorf("expected 'End Patch' in error, got: %s", text)
		}
		// Ensure the file was NOT created (incomplete patch must not be applied)
		if _, err := os.Stat(filepath.Join(scratchDir, "bp_noend.txt")); err == nil {
			t.Errorf("file bp_noend.txt must not be created from incomplete patch")
			_ = os.Remove(filepath.Join(scratchDir, "bp_noend.txt"))
		}
	})

	// --- F4: Filename with .. as part of a segment (not a traversal) must be allowed ---
	t.Run("begin_patch_dotdot_in_filename_allowed", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "version..txt")
		_ = os.Remove(targetFile)
		patch := "*** Begin Patch\n*** Add File: version..txt\n+v2.0\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); isError {
			t.Fatalf("Filename 'version..txt' should be allowed, got error: %s", getText(t, out))
		}
		_ = os.Remove(targetFile)
	})

	// --- F6: Top-level noise in Begin Patch block must fail ---
	t.Run("begin_patch_top_level_noise_rejected", func(t *testing.T) {
		patch := "*** Begin Patch\nsome unexpected content\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Top-level noise in Begin Patch should fail, but succeeded")
		}
		text := getText(t, out)
		if !strings.Contains(text, "Unexpected") && !strings.Contains(text, "malformed") && !strings.Contains(text, "parse error") {
			t.Errorf("expected parse error for top-level noise, got: %s", text)
		}
	})

	// --- QA-Fix2: Begin Patch Update File with pure insertion hunk (no context) must fail ---
	// A hunk with only + lines and no context/removal lines provides no anchor for placement.
	// Silently appending to EOF would be incorrect behaviour for an update operation.
	t.Run("begin_patch_update_file_pure_insertion_rejected", func(t *testing.T) {
		targetFile := filepath.Join(scratchDir, "bp_pureins.txt")
		originalContent := "line1\nline2\n"
		if err := os.WriteFile(targetFile, []byte(originalContent), 0644); err != nil {
			t.Fatalf("setup: %v", err)
		}
		defer os.Remove(targetFile)
		// Hunk with only + lines and no context or - lines — no anchor
		patch := "*** Begin Patch\n*** Update File: bp_pureins.txt\n@@ -1,0 +1,1 @@\n+inserted line\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Pure insertion hunk in Update File should fail, but succeeded")
		}
		text := getText(t, out)
		if !strings.Contains(text, "context") && !strings.Contains(text, "insertion") {
			t.Errorf("expected context/insertion error, got: %s", text)
		}
		// File must be unchanged
		got, err := os.ReadFile(targetFile)
		if err != nil {
			t.Fatalf("could not read target file after rejection: %v", err)
		}
		if string(got) != originalContent {
			t.Errorf("file must be unchanged after pure insertion rejection, got: %q", string(got))
		}
	})

	// --- Atomicity: unified diff multi-file failure must rollback earlier success and artifacts ---
	t.Run("unified_diff_multi_file_failure_rolls_back_all", func(t *testing.T) {
		aFile := filepath.Join(scratchDir, "atomic_a.txt")
		bFile := filepath.Join(scratchDir, "atomic_b.txt")
		if err := os.WriteFile(aFile, []byte("alpha\n"), 0644); err != nil {
			t.Fatalf("setup a: %v", err)
		}
		if err := os.WriteFile(bFile, []byte("beta\n"), 0644); err != nil {
			t.Fatalf("setup b: %v", err)
		}
		defer os.Remove(aFile)
		defer os.Remove(bFile)

		patch := "--- a/atomic_a.txt\n+++ b/atomic_a.txt\n@@ -1 +1 @@\n-alpha\n+ALPHA\n" +
			"--- a/atomic_b.txt\n+++ b/atomic_b.txt\n@@ -1 +1 @@\n-not-beta\n+BETA\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected stale second hunk to fail")
		}
		text := getText(t, out)
		if !strings.Contains(text, "rollback: restored pre-apply state") || !strings.Contains(text, "confirm no .rej/.orig") {
			t.Fatalf("expected rollback and artifact verification guidance, got: %s", text)
		}
		gotA, err := os.ReadFile(aFile)
		if err != nil {
			t.Fatalf("read a after rollback: %v", err)
		}
		gotB, err := os.ReadFile(bFile)
		if err != nil {
			t.Fatalf("read b after rollback: %v", err)
		}
		if string(gotA) != "alpha\n" || string(gotB) != "beta\n" {
			t.Fatalf("files must be restored after failed unified diff, got a=%q b=%q", string(gotA), string(gotB))
		}
		for _, suffix := range []string{".orig", ".rej"} {
			if _, err := os.Stat(bFile + suffix); !os.IsNotExist(err) {
				t.Fatalf("artifact %s must not remain after rollback", bFile+suffix)
			}
		}
	})

	// --- Atomicity: unified diff create then fail must remove created file ---
	t.Run("unified_diff_create_then_fail_removes_created_file", func(t *testing.T) {
		createdFile := filepath.Join(scratchDir, "atomic_created.txt")
		bFile := filepath.Join(scratchDir, "atomic_create_b.txt")
		_ = os.Remove(createdFile)
		if err := os.WriteFile(bFile, []byte("beta\n"), 0644); err != nil {
			t.Fatalf("setup b: %v", err)
		}
		defer os.Remove(bFile)

		patch := "--- /dev/null\n+++ b/atomic_created.txt\n@@ -0,0 +1 @@\n+created\n" +
			"--- a/atomic_create_b.txt\n+++ b/atomic_create_b.txt\n@@ -1 +1 @@\n-not-beta\n+BETA\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected stale second hunk to fail")
		}
		text := getText(t, out)
		if !strings.Contains(text, "rollback: restored pre-apply state") {
			t.Fatalf("expected rollback guidance, got: %s", text)
		}
		if _, err := os.Stat(createdFile); !os.IsNotExist(err) {
			t.Fatalf("created file must be removed after rollback")
		}
		gotB, err := os.ReadFile(bFile)
		if err != nil {
			t.Fatalf("read b after rollback: %v", err)
		}
		if string(gotB) != "beta\n" {
			t.Fatalf("existing file must be restored after rollback, got %q", string(gotB))
		}
	})

	// --- Atomicity: Begin Patch plan failure must leave earlier planned writes unapplied ---
	t.Run("begin_patch_multi_op_failure_leaves_no_earlier_writes", func(t *testing.T) {
		createdFile := filepath.Join(scratchDir, "bp_atomic_created.txt")
		bFile := filepath.Join(scratchDir, "bp_atomic_b.txt")
		_ = os.Remove(createdFile)
		if err := os.WriteFile(bFile, []byte("beta\n"), 0644); err != nil {
			t.Fatalf("setup b: %v", err)
		}
		defer os.Remove(bFile)

		patch := "*** Begin Patch\n*** Add File: bp_atomic_created.txt\n+alpha\n*** Update File: bp_atomic_b.txt\n@@\n wrong\n-beta\n+BETA\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected Begin Patch hunk failure")
		}
		text := getText(t, out)
		if !strings.Contains(text, "rollback: not_needed_no_files_modified") {
			t.Fatalf("expected no-write rollback guidance, got: %s", text)
		}
		if _, err := os.Stat(createdFile); !os.IsNotExist(err) {
			t.Fatalf("Begin Patch planned Add File must not be written after later failure")
		}
		gotB, err := os.ReadFile(bFile)
		if err != nil {
			t.Fatalf("read b after Begin Patch failure: %v", err)
		}
		if string(gotB) != "beta\n" {
			t.Fatalf("Begin Patch target must remain unchanged, got %q", string(gotB))
		}
	})

	// --- Safety: symlink targets must be rejected before snapshot/rollback follows them ---
	t.Run("unified_diff_symlink_target_rejected", func(t *testing.T) {
		outsideDir := t.TempDir()
		outsideFile := filepath.Join(outsideDir, "outside.txt")
		if err := os.WriteFile(outsideFile, []byte("outside\n"), 0644); err != nil {
			t.Fatalf("setup outside: %v", err)
		}
		linkFile := filepath.Join(scratchDir, "atomic_link.txt")
		_ = os.Remove(linkFile)
		if err := os.Symlink(outsideFile, linkFile); err != nil {
			t.Skipf("symlink unavailable on this platform: %v", err)
		}
		defer os.Remove(linkFile)

		patch := "--- a/atomic_link.txt\n+++ b/atomic_link.txt\n@@ -1 +1 @@\n-outside\n+changed\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected symlink patch target to be rejected")
		}
		text := getText(t, out)
		if !strings.Contains(text, "symlink") || !strings.Contains(text, "rollback: not_needed_no_files_modified") {
			t.Fatalf("expected symlink rejection and no-write guidance, got: %s", text)
		}
		gotOutside, err := os.ReadFile(outsideFile)
		if err != nil {
			t.Fatalf("read outside file after rejection: %v", err)
		}
		if string(gotOutside) != "outside\n" {
			t.Fatalf("outside symlink target must remain unchanged, got %q", string(gotOutside))
		}
	})

	// --- Safety: Begin Patch must reject symlink targets during planning, before reads/writes ---
	t.Run("begin_patch_symlink_target_rejected", func(t *testing.T) {
		outsideDir := t.TempDir()
		outsideFile := filepath.Join(outsideDir, "outside_begin.txt")
		if err := os.WriteFile(outsideFile, []byte("outside\n"), 0644); err != nil {
			t.Fatalf("setup outside: %v", err)
		}
		linkFile := filepath.Join(scratchDir, "bp_atomic_link.txt")
		_ = os.Remove(linkFile)
		if err := os.Symlink(outsideFile, linkFile); err != nil {
			t.Skipf("symlink unavailable on this platform: %v", err)
		}
		defer os.Remove(linkFile)

		patch := "*** Begin Patch\n*** Update File: bp_atomic_link.txt\n@@\n-outside\n+changed\n*** End Patch\n"
		out := callApplyPatch(t, patch, scratchDir)
		if isError, _ := out["isError"].(bool); !isError {
			t.Fatalf("Expected Begin Patch symlink target to be rejected")
		}
		text := getText(t, out)
		if !strings.Contains(text, "symlink") || !strings.Contains(text, "rollback: not_needed_no_files_modified") {
			t.Fatalf("expected symlink rejection and no-write guidance, got: %s", text)
		}
		gotOutside, err := os.ReadFile(outsideFile)
		if err != nil {
			t.Fatalf("read outside file after Begin Patch rejection: %v", err)
		}
		if string(gotOutside) != "outside\n" {
			t.Fatalf("outside Begin Patch symlink target must remain unchanged, got %q", string(gotOutside))
		}
	})
}
