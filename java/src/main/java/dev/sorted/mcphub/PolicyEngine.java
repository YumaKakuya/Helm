package dev.sorted.mcphub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Allow/deny/hide policy evaluation. Spec: Chapter 7 §7.3 and §7.4
 *
 * REQ-7.2.1: Policy governs tool-level accessibility (per-tool allow/deny).
 * REQ-7.2.2: Policy decisions in Java authority core.
 * REQ-7.3.6: Default policy = allow * (all tools allowed when no rules exist).
 * REQ-7.3.8: Highest priority rule wins on conflict.
 * REQ-7.3.9: On same priority — more specific pattern wins; deny > allow; hide > deny.
 */
public class PolicyEngine {
    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    public enum Decision { ALLOW, DENY, HIDE }

    /** Immutable snapshot of a policy evaluation. */
    public record PolicyResult(Decision decision, String matchedRuleId) {}

    private final List<PolicyRule> globalRules = new ArrayList<>();
    private final List<PolicyRule> sessionRules = new ArrayList<>();

    /** Load global rules (from registry config). Replaces any previously loaded global rules. */
    public void loadGlobalRules(List<PolicyRule> rules) {
        globalRules.clear();
        if (rules != null) {
            for (PolicyRule r : rules) {
                if ("global".equals(r.scope) || r.scope == null) {
                    globalRules.add(r);
                }
            }
        }
        log.info("PolicyEngine: loaded {} global rule(s)", globalRules.size());
    }

    /** Add a session-scoped rule. REQ-7.5.1 */
    public void addSessionRule(PolicyRule rule) {
        rule.scope = "session";
        sessionRules.add(rule);
    }

    /** Clear all session-scoped rules. Call on session close. REQ-7.3.11 */
    public void clearSessionRules() {
        int cleared = sessionRules.size();
        sessionRules.clear();
        if (cleared > 0) log.info("PolicyEngine: cleared {} session rule(s)", cleared);
    }

    /**
     * Evaluate the effective policy decision for a single tool name.
     * REQ-7.3.8: highest priority wins.
     * REQ-7.3.9: on tie — more specific wins; deny > allow; hide > deny.
     *
     * Default when no rules match: ALLOW (REQ-7.3.6).
     */
    public PolicyResult evaluate(String toolName) {
        if (toolName == null) return new PolicyResult(Decision.DENY, null);

        // Session rules have effective higher priority than global (REQ-7.5.2)
        // We model this by adding session rule priority + 10000 offset internally
        List<PolicyRule> allRules = new ArrayList<>(sessionRules.size() + globalRules.size());
        // Session rules: boost priority by large offset to model REQ-7.5.2
        for (PolicyRule r : sessionRules) {
            PolicyRule boosted = new PolicyRule();
            boosted.ruleId = r.ruleId;
            boosted.toolPattern = r.toolPattern;
            boosted.action = r.action;
            // REQ-7.5.2: session rules have higher default priority than global,
            // unless operator explicitly sets negative priority (opt-out of boost)
            boosted.priority = r.priority >= 0 ? r.priority + 10000 : r.priority;
            boosted.scope = "session";
            allRules.add(boosted);
        }
        allRules.addAll(globalRules);

        // Filter to matching rules only
        List<PolicyRule> matching = allRules.stream()
            .filter(r -> matches(r.toolPattern, toolName))
            .collect(Collectors.toList());

        if (matching.isEmpty()) {
            // REQ-7.3.6: default allow
            return new PolicyResult(Decision.ALLOW, null);
        }

        // Find winning rule per REQ-7.3.8 / REQ-7.3.9
        PolicyRule winner = matching.stream()
            .max(Comparator.comparingInt((PolicyRule r) -> r.priority)
                .thenComparingInt(r -> specificity(r.toolPattern, toolName))
                .thenComparingInt(r -> actionRank(r.action)))
            .orElse(null);

        if (winner == null) return new PolicyResult(Decision.ALLOW, null);

        Decision d = switch (winner.action) {
            case "hide" -> Decision.HIDE;
            case "deny" -> Decision.DENY;
            default     -> Decision.ALLOW;
        };
        return new PolicyResult(d, winner.ruleId);
    }

    /**
     * Filter a list of entries for what the AI can see in tools/list.
     * REQ-7.4.1: returns only tools that are ALLOW (not DENY, not HIDE).
     * REQ-4.4.1: also filters by enabled=true.
     */
    public List<CapabilityEntry> filterForAI(List<CapabilityEntry> entries) {
        return entries.stream()
            .filter(e -> e.enabled)
            .filter(e -> evaluate(e.displayName).decision() == Decision.ALLOW)
            .collect(Collectors.toList());
    }

    /**
     * Filter for operator-facing control.capabilities.
     * REQ-7.3.3: denied tools remain visible to operator (not AI).
     * Hidden tools: omit from operator view too (REQ-7.3.4: hide = treat as absent for AI).
     * For operator view we show everything except HIDE.
     */
    public List<CapabilityEntry> filterForOperator(List<CapabilityEntry> entries) {
        return entries.stream()
            .filter(e -> evaluate(e.displayName).decision() != Decision.HIDE)
            .collect(Collectors.toList());
    }

    /** True if this pattern matches the tool name. Supports exact and glob (*). */
    private boolean matches(String pattern, String toolName) {
        if (pattern == null) return false;
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return toolName.startsWith(prefix);
        }
        return pattern.equals(toolName);
    }

    /**
     * Specificity: higher = more specific.
     * Exact name = 2, prefix glob = 1, wildcard * = 0
     */
    private int specificity(String pattern, String toolName) {
        if (pattern.equals(toolName)) return 2;
        if ("*".equals(pattern)) return 0;
        return 1; // prefix glob
    }

    /**
     * Action rank for tie-breaking. REQ-7.3.9: hide > deny > allow.
     */
    private int actionRank(String action) {
        return switch (action) {
            case "hide" -> 2;
            case "deny" -> 1;
            default     -> 0;
        };
    }
}
