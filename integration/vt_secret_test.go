package integration

import (
	"regexp"
	"strings"
	"testing"
)

// VT-025: Route a call to a secret-adjacent provider (Coffer) and verify
// the response payload does not contain raw secret values.
// REQ-10.3.6: MUST use pattern-matching scan on response payload.
func TestVT_025_NoSecretInResponsePayload(t *testing.T) {
	if testing.Short() {
		t.Skip()
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	sockPath, _, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	params := map[string]interface{}{
		"name":      "coffer_list_projects",
		"arguments": map[string]interface{}{},
	}
	result, rpcErr := call(t, sockPath, "tools/call", params)
	if rpcErr != nil {
		t.Skipf("VT-025: coffer not reachable (vault may be locked): %v", rpcErr.Message)
	}

	payload := string(result)
	t.Logf("VT-025 payload (first 500 chars): %.500s", payload)

	secretPatterns := []struct {
		name string
		re   *regexp.Regexp
	}{
		{"jwt", regexp.MustCompile(`eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}`)},
		{"aws_access", regexp.MustCompile(`AKIA[0-9A-Z]{16}`)},
		{"stripe_live", regexp.MustCompile(`sk_live_[A-Za-z0-9]{20,}`)},
		{"github_pat", regexp.MustCompile(`ghp_[A-Za-z0-9]{30,}`)},
		{"pem_private", regexp.MustCompile(`-----BEGIN [A-Z ]*PRIVATE KEY-----`)},
		{"slack_token", regexp.MustCompile(`xox[bpas]-[A-Za-z0-9-]{10,}`)},
	}

	for _, sp := range secretPatterns {
		if match := sp.re.FindString(payload); match != "" {
			shown := match
			if len(shown) > 20 {
				shown = shown[:20]
			}
			t.Errorf("VT-025 FAIL: found secret pattern '%s' in payload: %s", sp.name, shown)
		}
	}
}

// VT-026 is a manual review task; automated check here confirms schema metadata
// does not declare any field typed/named for secrets.
func TestVT_026_NoSecretFieldInCapabilitiesSchema(t *testing.T) {
	if testing.Short() {
		t.Skip()
	}
	repoRoot := findRepoRoot(t)
	binPath := buildBinary(t, repoRoot)
	sockPath, _, cleanup := startDaemon(t, binPath, repoRoot)
	defer cleanup()

	armAndOpen(t, sockPath)
	defer closeSession(t, sockPath)

	result, rpcErr := call(t, sockPath, "mcphub.control.capabilities", nil)
	if rpcErr != nil {
		t.Fatalf("VT-026: capabilities call failed: %d %s", rpcErr.Code, rpcErr.Message)
	}
	payload := string(result)

	forbidden := []string{
		`"raw_secret"`,
		`"plaintext_password"`,
		`"api_key_value"`,
		`"token_value"`,
		`"secret_value"`,
	}
	for _, f := range forbidden {
		if strings.Contains(payload, f) {
			t.Errorf("VT-026: capability schema declares forbidden field: %s", f)
		}
	}
}
