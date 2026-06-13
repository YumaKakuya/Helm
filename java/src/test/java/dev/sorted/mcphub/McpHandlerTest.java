package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpHandler. Spec: Ch.4 §4.4, Ch.5 §5.2-5.6, Ch.7 §7.4
 */
class McpHandlerTest {

    @TempDir Path tempDir;
    private McpHandler handler;
    private StateMachine sm;
    private CapabilityRegistry registry;
    private PolicyEngine policy;
    private DatabaseManager db;
    private BodyBudgetService bodyBudget;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        sm = new StateMachine();
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        db.open();
        registry = new CapabilityRegistry();
        try (InputStream is = McpHandlerTest.class.getResourceAsStream("/capabilities.yaml")) {
            assertNotNull(is, "capabilities.yaml must be in classpath");
            registry.load(is);
        }
        policy = new PolicyEngine();
        policy.loadGlobalRules(registry.getPolicyRules());
        bodyBudget = new BodyBudgetService(db);
        bodyBudget.setMcphubHostedToolCount(registry.getLoadedCount());
        handler = new McpHandler(sm, registry, policy, db, bodyBudget);
    }

    @AfterEach
    void tearDown() { db.close(); }

    // --- initialize ---

    @Test
    void initialize_returnsServerInfo() throws Exception {
        JsonNode r = handler.handle("initialize", null);
        assertEquals("mcphub", r.path("serverInfo").path("name").asText());
        assertTrue(r.has("capabilities"));
    }

    @Test
    void initialize_withCustomServerName() throws Exception {
        handler.setServerName("hub");
        JsonNode r = handler.handle("initialize", null);
        assertEquals("hub", r.path("serverInfo").path("name").asText());
    }

    @Test
    void initialize_defaultServerName() throws Exception {
        JsonNode r = handler.handle("initialize", null);
        assertEquals("mcphub", r.path("serverInfo").path("name").asText());
    }

    // --- tools/list ---

    @Test
    void toolsList_whenClosed_returnsSessionOpenOnly() throws Exception {
        // Session is CLOSED — tools/list returns only mcphub.session.open (REQ-7.4.2)
        // so the AI can self-reopen the session
        JsonNode r = handler.handle("tools/list", null);
        assertEquals(1, r.path("tools").size());
        assertEquals("mcphub.session.open", r.path("tools").get(0).path("name").asText());
    }

    @Test
    void toolsList_whenArmed_returnsSessionOpenOnly() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");

        JsonNode r = handler.handle("tools/list", null);
        assertEquals(1, r.path("tools").size());
        assertEquals("mcphub.session.open", r.path("tools").get(0).path("name").asText());
    }

    @Test
    void toolsList_whenCoolingDown_returnsEmpty() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.CLOSE, "s1");

        JsonNode r = handler.handle("tools/list", null);
        assertEquals(0, r.path("tools").size());
    }

    @Test
    void toolsList_whenLocked_returnsEmpty() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.LOCK, "s1");

        JsonNode r = handler.handle("tools/list", null);
        assertEquals(0, r.path("tools").size());
    }

    @Test
    void toolsList_whenOpen_returns11PlusDisambiguation() throws Exception {
        // Arm and open the session
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("edit", "apply_patch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");
        confirmGroup("session", "plan_enter", "plan_exit", "skill", "batch", "mcphub_checkpoint");
        confirmGroup("nexus", "nexus_issue_create", "nexus_issue_list",
                "nexus_issue_close", "nexus_issue_update");

        JsonNode r = handler.handle("tools/list", null);
        JsonNode tools = r.path("tools");
        // 20 registered tools (builtin-hosted: 11 original + 4 nexus + 4 task + 1 checkpoint) + 1 disambiguation + 1 task_context = 22
        assertEquals(22, tools.size(), "Expected 20 capabilities + 2 hub tools (disambiguate + task_context)");

        // Verify disambiguation tool is present
        boolean hasDisambig = false;
        for (JsonNode t : tools) {
            if ("mcphub_disambiguate".equals(t.path("name").asText())) {
                hasDisambig = true;
                break;
            }
        }
        assertTrue(hasDisambig, "mcphub_disambiguate must appear in tools/list when Open");
    }

    @Test
    void toolsList_deniedTool_notVisible() throws Exception {
        // Deny webfetch globally
        PolicyRule deny = new PolicyRule();
        deny.ruleId = "deny-webfetch";
        deny.toolPattern = "webfetch";
        deny.action = "deny";
        deny.priority = 999;
        deny.scope = "global";
        policy.loadGlobalRules(java.util.List.of(deny));

        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("edit", "apply_patch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");
        confirmGroup("session", "plan_enter", "plan_exit", "skill", "batch", "mcphub_checkpoint");
        confirmGroup("nexus", "nexus_issue_create", "nexus_issue_list",
                "nexus_issue_close", "nexus_issue_update");

        JsonNode r = handler.handle("tools/list", null);
        for (JsonNode t : r.path("tools")) {
            assertNotEquals("webfetch", t.path("name").asText(),
                    "Denied tool must not appear in tools/list");
        }
    }

    @Test
    void toolsList_taskContextResearch_hidesNonResearchTools() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("edit", "apply_patch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");
        confirmGroup("session", "plan_enter", "plan_exit", "skill", "batch", "mcphub_checkpoint");

        setTaskContext("research");

        java.util.Set<String> names = toolNames(handler.handle("tools/list", null));
        assertTrue(names.contains("webfetch"));
        assertTrue(names.contains("websearch"));
        assertTrue(names.contains("mcphub_set_task_context"));
        assertFalse(names.contains("list"), "research context must hide project file listing");
        assertFalse(names.contains("apply_patch"), "research context must hide edit tools");
    }

    // --- tools/call ---

    @Test
    void toolsCall_sessionNotOpen_returnsSessionNotOpen() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("session_not_open"), "Error text must contain error code");
        assertTrue(text.contains("mcphub.session.open"), "Error text must mention mcphub.session.open as recovery path");
        assertTrue(text.contains("call_mcphub_session_open"), "Error next_action must be call_mcphub_session_open");
        String cliCommand = System.getenv("MCPHUB_CLI_COMMAND");
        String expectedCliOpen = (cliCommand == null || cliCommand.isBlank() ? "mcphub" : cliCommand) + " open";
        assertTrue(text.contains("cli_recovery_command: " + expectedCliOpen),
                "Error text must include exact CLI recovery command for clients that cannot call mcphub.session.open");
    }

    @Test
    void toolsCall_unknownTool_returnsToolNotFound() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("edit", "apply_patch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");
        confirmGroup("session", "plan_enter", "plan_exit", "skill", "batch", "mcphub_checkpoint");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "nonexistent_tool_xyz");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("tool_not_found"), "Error text must contain error code");
        // REQ-5.4.2: available_tools included in error text
        assertTrue(text.contains("available_tools"), "Error text must list available tools");
        JsonNode gap = r.path("capability_gap");
        assertEquals("capability_not_registered", gap.path("gap_type").asText());
        assertFalse(gap.path("explanation").asText().isBlank());
        assertEquals("capability_registry_governance", gap.path("premium_feature_id").asText());
    }

    @Test
    void toolsCall_deniedTool_returnsToolDenied() throws Exception {
        PolicyRule deny = new PolicyRule();
        deny.ruleId = "deny-webfetch";
        deny.toolPattern = "webfetch";
        deny.action = "deny";
        deny.priority = 999;
        deny.scope = "global";
        policy.loadGlobalRules(java.util.List.of(deny));

        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("tool_denied"), "Error text must contain error code");
        // REQ-5.6.4: MUST NOT suggest retry for policy denial
        assertFalse(text.contains("next_action: retry"), "MUST NOT suggest retry for policy denial");
        JsonNode gap = r.path("capability_gap");
        assertEquals("policy_restriction", gap.path("gap_type").asText());
        assertFalse(gap.path("explanation").asText().isBlank());
        assertEquals("policy_governance", gap.path("premium_feature_id").asText());
    }

    @Test
    void toolsCall_hiddenByTaskContext_returnsActionableToolNotFound() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");

        setTaskContext("research");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "list");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("tool_not_found"), "Hidden tools must still appear as not found");
        assertTrue(text.contains("hidden by task context 'research'"));
        assertTrue(text.contains("mcphub_set_task_context"));
        assertTrue(text.contains("{\"context\":\"all\"}"));
        assertTrue(text.contains("next_action: set_task_context_all"));
        assertTrue(text.contains("available_tools"));

        JsonNode gap = r.path("capability_gap");
        assertEquals("task_context_filter", gap.path("gap_type").asText());
        assertEquals("task_context_filtering", gap.path("premium_feature_id").asText());
        assertTrue(gap.path("operator_action").asText().contains("mcphub_set_task_context"));
    }

    @Test
    void toolsCall_hiddenByGenericPolicy_keepsGenericToolNotFound() throws Exception {
        PolicyRule hide = new PolicyRule();
        hide.ruleId = "hide-webfetch";
        hide.toolPattern = "webfetch";
        hide.action = "hide";
        hide.priority = 999;
        hide.scope = "global";
        policy.loadGlobalRules(java.util.List.of(hide));

        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("tool_not_found"));
        assertFalse(text.contains("hidden by task context"));
        assertFalse(text.contains("set_task_context_all"));
        assertFalse(r.has("capability_gap"));
    }

    @Test
    void toolsCall_registeredTool_withoutProviderManager_returnsUnavailable() throws Exception {
        // AMD-MCPHUB-001: without a ProviderManager wired, tools/call returns provider_unreachable
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        ObjectNode args = mapper.createObjectNode();
        args.put("url", "https://example.com");
        params.set("arguments", args);

        JsonNode r = handler.handle("tools/call", params);
        // Without providerManager, dispatch returns provider_unreachable failure response
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("provider_unreachable"), "Error text must contain error code");
        JsonNode gap = r.path("capability_gap");
        assertEquals("provider_not_running", gap.path("gap_type").asText());
        assertFalse(gap.path("explanation").asText().isBlank());
        assertEquals("provider_lifecycle_governance", gap.path("premium_feature_id").asText());
    }

    @Test
    void toolsCall_disambiguate_returnsRecommendation() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");
        confirmGroup("edit", "apply_patch");
        confirmGroup("project", "todowrite", "list", "codesearch", "lsp",
                "task_create", "task_list", "task_update", "task_delete");
        confirmGroup("session", "plan_enter", "plan_exit", "skill", "batch", "mcphub_checkpoint");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "mcphub_disambiguate");
        ObjectNode args = mapper.createObjectNode();
        args.put("task_description", "search the web for information about Java");
        params.set("arguments", args);

        JsonNode r = handler.handle("tools/call", params);
        // Should return content array with disambiguation result
        assertTrue(r.has("content"), "Disambiguation must return 'content' field");
    }

    // --- Route logging (IS-05) ---

    @Test
    void toolsCall_logsRouteEntry() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("web", "webfetch", "websearch");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        params.set("arguments", mapper.createObjectNode());

        handler.handle("tools/call", params);

        // Give virtual thread time to write
        Thread.sleep(100);

        // Query DB to confirm route log was written
        var conn = db.getConnection();
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM route_log WHERE tool_name='webfetch'")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1, "route_log must contain at least one entry for webfetch");
        }
    }

    @Test
    void adapterRegistration_confirmsEntries() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");

        JsonNode before = handler.handle("tools/list", null);
        assertEquals(2, before.path("tools").size(),
                "Before adapter registration, only hub tools (disambiguate + task_context) are listed");

        confirmGroup("web", "webfetch", "websearch");

        JsonNode after = handler.handle("tools/list", null);
        assertEquals(4, after.path("tools").size(),
                "After web adapter confirms, 2 tools + 2 hub tools");
    }

    @Test
    void toolsCall_pendingTool_returnsProviderUnavailable() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "webfetch");
        params.set("arguments", mapper.createObjectNode());

        JsonNode r = handler.handle("tools/call", params);
        assertTrue(r.path("isError").asBoolean(), "isError must be true");
        String text = r.path("content").get(0).path("text").asText();
        assertTrue(text.contains("provider_unreachable"), "Error text must contain error code");
    }

    // -------------------------------------------------------------------------
    // S4-06: provider_health_update dispatch
    // -------------------------------------------------------------------------

    @Test
    void providerHealthUpdate_validParams_returnsOk() throws Exception {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        handler.setHealthTracker(tracker);

        ObjectNode params = mapper.createObjectNode();
        params.put("group_id", "web");
        params.put("status", "running");
        JsonNode r = handler.handle("mcphub.internal.provider_health_update", params);
        assertEquals("ok", r.path("status").asText());
        assertEquals("running", tracker.getGroupHealth("web"));
    }

    @Test
    void providerHealthUpdate_missingGroupId_returnsError() throws Exception {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        handler.setHealthTracker(tracker);

        ObjectNode params = mapper.createObjectNode();
        params.put("status", "running");
        // missing group_id
        JsonNode r = handler.handle("mcphub.internal.provider_health_update", params);
        assertEquals("error", r.path("status").asText());
    }

    @Test
    void providerHealthUpdate_missingStatus_returnsError() throws Exception {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        handler.setHealthTracker(tracker);

        ObjectNode params = mapper.createObjectNode();
        params.put("group_id", "web");
        // missing status
        JsonNode r = handler.handle("mcphub.internal.provider_health_update", params);
        assertEquals("error", r.path("status").asText());
    }

    @Test
    void providerHealthUpdate_withoutTracker_doesNotCrash() throws Exception {
        // Backward compat: if no tracker is set, handler still responds
        handler.setHealthTracker(null);
        ObjectNode params = mapper.createObjectNode();
        params.put("group_id", "web");
        params.put("status", "running");
        // Should not throw
        JsonNode r = handler.handle("mcphub.internal.provider_health_update", params);
        // Status field may be "ok" or absent; either way no exception
        assertNotNull(r);
    }

    private void confirmGroup(String groupId, String... tools) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("group_id", groupId);
        ArrayNode arr = mapper.createArrayNode();
        for (String t : tools) {
            arr.add(t);
        }
        params.set("tools", arr);
        handler.handle("mcphub.internal.adapter_registration", params);
    }

    private void setTaskContext(String context) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "mcphub_set_task_context");
        ObjectNode args = mapper.createObjectNode();
        args.put("context", context);
        params.set("arguments", args);
        handler.handle("tools/call", params);
    }

    private java.util.Set<String> toolNames(JsonNode toolsListResponse) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode tool : toolsListResponse.path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    // -------------------------------------------------------------------------
    // REQ-3.7.3: tools/call MUST reset the session idle timer
    // -------------------------------------------------------------------------

    @Test
    void toolsCall_whenOpen_resetsIdleTimer() throws Exception {
        // Arrange: session Open, SessionManager wired, a known baseline activity instant
        SessionManager sm2 = new SessionManager();
        handler.setSessionManager(sm2);
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm2.startSession(); // initializes lastActivity
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm2.onOpen();
        java.time.Instant before = sm2.getLastActivity();
        assertNotNull(before, "lastActivity should be initialized after startSession");

        // Sleep just long enough for the nano-clock to advance
        Thread.sleep(5);

        // Need a confirmed tool so tools/call is not rejected as tool_not_found
        confirmGroup("project", "list");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "list");
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "/tmp");
        params.set("arguments", args);

        handler.handle("tools/call", params);

        java.time.Instant after = sm2.getLastActivity();
        assertNotNull(after, "lastActivity must be set after tools/call");
        assertTrue(after.isAfter(before),
                "tools/call MUST advance lastActivity (REQ-3.7.3). before=" + before + " after=" + after);
    }

    @Test
    void toolsCall_withoutSessionManager_doesNotCrash() throws Exception {
        // Backward compat: if no SessionManager is wired, tools/call still works
        handler.setSessionManager(null);
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        confirmGroup("project", "list");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "list");
        params.set("arguments", mapper.createObjectNode());

        // Should not throw NPE
        JsonNode r = handler.handle("tools/call", params);
        assertNotNull(r);
    }
}
