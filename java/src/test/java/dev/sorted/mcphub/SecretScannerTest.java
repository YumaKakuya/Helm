package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretScannerTest {

    @Test
    void scan_jwt() {
        String t = "token: eyJhbGciOi" + "JIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyIn0.signatureDataHereForJwtExample";
        var f = SecretScanner.scan(t);
        assertEquals(1, f.size());
        assertEquals("jwt", f.get(0).patternName());
    }

    @Test
    void scan_awsKey() {
        String t = "AWS access key: AK" + "IAIOSFODNN7EXAMPLE";
        assertTrue(SecretScanner.containsSecret(t));
        assertEquals("aws_access", SecretScanner.scan(t).get(0).patternName());
    }

    @Test
    void scan_stripeLive() {
        assertTrue(SecretScanner.containsSecret("sk_" + "live_abcdefghijklmnop123456789012345"));
    }

    @Test
    void scan_githubPat() {
        assertTrue(SecretScanner.containsSecret("gh" + "p_abcdefghijklmnopqrstuvwxyz0123456789"));
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
