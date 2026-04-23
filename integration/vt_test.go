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
	"provider_unavailable": {},
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
	if jars, _ := filepath.Glob(filepath.Join(repoRoot, "java", "build", "libs", "*.jar")); len(jars) > 0 {
		return
	}
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

func TestVT_020_ParentExitAutoClose(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration VT test in short mode")
	}

	repoRoot := findRepoRoot(t)
	ensureJavaJar(t, repoRoot)
	binPath := buildBinary(t, repoRoot)
	sockPath, dataDir, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)

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

	waitForState(t, sockPath, "CLOSED", 4*time.Second)

	transitions := readStateTransitions(t, dataDir)
	t.Logf("VT-020: recorded transitions: %v", transitions)
	joined := strings.Join(transitions, ",")
	if !strings.Contains(joined, "COOLING_DOWN") || !strings.Contains(joined, "CLOSED") {
		t.Fatalf("VT-020: expected COOLING_DOWN and CLOSED transitions after bridge exit, got %v", transitions)
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
