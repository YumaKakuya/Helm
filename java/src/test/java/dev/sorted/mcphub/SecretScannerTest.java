package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretScannerTest {

    // Build test secret prefixes via concatenation to avoid GitHub Push Protection
    // blocking test data as real secrets. These are NOT real keys.
    private static final String STRIPE_LIVE_PREFIX = "sk_" + "live_";
    private static final String GITHUB_PAT_PREFIX = "gh" + "p_";
    private static final String AWS_PREFIX = "AK" + "IA";
    private static final String DUMMY_SUFFIX = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Test
    void scan_jwt() {
        // JWT is not flagged by Push Protection — safe as literal
        String t = "token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        var f = SecretScanner.scan(t);
        assertEquals(1, f.size());
        assertEquals("jwt", f.get(0).patternName());
    }

    @Test
    void scan_awsKey() {
        String t = "AWS access key: " + AWS_PREFIX + "IOSFODNN7EXAMPLE";
        assertTrue(SecretScanner.containsSecret(t));
        assertEquals("aws_access", SecretScanner.scan(t).get(0).patternName());
    }

    @Test
    void scan_stripeLive() {
        assertTrue(SecretScanner.containsSecret(STRIPE_LIVE_PREFIX + DUMMY_SUFFIX));
    }

    @Test
    void scan_githubPat() {
        assertTrue(SecretScanner.containsSecret(GITHUB_PAT_PREFIX + DUMMY_SUFFIX));
    }

    @Test
    void scan_privateKey() {
        assertTrue(SecretScanner.containsSecret("-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAA..."));
    }

    @Test
    void scan_cleanText_noFindings() {
        String t = "This is a normal response with no secrets. File list: a.txt, b.txt.";
        assertTrue(SecretScanner.scan(t).isEmpty());
    }

    @Test
    void scan_nullOrEmpty() {
        assertTrue(SecretScanner.scan(null).isEmpty());
        assertTrue(SecretScanner.scan("").isEmpty());
    }
}
