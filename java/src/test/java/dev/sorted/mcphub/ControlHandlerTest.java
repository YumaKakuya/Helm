package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ControlHandler.
 * Spec: Chapter 2 §2.6, Chapter 3 §3.4, §3.10
 */
class ControlHandlerTest {

    @TempDir
    Path tempDir;

    private ControlHandler handler;
    private StateMachine sm;
    private SessionManager session;
    private DatabaseManager db;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        sm = new StateMachine();
        session = new SessionManager();
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        db.open();
        handler = new ControlHandler(sm, session, db);
    }

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdown();
        session.shutdown();
        if (db != null) db.close();
    }

    @Test
    void healthReturnsOk() throws Exception {
        JsonNode result = handler.handle("mcphub.control.health", null);
        assertEquals("ok", result.get("status").asText());
        assertEquals("CLOSED", result.get("state").asText());
    }

    @Test
    void statusReturnsClosedInitially() throws Exception {
        JsonNode result = handler.handle("mcphub.control.status", null);
        assertEquals("CLOSED", result.get("state").asText());
        assertFalse(result.get("locked_until_unlock").asBoolean());
    }

    @Test
    void armTransitionsToArmed() throws Exception {
        JsonNode result = handler.handle("mcphub.control.arm", null);
        assertEquals("ARMED", result.get("state").asText());
        assertNotNull(result.get("session_id"));
    }

    @Test
    void openTransitionsToOpen() throws Exception {
        handler.handle("mcphub.control.arm", null);
        JsonNode result = handler.handle("mcphub.control.open", null);
        assertEquals("OPEN", result.get("state").asText());
    }

    @Test
    void closeFromOpenTransitionsToClosed() throws Exception {
        handler.handle("mcphub.control.arm", null);
        handler.handle("mcphub.control.open", null);
        JsonNode result = handler.handle("mcphub.control.close", null);
        assertEquals("CLOSED", result.get("state").asText());
    }

    @Test
    void lockFromOpenTransitionsToClosed() throws Exception {
        handler.handle("mcphub.control.arm", null);
        handler.handle("mcphub.control.open", null);
        JsonNode result = handler.handle("mcphub.control.lock",
            mapper.readTree("{\"lock_reason\":\"test\"}"));
        assertEquals("CLOSED", result.get("state").asText());
        assertTrue(result.get("locked_until_unlock").asBoolean());
    }

    @Test
    void lockFromClosedTransitionsToClosed() throws Exception {
        JsonNode result = handler.handle("mcphub.control.lock",
            mapper.readTree("{\"lock_reason\":\"test\"}"));
        assertEquals("CLOSED", result.get("state").asText());
        assertTrue(result.get("locked_until_unlock").asBoolean());
    }

    @Test
    void lockPreventsArm() throws Exception {
        handler.handle("mcphub.control.arm", null);
        handler.handle("mcphub.control.lock", null);
        assertThrows(JsonRpcServer.JsonRpcException.class,
            () -> handler.handle("mcphub.control.arm", null));
    }

    @Test
    void unlockAllowsArmAfterLock() throws Exception {
        handler.handle("mcphub.control.arm", null);
        handler.handle("mcphub.control.lock", null);
        handler.handle("mcphub.control.unlock", null);
        // Should succeed now
        JsonNode result = handler.handle("mcphub.control.arm", null);
        assertEquals("ARMED", result.get("state").asText());
    }

    @Test
    void unknownMethodThrows() {
        assertThrows(JsonRpcServer.JsonRpcException.class,
            () -> handler.handle("mcphub.unknown.method", null));
    }

    @Test
    void sessionLogPopulatedAfterArm() throws Exception {
        handler.handle("mcphub.control.arm", null);
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT * FROM session_log WHERE to_state='ARMED'")) {
            assertTrue(rs.next(), "session_log should have ARMED entry");
        }
    }

    @Test
    void capabilities_withHealthTracker_reportsRealHealth() throws Exception {
        ControlHandler capabilitiesHandler = createCapabilityAwareHandler();

        ProviderHealthTracker tracker = new ProviderHealthTracker();
        capabilitiesHandler.setHealthTracker(tracker);
        tracker.updateGroup("web", "running");
        tracker.updateGroup("edit", "stopped");

        JsonNode r = capabilitiesHandler.handle("mcphub.control.capabilities", null);
        JsonNode caps = r.path("capabilities");
        assertTrue(caps.isArray() && caps.size() > 0, "capabilities array should not be empty");

        boolean foundWebfetch = false;
        boolean foundApplyPatch = false;
        for (JsonNode cap : caps) {
            if ("webfetch".equals(cap.path("display_name").asText())) {
                assertEquals("running", cap.path("provider_health").asText(),
                        "webfetch should report running (its group 'web' was marked running)");
                foundWebfetch = true;
            }
            if ("apply_patch".equals(cap.path("display_name").asText())) {
                assertEquals("stopped", cap.path("provider_health").asText(),
                        "apply_patch should report stopped (its group 'edit' was marked stopped)");
                foundApplyPatch = true;
            }
        }
        assertTrue(foundWebfetch, "webfetch must appear in capabilities");
        assertTrue(foundApplyPatch, "apply_patch must appear in capabilities");
    }

    @Test
    void capabilities_withoutHealthTracker_reportsUnavailable() throws Exception {
        ControlHandler capabilitiesHandler = createCapabilityAwareHandler();

        JsonNode r = capabilitiesHandler.handle("mcphub.control.capabilities", null);
        JsonNode caps = r.path("capabilities");
        assertTrue(caps.isArray() && caps.size() > 0, "capabilities array should not be empty");

        for (JsonNode cap : caps) {
            assertEquals("unavailable", cap.path("provider_health").asText(),
                    "without tracker, provider_health must default to unavailable");
        }
    }

    @Test
    void coolingDown_clearsHealthTracker() throws Exception {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        handler.setHealthTracker(tracker);
        tracker.updateGroup("web", "running");
        assertEquals("running", tracker.getGroupHealth("web"));

        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.CLOSE, "s1");

        assertEquals("unavailable", tracker.getGroupHealth("web"),
                "Tracker must be cleared on COOLING_DOWN transition");
    }

    // -------------------------------------------------------------------------
    // AC-1: add_session_rule
    // -------------------------------------------------------------------------

    @Test
    void addSessionRule_whenArmed_succeeds() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        h.handle("mcphub.control.arm", null);

        ObjectNode params = mapper.createObjectNode();
        params.put("tool_pattern", "webfetch");
        params.put("action", "deny");
        params.put("rule_id", "test-deny-webfetch");
        params.put("priority", 500);

        JsonNode r = h.handle("mcphub.control.add_session_rule", params);
        assertEquals("ok", r.path("status").asText());
        assertEquals("test-deny-webfetch", r.path("rule_id").asText());
        assertEquals("session", r.path("scope").asText());
        assertEquals("ARMED", r.path("state").asText());
    }

    @Test
    void addSessionRule_whenOpen_succeeds() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        h.handle("mcphub.control.arm", null);
        h.handle("mcphub.control.open", null);

        ObjectNode params = mapper.createObjectNode();
        params.put("tool_pattern", "webfetch");
        params.put("action", "hide");

        JsonNode r = h.handle("mcphub.control.add_session_rule", params);
        assertEquals("ok", r.path("status").asText());
        assertEquals("session", r.path("scope").asText());
        assertEquals("OPEN", r.path("state").asText());
    }

    @Test
    void addSessionRule_whenClosed_rejects() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        ObjectNode params = mapper.createObjectNode();
        params.put("tool_pattern", "webfetch");
        params.put("action", "deny");

        JsonRpcServer.JsonRpcException ex = assertThrows(
            JsonRpcServer.JsonRpcException.class,
            () -> h.handle("mcphub.control.add_session_rule", params));
        assertTrue(ex.getMessage().contains("Armed or Open"));
    }

    @Test
    void addSessionRule_invalidAction_rejects() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        h.handle("mcphub.control.arm", null);

        ObjectNode params = mapper.createObjectNode();
        params.put("tool_pattern", "webfetch");
        params.put("action", "block");

        JsonRpcServer.JsonRpcException ex = assertThrows(
            JsonRpcServer.JsonRpcException.class,
            () -> h.handle("mcphub.control.add_session_rule", params));
        assertTrue(ex.getMessage().contains("allow, deny, or hide"));
    }

    @Test
    void addSessionRule_missingToolPattern_rejects() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        h.handle("mcphub.control.arm", null);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "deny");

        JsonRpcServer.JsonRpcException ex = assertThrows(
            JsonRpcServer.JsonRpcException.class,
            () -> h.handle("mcphub.control.add_session_rule", params));
        assertTrue(ex.getMessage().contains("Missing required params"));
    }

    @Test
    void addSessionRule_missingAction_rejects() throws Exception {
        ControlHandler h = createCapabilityAwareHandler();
        h.handle("mcphub.control.arm", null);

        ObjectNode params = mapper.createObjectNode();
        params.put("tool_pattern", "webfetch");

        JsonRpcServer.JsonRpcException ex = assertThrows(
            JsonRpcServer.JsonRpcException.class,
            () -> h.handle("mcphub.control.add_session_rule", params));
        assertTrue(ex.getMessage().contains("Missing required params"));
    }

    @Test
    void sessionRule_purgedOnClose_thenToolAllowedAgain() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        try (InputStream is = ControlHandlerTest.class.getResourceAsStream("/capabilities.yaml")) {
            assertNotNull(is, "capabilities.yaml must be in classpath");
            registry.load(is);
        }
        PolicyEngine policy = new PolicyEngine();
        policy.loadGlobalRules(registry.getPolicyRules());
        BodyBudgetService bodyBudget = new BodyBudgetService(db);
        bodyBudget.setMcphubHostedToolCount(registry.getLoadedCount());
        ControlHandler h = new ControlHandler(sm, session, db, registry, policy, bodyBudget);

        // Arm session
        h.handle("mcphub.control.arm", null);

        // Add session rule to deny webfetch
        ObjectNode ruleParams = mapper.createObjectNode();
        ruleParams.put("tool_pattern", "webfetch");
        ruleParams.put("action", "deny");
        ruleParams.put("rule_id", "session-deny-webfetch");
        JsonNode addResult = h.handle("mcphub.control.add_session_rule", ruleParams);
        assertEquals("ok", addResult.path("status").asText());

        // Verify tool is denied before close
        assertEquals(PolicyEngine.Decision.DENY, policy.evaluate("webfetch").decision());

        // Open, then close (triggers COOLING_DOWN → clearSessionRules)
        h.handle("mcphub.control.open", null);
        h.handle("mcphub.control.close", null);

        // Arm and open a new session
        h.handle("mcphub.control.arm", null);
        h.handle("mcphub.control.open", null);

        // Verify previously-denied tool is now allowed (session rule was purged)
        assertEquals(PolicyEngine.Decision.ALLOW, policy.evaluate("webfetch").decision());
    }

    @Test
    void sessionRule_withExplicitLowPriority_doesNotOverrideGlobalRule() throws Exception {
        // Global rule: deny webfetch at priority 1000
        PolicyEngine policy = new PolicyEngine();
        PolicyRule globalDeny = new PolicyRule();
        globalDeny.ruleId = "global-deny-webfetch";
        globalDeny.toolPattern = "webfetch";
        globalDeny.action = "deny";
        globalDeny.priority = 1000;
        globalDeny.scope = "global";
        policy.loadGlobalRules(java.util.List.of(globalDeny));

        // Session rule: allow webfetch at priority -1 (explicitly low, opt-out of boost)
        PolicyRule sessionAllow = new PolicyRule();
        sessionAllow.ruleId = "session-allow-webfetch";
        sessionAllow.toolPattern = "webfetch";
        sessionAllow.action = "allow";
        sessionAllow.priority = -1;
        sessionAllow.scope = "session";
        policy.addSessionRule(sessionAllow);

        // Global deny should win because session rule has explicit low priority (-1) and is NOT boosted
        PolicyEngine.PolicyResult result = policy.evaluate("webfetch");
        assertEquals(PolicyEngine.Decision.DENY, result.decision(),
                "Global deny at priority 1000 must override session allow at priority -1");
        assertEquals("global-deny-webfetch", result.matchedRuleId());
    }

    // -------------------------------------------------------------------------
    // Bridge attach/detach idle timeout suppression
    // -------------------------------------------------------------------------

    @Test
    void bridgeAttach_incrementsCount() throws Exception {
        JsonNode result = handler.handle("mcphub.control.bridge_attach",
            mapper.readTree("{\"pid\":12345}"));
        assertEquals("ok", result.get("status").asText());
        assertEquals(1, result.get("active_bridges").asInt());

        result = handler.handle("mcphub.control.bridge_attach",
            mapper.readTree("{\"pid\":12346}"));
        assertEquals(2, result.get("active_bridges").asInt());
    }

    @Test
    void bridgeAttach_cancelsIdleTimer() throws Exception {
        StateMachine freshSm = new StateMachine();
        freshSm.transition(StateMachine.Trigger.ARM, "s1");
        freshSm.transition(StateMachine.Trigger.OPEN, "s1");

        SessionManager shortSession = new SessionManager(1, 300);
        shortSession.startSession();
        shortSession.onOpen();

        ControlHandler ch = new ControlHandler(freshSm, shortSession, db);
        ch.handle("mcphub.control.bridge_attach", mapper.readTree("{\"pid\":12345}"));

        Thread.sleep(3000);
        assertEquals(StateMachine.State.OPEN, freshSm.getState(),
            "Session should remain OPEN while bridge is attached");
        assertNotNull(shortSession.getCurrentSessionId());

        ch.shutdown();
        shortSession.shutdown();
    }

    @Test
    void bridgeDetach_lastBridge_restartsIdleTimer() throws Exception {
        StateMachine freshSm = new StateMachine();
        freshSm.transition(StateMachine.Trigger.ARM, "s1");
        freshSm.transition(StateMachine.Trigger.OPEN, "s1");

        SessionManager shortSession = new SessionManager(1, 300);
        shortSession.startSession();
        shortSession.onOpen();

        ControlHandler ch = new ControlHandler(freshSm, shortSession, db);
        ch.handle("mcphub.control.bridge_attach", mapper.readTree("{\"pid\":12345}"));

        JsonNode result = ch.handle("mcphub.control.bridge_detach", mapper.readTree("{\"pid\":12345}"));
        assertEquals("ok", result.get("status").asText());
        assertEquals(0, result.get("active_bridges").asInt());

        assertEquals(StateMachine.State.OPEN, freshSm.getState(),
            "Session should not close immediately after last bridge detaches");

        Thread.sleep(3000);
        assertEquals(StateMachine.State.CLOSED, freshSm.getState(),
            "Session should close after resumed idle timer fires");
        assertNull(shortSession.getCurrentSessionId());

        ch.shutdown();
        shortSession.shutdown();
    }

    @Test
    void bridgeDetach_notLastBridge_keepsIdleSuppressed() throws Exception {
        StateMachine freshSm = new StateMachine();
        freshSm.transition(StateMachine.Trigger.ARM, "s1");
        freshSm.transition(StateMachine.Trigger.OPEN, "s1");

        SessionManager shortSession = new SessionManager(1, 300);
        shortSession.startSession();
        shortSession.onOpen();

        ControlHandler ch = new ControlHandler(freshSm, shortSession, db);
        ch.handle("mcphub.control.bridge_attach", mapper.readTree("{\"pid\":12345}"));
        ch.handle("mcphub.control.bridge_attach", mapper.readTree("{\"pid\":12346}"));
        ch.handle("mcphub.control.bridge_detach", mapper.readTree("{\"pid\":12345}"));

        Thread.sleep(3000);
        assertEquals(StateMachine.State.OPEN, freshSm.getState(),
            "Session should remain OPEN when one bridge detaches but another remains");
        assertNotNull(shortSession.getCurrentSessionId());

        ch.shutdown();
        shortSession.shutdown();
    }

    @Test
    void idleTimeout_doesNotFire_whileBridgeAttached() throws Exception {
        StateMachine freshSm = new StateMachine();
        freshSm.transition(StateMachine.Trigger.ARM, "s1");
        freshSm.transition(StateMachine.Trigger.OPEN, "s1");

        SessionManager shortSession = new SessionManager(1, 300);
        shortSession.startSession();
        shortSession.onOpen();

        ControlHandler ch = new ControlHandler(freshSm, shortSession, db);
        ch.handle("mcphub.control.bridge_attach", mapper.readTree("{\"pid\":12345}"));

        Thread.sleep(3000);
        assertEquals(StateMachine.State.OPEN, freshSm.getState(),
            "Session should remain OPEN while bridge is attached");
        assertNotNull(shortSession.getCurrentSessionId());

        ch.shutdown();
        shortSession.shutdown();
    }

    private ControlHandler createCapabilityAwareHandler() throws Exception {
        CapabilityRegistry registry = new CapabilityRegistry();
        try (InputStream is = ControlHandlerTest.class.getResourceAsStream("/capabilities.yaml")) {
            assertNotNull(is, "capabilities.yaml must be in classpath");
            registry.load(is);
        }

        PolicyEngine policy = new PolicyEngine();
        policy.loadGlobalRules(registry.getPolicyRules());

        BodyBudgetService bodyBudget = new BodyBudgetService(db);
        bodyBudget.setMcphubHostedToolCount(registry.getLoadedCount());

        return new ControlHandler(sm, session, db, registry, policy, bodyBudget);
    }
}
