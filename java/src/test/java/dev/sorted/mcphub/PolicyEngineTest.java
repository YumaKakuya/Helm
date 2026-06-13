package dev.sorted.mcphub;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PolicyEngine. Spec: Chapter 7 §7.3, §7.4, §7.5
 */
class PolicyEngineTest {

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine();
    }

    // --- Default policy ---

    @Test
    void defaultPolicy_allowsAll() {
        // REQ-7.3.6: default = allow * when no rules loaded
        var result = engine.evaluate("webfetch");
        assertEquals(PolicyEngine.Decision.ALLOW, result.decision());
        assertNull(result.matchedRuleId());
    }

    // --- Allow/deny/hide semantics ---

    @Test
    void explicitAllow_returnsAllow() {
        engine.loadGlobalRules(List.of(rule("r1", "webfetch", "allow", 100)));
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("webfetch").decision());
    }

    @Test
    void explicitDeny_returnsDeny() {
        engine.loadGlobalRules(List.of(rule("r1", "webfetch", "deny", 100)));
        var result = engine.evaluate("webfetch");
        assertEquals(PolicyEngine.Decision.DENY, result.decision());
        assertEquals("r1", result.matchedRuleId());
    }

    @Test
    void explicitHide_returnsHide() {
        engine.loadGlobalRules(List.of(rule("r1", "webfetch", "hide", 100)));
        assertEquals(PolicyEngine.Decision.HIDE, engine.evaluate("webfetch").decision());
    }

    // --- Glob matching ---

    @Test
    void globPattern_matchesPrefix() {
        engine.loadGlobalRules(List.of(rule("r1", "web*", "deny", 100)));
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("webfetch").decision());
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("websearch").decision());
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("codesearch").decision());
    }

    @Test
    void wildcardStar_matchesAll() {
        engine.loadGlobalRules(List.of(rule("r1", "*", "deny", 100)));
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("anything").decision());
    }

    // --- Priority resolution (REQ-7.3.8) ---

    @Test
    void higherPriority_wins() {
        // deny at priority 200 beats allow at priority 100
        engine.loadGlobalRules(List.of(
            rule("allow-web", "web*", "allow", 100),
            rule("deny-webfetch", "webfetch", "deny", 200)
        ));
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("webfetch").decision());
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("websearch").decision());
    }

    // --- Tie-breaking (REQ-7.3.9) ---

    @Test
    void samePriority_moreSpecificWins() {
        // exact match beats glob at same priority
        engine.loadGlobalRules(List.of(
            rule("deny-glob", "web*", "deny", 100),
            rule("allow-exact", "webfetch", "allow", 100)
        ));
        // exact name = 2 specificity beats glob = 1
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("webfetch").decision());
    }

    @Test
    void samePriorityAndSpecificity_denyWinsOverAllow() {
        engine.loadGlobalRules(List.of(
            rule("r1", "webfetch", "allow", 100),
            rule("r2", "webfetch", "deny", 100)
        ));
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("webfetch").decision());
    }

    @Test
    void samePriorityAndSpecificity_hideWinsOverDeny() {
        engine.loadGlobalRules(List.of(
            rule("r1", "webfetch", "deny", 100),
            rule("r2", "webfetch", "hide", 100)
        ));
        assertEquals(PolicyEngine.Decision.HIDE, engine.evaluate("webfetch").decision());
    }

    // --- filterForAI (REQ-7.4.1, REQ-4.4.1) ---

    @Test
    void filterForAI_excludesDeniedAndHidden() {
        engine.loadGlobalRules(List.of(
            rule("deny-web", "webfetch", "deny", 100),
            rule("hide-search", "websearch", "hide", 100)
        ));
        List<CapabilityEntry> entries = List.of(
            entry("webfetch", true),
            entry("websearch", true),
            entry("codesearch", true)
        );
        var visible = engine.filterForAI(entries);
        assertEquals(1, visible.size());
        assertEquals("codesearch", visible.get(0).displayName);
    }

    @Test
    void filterForAI_excludesDisabledEntries() {
        List<CapabilityEntry> entries = List.of(
            entry("webfetch", false),  // disabled
            entry("websearch", true)
        );
        var visible = engine.filterForAI(entries);
        assertEquals(1, visible.size());
        assertEquals("websearch", visible.get(0).displayName);
    }

    // --- filterForOperator (shows denied, hides hidden) ---

    @Test
    void filterForOperator_showsDeniedButNotHidden() {
        engine.loadGlobalRules(List.of(
            rule("deny-web", "webfetch", "deny", 100),
            rule("hide-search", "websearch", "hide", 100)
        ));
        List<CapabilityEntry> entries = List.of(
            entry("webfetch", true),
            entry("websearch", true),
            entry("codesearch", true)
        );
        var visible = engine.filterForOperator(entries);
        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(e -> "webfetch".equals(e.displayName)));
        assertTrue(visible.stream().anyMatch(e -> "codesearch".equals(e.displayName)));
        assertFalse(visible.stream().anyMatch(e -> "websearch".equals(e.displayName)));
    }

    // --- Session rules (REQ-7.5.1, REQ-7.5.2, REQ-7.3.11) ---

    @Test
    void sessionRule_overridesGlobal() {
        // Global: allow all. Session: deny webfetch.
        engine.loadGlobalRules(List.of(rule("global-allow", "*", "allow", 1)));
        PolicyRule sessionRule = rule("session-deny", "webfetch", "deny", 1);
        sessionRule.scope = "session";
        engine.addSessionRule(sessionRule);
        assertEquals(PolicyEngine.Decision.DENY, engine.evaluate("webfetch").decision());
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("websearch").decision());
    }

    @Test
    void clearSessionRules_restoresGlobalBehavior() {
        engine.loadGlobalRules(List.of(rule("global-allow", "*", "allow", 1)));
        PolicyRule sessionRule = rule("session-deny", "webfetch", "deny", 1);
        sessionRule.scope = "session";
        engine.addSessionRule(sessionRule);
        engine.clearSessionRules();
        assertEquals(PolicyEngine.Decision.ALLOW, engine.evaluate("webfetch").decision());
    }

    // --- Helpers ---

    private PolicyRule rule(String id, String pattern, String action, int priority) {
        PolicyRule r = new PolicyRule();
        r.ruleId = id;
        r.toolPattern = pattern;
        r.action = action;
        r.priority = priority;
        r.scope = "global";
        return r;
    }

    private CapabilityEntry entry(String displayName, boolean enabled) {
        CapabilityEntry e = new CapabilityEntry();
        e.displayName = displayName;
        e.capabilityId = displayName;
        e.enabled = enabled;
        e.providerId = "builtin-hatch";
        e.accessClass = "safe";
        e.rwBoundary = "read";
        e.contract = new CapabilityContract();
        e.contract.purpose = "Test purpose";
        return e;
    }
}
