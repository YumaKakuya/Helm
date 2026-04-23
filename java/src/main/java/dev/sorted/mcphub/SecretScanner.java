package dev.sorted.mcphub;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans text for known secret patterns. REQ-10.3.6.
 * Used in VT-025 to verify MCPHUB response payloads do not leak raw secrets.
 *
 * Patterns covered:
 * - JWT tokens: eyJ... format
 * - AWS access keys: AKIA[0-9A-Z]{16}
 * - Stripe keys: sk_live_, sk_test_
 * - GitHub tokens: ghp_, gho_, ghs_
 * - Slack tokens: xoxb-, xoxa-, xoxp-
 * - Google OAuth refresh tokens: 1//
 * - Private key blocks: -----BEGIN ... PRIVATE KEY-----
 */
public class SecretScanner {

    /** Named pattern definitions used by the scanner. */
    public static final List<NamedPattern> PATTERNS = List.of(
        new NamedPattern("jwt", Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
        new NamedPattern("aws_access", Pattern.compile("AKIA[0-9A-Z]{16}")),
        new NamedPattern("stripe_live", Pattern.compile("sk_live_[A-Za-z0-9]{20,}")),
        new NamedPattern("stripe_test", Pattern.compile("sk_test_[A-Za-z0-9]{20,}")),
        new NamedPattern("github_pat", Pattern.compile("ghp_[A-Za-z0-9]{30,}")),
        new NamedPattern("github_oauth", Pattern.compile("gho_[A-Za-z0-9]{30,}")),
        new NamedPattern("github_server", Pattern.compile("ghs_[A-Za-z0-9]{30,}")),
        new NamedPattern("slack_bot", Pattern.compile("xox[bpas]-[A-Za-z0-9-]{10,}")),
        new NamedPattern("google_rt", Pattern.compile("1//[A-Za-z0-9_-]{40,}")),
        new NamedPattern("pem_private", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"))
    );

    public record NamedPattern(String name, Pattern regex) {}

    public record Finding(String patternName, String match) {}

    /**
     * Scan the given text and return all matched secret patterns.
     * Returns an empty list if no pattern matches.
     */
    public static List<Finding> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new java.util.ArrayList<>();
        for (NamedPattern p : PATTERNS) {
            var matcher = p.regex.matcher(text);
            while (matcher.find()) {
                findings.add(new Finding(p.name, matcher.group()));
            }
        }
        return findings;
    }

    /** Returns true if any secret pattern matches the given text. */
    public static boolean containsSecret(String text) {
        return !scan(text).isEmpty();
    }
}
