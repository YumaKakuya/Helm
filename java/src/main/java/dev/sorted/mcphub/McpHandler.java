package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP protocol handler: tools/list, tools/call, initialize, mcphub.disambiguate.
 *
 * Spec references:
 *   REQ-2.3.1: stdio bridge MCP server (we handle the Java side)
 *   REQ-2.3.3: bridge MUST NOT route/policy — Java does it here
 *   REQ-4.4.1: tools/list filtered by enabled + policy + provider health
 *   REQ-4.4.2: each tool entry: name, description, inputSchema
 *   REQ-5.2.1: routing in Java authority core (AXIOM-7)
 *   REQ-5.2.7: every tool call produces route log entry
 *   REQ-5.3.1: disambiguation exposed as MCP tool
 *   REQ-5.6.1: structured failure response on every failed dispatch
 *   REQ-7.2.2: policy decisions in Java
 *   REQ-7.4.1: tools/list only when OPEN, enabled, and ALLOW policy
 */
public class McpHandler implements JsonRpcServer.MethodHandler {
    private static final Logger log = LoggerFactory.getLogger(McpHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DISAMBIGUATION_TOOL = "mcphub_disambiguate";
    private static final String TASK_CONTEXT_TOOL = "mcphub_set_task_context";
    private static final String SESSION_OPEN_TOOL = "mcphub.session.open";
    private static final String CLI_COMMAND_ENV = "MCPHUB_CLI_COMMAND";
    private static final String CHECKPOINT_TOOL = "mcphub_checkpoint";
    private static final String SERVER_VERSION = "0.2.0-alpha";

    private final StateMachine stateMachine;
    private final CapabilityRegistry registry;
    private final PolicyEngine policy;
    private final DatabaseManager db;
    private final BodyBudgetService bodyBudget;
    private ProviderHealthTracker healthTracker;
    private SessionManager sessionManager;  // nullable; required for REQ-3.7.3 idle reset
    private ProviderManager providerManager; // AMD-MCPHUB-001: Java-native provider dispatch
    private final ResultCache resultCache = new ResultCache(); // OS-12: session-scoped read-only cache
    private final TaskContextFilter taskContextFilter = new TaskContextFilter(); // OS-14: task-context filtering
    private String serverName = "mcphub";
    private String checkpointDir; // MCPHUB_DATA_DIR for mcphub_checkpoint persistence

    public McpHandler(StateMachine stateMachine, CapabilityRegistry registry,
                      PolicyEngine policy, DatabaseManager db, BodyBudgetService bodyBudget) {
        this.stateMachine = stateMachine;
        this.registry = registry;
        this.policy = policy;
        this.db = db;
        this.bodyBudget = bodyBudget;
        this.healthTracker = null;
        this.sessionManager = null;
        this.checkpointDir = System.getenv("MCPHUB_DATA_DIR");
        if (this.checkpointDir == null || this.checkpointDir.isBlank()) {
            this.checkpointDir = System.getProperty("user.home") + "/.local/share/mcphub";
        }
    }

    /** Wire checkpoint directory (used by tests to override). */
    public void setCheckpointDir(String checkpointDir) {
        this.checkpointDir = checkpointDir;
    }

    /** Optional wiring for REQ-4.7.2 runtime provider health updates. */
    public void setHealthTracker(ProviderHealthTracker healthTracker) {
        this.healthTracker = healthTracker;
    }

    /** Wire SessionManager so tools/call can reset the idle timer (REQ-3.7.3). */
    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** AMD-MCPHUB-001: Wire ProviderManager for direct provider dispatch. */
    public void setProviderManager(ProviderManager providerManager) {
        this.providerManager = providerManager;
    }

    /** OS-12: Access the result cache (for session close cleanup). */
    public ResultCache getResultCache() {
        return resultCache;
    }

    /** OS-14: Access the task context filter (for session close cleanup). */
    public TaskContextFilter getTaskContextFilter() {
        return taskContextFilter;
    }

    /** Optional override for MCP serverInfo.name. Defaults to "mcphub". */
    public void setServerName(String name) {
        if (name != null && !name.isBlank()) {
            this.serverName = name;
        }
    }

    @Override
    public JsonNode handle(String method, JsonNode params)
            throws JsonRpcServer.JsonRpcException {
        return switch (method) {
            case "initialize"                -> handleInitialize();
            case "tools/list"               -> handleToolsList();
            case "tools/call"               -> handleToolsCall(params);
            case "mcphub.internal.adapter_registration" -> handleAdapterRegistration(params);
            case "mcphub.internal.provider_health_update" -> handleProviderHealthUpdate(params);
            case "notifications/initialized" -> mapper.createObjectNode(); // no-op ack
            default -> throw new JsonRpcServer.JsonRpcException(
                JsonRpcServer.ERR_NOT_FOUND, "Unknown method: " + method);
        };
    }

    // -------------------------------------------------------------------------
    // initialize — MCP handshake (REQ-2.3.1)
    // -------------------------------------------------------------------------

    private JsonNode handleInitialize() {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", "2024-11-05");
        ObjectNode caps = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode());
        r.set("capabilities", caps);
        ObjectNode info = mapper.createObjectNode();
        info.put("name", serverName);
        info.put("version", SERVER_VERSION);
        r.set("serverInfo", info);
        return r;
    }

    // -------------------------------------------------------------------------
    // tools/list — REQ-4.4.1, REQ-7.4.1, REQ-7.4.2
    // -------------------------------------------------------------------------

    /**
     * Returns only tools that are:
     *   (a) session is Open (REQ-7.4.2)
     *   (b) enabled in registry (REQ-4.4.1)
     *   (c) not denied or hidden by policy (REQ-4.4.1, REQ-7.4.1)
     *   Plus the disambiguation tool (REQ-5.3.1) if session is Open.
     */
    private JsonNode handleToolsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode tools = mapper.createArrayNode();

        StateMachine.State currentState = stateMachine.getState();
        boolean isOpen = currentState == StateMachine.State.OPEN;
        if (!isOpen) {
            // Approved amendment: CLOSED/ARMED may expose only mcphub.session.open,
            // except when locked_until_unlock is active.
            if (!stateMachine.isLockedUntilUnlock()
                    && (currentState == StateMachine.State.CLOSED || currentState == StateMachine.State.ARMED)) {
                tools.add(buildSessionOpenTool());
            }
            r.set("tools", tools);
            return r;
        }

        // Filtered capabilities (confirmed + policy ALLOW)
        List<CapabilityEntry> visible = policy.filterForAI(registry.getConfirmed());
        for (CapabilityEntry entry : visible) {
            tools.add(toMcpToolEntry(entry));
        }

        // Add disambiguation tool (REQ-5.3.1, AXIOM-4 trade-off accepted in Spec)
        tools.add(buildDisambiguationTool());

        // OS-14: Add task-context filtering tool
        tools.add(buildTaskContextTool());

        r.set("tools", tools);

        // Track MCP surface size (REQ-4.4.4)
        int surfaceBytes = r.toString().getBytes().length;
        if (surfaceBytes > 20 * 1024) { // REQ-4.4.5: warn if > 20 KB
            log.warn("tools/list response exceeds 20 KB ({} bytes)", surfaceBytes);
        }

        return r;
    }

    /** Convert CapabilityEntry to MCP tools/list entry shape. REQ-4.4.2 */
    private ObjectNode toMcpToolEntry(CapabilityEntry entry) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", entry.displayName);

        // REQ-4.9.1: description from contract.purpose + safety hints
        String desc = entry.contract != null && entry.contract.purpose != null
                ? entry.contract.purpose : entry.displayName;
        if ("restricted".equals(entry.accessClass)) {
            desc = desc + " [modifies external state]"; // REQ-4.9.2
        }
        if ("read".equals(entry.rwBoundary) && !desc.contains("read-only")) {
            desc = desc + " (read-only)"; // REQ-4.9.3
        }
        tool.put("description", desc);

        // inputSchema from registry entry
        if (entry.schema != null) {
            tool.set("inputSchema", mapper.valueToTree(entry.schema));
        } else {
            ObjectNode emptySchema = mapper.createObjectNode();
            emptySchema.put("type", "object");
            tool.set("inputSchema", emptySchema);
        }
        return tool;
    }

    /** Build the task-context MCP tool entry. OS-14 */
    private ObjectNode buildTaskContextTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", TASK_CONTEXT_TOOL);
        tool.put("description",
            "Set the current task context to filter visible tools. " +
            "Contexts: 'coding' (code editing tools), 'research' (web tools), " +
            "'planning' (session/planning tools), 'all' (no filtering, default).");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode contextProp = mapper.createObjectNode();
        contextProp.put("type", "string");
        contextProp.put("description", "Task context: coding, research, planning, or all");
        ArrayNode enumValues = mapper.createArrayNode();
        enumValues.add("coding"); enumValues.add("research");
        enumValues.add("planning"); enumValues.add("all");
        contextProp.set("enum", enumValues);
        props.set("context", contextProp);
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("context");
        schema.set("required", required);
        tool.set("inputSchema", schema);
        return tool;
    }

    /** Build the mcphub.session.open MCP tool entry (visible in all states). */
    private ObjectNode buildSessionOpenTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", SESSION_OPEN_TOOL);
        tool.put("description", "Open the MCPHUB session to make all tools available. " +
                "Call this when session is CLOSED or ARMED. If the client cannot call this MCP tool, run: " +
                cliOpenCommand());
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        tool.set("inputSchema", schema);
        return tool;
    }

    /** Handle mcphub.session.open: ARM + OPEN the session and start providers. */
    private JsonNode handleSessionOpen(long startMs, int requestSizeBytes, String intentAnnotation) {
        try {
            StateMachine.State current = stateMachine.getState();
            String sessionId = sessionManager != null ? sessionManager.getCurrentSessionId() : null;

            if (current == StateMachine.State.OPEN) {
                // Idempotent: already open
                logRoute(sessionId, SESSION_OPEN_TOOL, "mcphub-internal", "builtin_hosted",
                        "allowed", null, System.currentTimeMillis() - startMs,
                        requestSizeBytes, 0, intentAnnotation, null);
                ObjectNode resp = mapper.createObjectNode();
                resp.set("content", wrapTextContent("{\"state\":\"OPEN\",\"message\":\"Session already open.\"}"));
                return resp;
            }

            if (current == StateMachine.State.CLOSED) {
                sessionId = sessionManager != null ? sessionManager.startSession() : "mcp-session";
                stateMachine.transition(StateMachine.Trigger.ARM, sessionId);
            } else if (current == StateMachine.State.ARMED) {
                sessionId = sessionManager != null ? sessionManager.getCurrentSessionId() : "mcp-session";
            } else {
                // COOLING_DOWN or LOCKED
                return failureResponse("session_not_open",
                        "Cannot open session in state: " + current.name() + ". Wait and retry.",
                        "wait_session", null, null);
            }

            stateMachine.transition(StateMachine.Trigger.OPEN, sessionId);
            if (sessionManager != null) sessionManager.onOpen();

            logRoute(sessionId, SESSION_OPEN_TOOL, "mcphub-internal", "builtin_hosted",
                    "allowed", null, System.currentTimeMillis() - startMs,
                    requestSizeBytes, 0, intentAnnotation, null);

            ObjectNode resp = mapper.createObjectNode();
            resp.put("mcphub_providers", "start");
            resp.set("content", wrapTextContent(
                    "{\"state\":\"OPEN\",\"session_id\":\"" + sessionId + "\",\"message\":\"Session opened. All tools are now available.\"}"));
            return resp;

        } catch (StateMachine.TransitionException e) {
            return failureResponse("session_not_open",
                    "Failed to open session: " + e.getMessage(),
                    "wait_session", null, null);
        }
    }

    /** Build the disambiguation MCP tool entry. REQ-5.3.1 */
    private ObjectNode buildDisambiguationTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", DISAMBIGUATION_TOOL);
        tool.put("description",
            "Query MCPHUB to determine which tool is most appropriate for a given task. " +
            "Use before a tool call when the right tool is ambiguous.");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode taskDesc = mapper.createObjectNode();
        taskDesc.put("type", "string");
        taskDesc.put("description", "Natural-language description of what you want to accomplish");
        props.set("task_description", taskDesc);
        ObjectNode candidateTools = mapper.createObjectNode();
        candidateTools.put("type", "array");
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        candidateTools.set("items", items);
        candidateTools.put("description", "Optional: restrict to these tool names");
        props.set("candidate_tools", candidateTools);
        schema.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("task_description");
        schema.set("required", required);
        tool.set("inputSchema", schema);
        return tool;
    }

    // -------------------------------------------------------------------------
    // tools/call — IS-05 (route log), IS-06 (policy), P1-03 (failure surface)
    // -------------------------------------------------------------------------

    private JsonNode handleToolsCall(JsonNode params) {
        long startMs = System.currentTimeMillis();
        String toolName = params != null ? params.path("name").asText(null) : null;
        String intentAnnotation = params != null ? params.path("_intent").asText(null) : null;
        // Truncate intent annotation per REQ-5.10.3 (max 500 chars)
        if (intentAnnotation != null && intentAnnotation.length() > 500) {
            intentAnnotation = intentAnnotation.substring(0, 500);
        }

        int requestSizeBytes = params != null ? params.toString().length() : 0;

        // Retrieve current session ID for route_log (REQ-8.3.1)
        String routeSessionId = sessionManager != null ? sessionManager.getCurrentSessionId() : null;

        // --- OS-14: Task-context tool handled specially ---
        if (TASK_CONTEXT_TOOL.equals(toolName)) {
            if (stateMachine.getState() != StateMachine.State.OPEN) {
                return failureResponse("session_not_open",
                        sessionOpenRecoveryReason("Task context requires an Open session."),
                        "call_mcphub_session_open", null, null);
            }
            String contextName = params != null ? params.path("arguments").path("context").asText("all") : "all";
            int beforeCount = policy.filterForAI(registry.getConfirmed()).size();
            String applied = taskContextFilter.setContext(contextName, policy, registry);
            int afterCount = policy.filterForAI(registry.getConfirmed()).size();
            int hiddenCount = beforeCount - afterCount;
            if (hiddenCount < 0) hiddenCount = 0;
            logRoute(routeSessionId, TASK_CONTEXT_TOOL, "mcphub-internal", "builtin_hosted",
                    "allowed", null, System.currentTimeMillis() - startMs,
                    requestSizeBytes, 0, intentAnnotation, null);
            return TaskContextFilter.buildResponse(applied, hiddenCount, afterCount);
        }

        // --- Disambiguation tool is handled specially ---
        if (DISAMBIGUATION_TOOL.equals(toolName)) {
            String taskDesc = params != null ? params.path("arguments").path("task_description").asText(null) : null;
            JsonNode disambResult = handleDisambiguate(taskDesc, params);
            logRoute(routeSessionId, DISAMBIGUATION_TOOL, "mcphub-internal", "builtin_hosted",
                    "allowed", null, System.currentTimeMillis() - startMs,
                    requestSizeBytes, disambResult.toString().length(), intentAnnotation, null);
            ObjectNode resp = mapper.createObjectNode();
            resp.set("content", wrapTextContent(disambResult.toString()));
            return resp;
        }

        // --- mcphub.session.open: bypass state guard, ARM+OPEN from any non-OPEN state ---
        if (SESSION_OPEN_TOOL.equals(toolName)) {
            return handleSessionOpen(startMs, requestSizeBytes, intentAnnotation);
        }

        // --- State guard (REQ-2.4.5, REQ-5.6.2 session_not_open) ---
        if (stateMachine.getState() != StateMachine.State.OPEN) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation,
                    "session_not_open");
            return failureResponse("session_not_open",
                    sessionOpenRecoveryReason("Session is not Open. Call mcphub.session.open to reopen the session."),
                    "call_mcphub_session_open", null, null);
        }

        // REQ-3.7.3: reset idle timer on every tools/call for the active session.
        // Without this, the session auto-closes after 5min even during active use.
        if (sessionManager != null) {
            sessionManager.resetActivity();
        }

        // --- mcphub_checkpoint: hub tool, handled in Java (after state guard) ---
        if (CHECKPOINT_TOOL.equals(toolName)) {
            return handleCheckpoint(params, routeSessionId, startMs, requestSizeBytes, intentAnnotation);
        }

        // --- Tool existence check ---
        if (toolName == null) {
            long latency = System.currentTimeMillis() - startMs;
            // REQ-5.2.7: every tools/call dispatch must produce a route log entry
            logRoute(routeSessionId, "(missing)", null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "tool_not_found");
            List<String> available = policy.filterForAI(registry.getConfirmed())
                    .stream().map(e -> e.displayName).collect(Collectors.toList());
            // REQ-5.4.2: include available_tools so AI can self-correct
            return failureResponse("tool_not_found", "Missing tool name in request.",
                    "abort", available, null);
        }
        var entryOpt = registry.findByDisplayName(toolName);
        if (entryOpt.isEmpty()) {
            // REQ-5.4.2: include available_tools list
            List<CapabilityEntry> altEntries = policy.filterForAI(registry.getConfirmed());
            List<String> available = altEntries.stream()
                    .map(e -> e.displayName).collect(Collectors.toList());
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "tool_not_found");
            ObjectNode gapExplanation = CapabilityGapExplainer.explain(
                    toolName, "tool_not_found", null, null, altEntries);
            return failureResponseWithGap("tool_not_found",
                    "Tool '" + toolName + "' is not registered in MCPHUB.",
                    "use_alternative", available, null, gapExplanation);
        }

        CapabilityEntry entry = entryOpt.get();
        String providerType = providerTypeForEntry(entry);
        if (!"confirmed".equals(entry.runtimeState)) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, entry.providerId, providerType,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "provider_unreachable");
            List<CapabilityEntry> altEntries = policy.filterForAI(registry.getConfirmed());
            List<String> available = altEntries.stream()
                    .map(e -> e.displayName).collect(Collectors.toList());
            ObjectNode gapExplanation = CapabilityGapExplainer.explain(
                    toolName, "provider_unreachable", null, entry, altEntries);
            return failureResponseWithGap("provider_unreachable",
                    "Tool '" + toolName + "' is registered but its provider adapter is not running.",
                    "wait_session", available, null, gapExplanation);
        }

        // --- Policy check (IS-06, REQ-2.4.3, REQ-5.2.6) ---
        PolicyEngine.PolicyResult policyResult = policy.evaluate(toolName);
        if (policyResult.decision() == PolicyEngine.Decision.DENY) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, entry.providerId, providerType,
                    "denied", policyResult.matchedRuleId(), latency, requestSizeBytes, null,
                    intentAnnotation, "tool_denied");
            // OS-15: Capability-gap explanation for denied tools
            List<CapabilityEntry> altEntries = policy.filterForAI(registry.getConfirmed());
            ObjectNode gapExplanation = CapabilityGapExplainer.explain(
                    toolName, "tool_denied", policyResult.matchedRuleId(), entry, altEntries);
            return failureResponseWithGap("tool_denied",
                    "Tool '" + toolName + "' is denied by policy rule: " + policyResult.matchedRuleId(),
                    "abort", null, policyResult.matchedRuleId(), gapExplanation);
        }
        if (policyResult.decision() == PolicyEngine.Decision.HIDE) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "tool_not_found");
            // REQ-7.3.4: hidden tools appear as not found to AI
            // REQ-5.4.2: include available_tools so AI can self-correct
            List<CapabilityEntry> altEntries = policy.filterForAI(registry.getConfirmed());
            List<String> available = new java.util.ArrayList<>(altEntries.stream()
                    .map(e -> e.displayName).toList());
            if (isTaskContextHide(policyResult)) {
                addIfMissing(available, TASK_CONTEXT_TOOL);
                ObjectNode gapExplanation = CapabilityGapExplainer.explain(
                        toolName, "tool_not_found", policyResult.matchedRuleId(), entry, altEntries);
                return failureResponseWithGap("tool_not_found",
                        "Tool '" + toolName + "' is currently hidden by task context '" +
                                taskContextFilter.getCurrentContext().name().toLowerCase() +
                                "'. Call " + TASK_CONTEXT_TOOL + " with {\"context\":\"all\"} " +
                                "to restore the full tool list.",
                        "set_task_context_all", available, policyResult.matchedRuleId(), gapExplanation);
            }
            return failureResponse("tool_not_found",
                    "Tool '" + toolName + "' is not registered in MCPHUB.", "use_alternative", available, null);
        }

        // --- OS-13: Dry-run mode — return preview without calling provider ---
        boolean isDryRun = params != null && params.path("arguments").path("_dry_run").asBoolean(false);
        if (isDryRun) {
            String groupId = resolveGroupId(toolName);
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, entry.providerId, providerType,
                    "allowed", policyResult.matchedRuleId(), latency,
                    requestSizeBytes, 0, intentAnnotation, "dry_run");
            return DryRunService.preview(entry, policyResult, groupId, providerType);
        }

        // --- OS-12: Check read-only result cache before dispatch ---
        if (ResultCache.isCacheEligible(entry)) {
            JsonNode arguments = params != null ? params.path("arguments") : mapper.createObjectNode();
            JsonNode cached = resultCache.get(toolName, arguments);
            if (cached != null) {
                long latency = System.currentTimeMillis() - startMs;
                logRoute(routeSessionId, toolName, entry.providerId, providerTypeForEntry(entry),
                        "allowed", policyResult.matchedRuleId(), latency,
                        requestSizeBytes, cached.toString().length(), intentAnnotation, null);
                return cached;
            }
        }

        // --- Dispatch: Java directly calls provider (AMD-MCPHUB-001) ---
        String groupId = resolveGroupId(toolName);

        // Build forward params for the adapter
        ObjectNode forwardParams = mapper.createObjectNode();
        String forwardName = entry.originalToolName != null && !entry.originalToolName.isBlank()
                ? entry.originalToolName
                : toolName;
        forwardParams.put("name", forwardName);
        JsonNode arguments = params != null ? params.path("arguments") : mapper.createObjectNode();
        forwardParams.set("arguments", arguments);

        // Direct provider call
        if (providerManager != null && providerManager.isRunning(groupId)) {
            long providerStartMs = System.currentTimeMillis();
            try {
                JsonNode providerResult = providerManager.call(groupId, "tools/call", forwardParams);
                long latency = providerStartMs - startMs;
                int respBytes = providerResult != null ? providerResult.toString().length() : 0;
                logRoute(routeSessionId, toolName, entry.providerId, providerType,
                        "allowed", policyResult.matchedRuleId(), latency,
                        requestSizeBytes, respBytes, intentAnnotation, null);
                // OS-12: Cache result for read-only tools
                if (ResultCache.isCacheEligible(entry) && providerResult != null) {
                    JsonNode cacheArgs = params != null ? params.path("arguments") : mapper.createObjectNode();
                    resultCache.put(toolName, cacheArgs, providerResult);
                }
                return providerResult;
            } catch (Exception e) {
                long latency = providerStartMs - startMs;
                logRoute(routeSessionId, toolName, entry.providerId, providerType,
                        "error", policyResult.matchedRuleId(), latency,
                        requestSizeBytes, null, intentAnnotation, "provider_error");
                return failureResponse("provider_error",
                        "Provider '" + groupId + "' call failed: " + e.getMessage(),
                        "retry", null, null);
            }
        } else {
            // Provider not running — return structured failure
            long latency = System.currentTimeMillis() - startMs;
            logRoute(routeSessionId, toolName, entry.providerId, providerType,
                    "error", policyResult.matchedRuleId(), latency,
                    requestSizeBytes, null, intentAnnotation, "provider_unreachable");
            List<CapabilityEntry> altEntries = policy.filterForAI(registry.getConfirmed());
            List<String> available = altEntries.stream()
                    .map(e2 -> e2.displayName).collect(Collectors.toList());
            ObjectNode gapExplanation = CapabilityGapExplainer.explain(
                    toolName, "provider_unreachable", null, entry, altEntries);
            return failureResponseWithGap("provider_unreachable",
                    "Provider group '" + groupId + "' is not running.",
                    "wait_session", available, null, gapExplanation);
        }
    }

    // -------------------------------------------------------------------------
    // Disambiguation endpoint (P1-01, REQ-5.3.1 through REQ-5.3.10)
    // Called internally from tools/call when tool name = DISAMBIGUATION_TOOL
    // -------------------------------------------------------------------------

    private JsonNode handleDisambiguate(String taskDescription, JsonNode params) {
        // REQ-5.10.1: only when Open
        if (stateMachine.getState() != StateMachine.State.OPEN) {
            return failureResponse("session_not_open",
                    sessionOpenRecoveryReason("Disambiguation requires an Open session."),
                    "call_mcphub_session_open", null, null);
        }

        if (taskDescription == null || taskDescription.isBlank()) {
            return failureResponse("internal_error",
                    "task_description is required.", "abort", null, null);
        }

        List<CapabilityEntry> candidates = policy.filterForAI(registry.getConfirmed());

        // Optional narrowing (REQ-5.3.4)
        JsonNode candidateToolsNode = params != null
                ? params.path("arguments").path("candidate_tools") : null;
        if (candidateToolsNode != null && candidateToolsNode.isArray()) {
            List<String> names = new java.util.ArrayList<>();
            candidateToolsNode.forEach(n -> names.add(n.asText()));
            candidates = candidates.stream()
                    .filter(e -> names.contains(e.displayName))
                    .collect(Collectors.toList());
        }

        // REQ-5.3.3: MUST NOT perform heuristic guessing or probabilistic ranking.
        // REQ-5.3.6: when confidence is partial/none, recommended_tool MUST be null.
        // Deterministic rule: if exactly one candidate exists after policy+narrowing filter,
        // that is the unambiguous answer (confidence=deterministic).
        // If multiple or zero candidates — hub cannot determine without guessing → none + null.
        ObjectNode result = mapper.createObjectNode();
        if (candidates.size() == 1) {
            CapabilityEntry only = candidates.get(0);
            result.put("recommended_tool", only.displayName);
            result.put("confidence", "deterministic");
            result.put("reason",
                "Exactly one tool is available in the policy-filtered registry for this request. " +
                "Tool: '" + only.displayName + "'. " +
                (only.contract != null && only.contract.purpose != null
                    ? "Purpose: " + only.contract.purpose : ""));
        } else if (candidates.isEmpty()) {
            result.putNull("recommended_tool"); // REQ-5.3.6
            result.put("confidence", "none");
            result.put("reason", "No tools are currently available in the policy-filtered registry.");
            result.put("unresolvable_reason",
                "Registry has no enabled, policy-allowed tools to recommend.");
        } else {
            // Multiple candidates — cannot determine deterministically (REQ-5.3.3 forbids heuristic)
            result.putNull("recommended_tool"); // REQ-5.3.6: MUST be null when not deterministic
            result.put("confidence", "none");
            result.put("reason",
                candidates.size() + " tools are available. Hub cannot select without heuristic " +
                "guessing, which is prohibited by REQ-5.3.3. " +
                "Narrow via 'candidate_tools' to a single tool for a deterministic answer.");
            result.put("unresolvable_reason",
                "Multiple tools match. Use candidate_tools to specify exactly one tool.");
        }

        // REQ-5.3.4: alternatives — list all candidates when no deterministic recommendation
        // When confidence=deterministic (1 candidate), alternatives is empty.
        ArrayNode alts = mapper.createArrayNode();
        if (candidates.size() != 1) {
            for (CapabilityEntry e : candidates) {
                if (e.contract == null || e.contract.purpose == null) continue;
                ObjectNode alt = mapper.createObjectNode();
                alt.put("tool", e.displayName);
                alt.put("reason", e.contract.purpose);
                alts.add(alt);
            }
        }
        result.set("alternatives", alts);

        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Called by Go daemon after spawning each adapter group and querying its tools/list.
     * Params: { "group_id": "web", "tools": ["webfetch", "websearch"] }
     * REQ-6.2.6
     */
    private JsonNode handleAdapterRegistration(JsonNode params) {
        if (params == null || !params.has("group_id") || !params.has("tools")) {
            ObjectNode err = mapper.createObjectNode();
            err.put("status", "error");
            err.put("reason", "missing group_id or tools");
            return err;
        }

        String groupId = params.get("group_id").asText();
        Set<String> toolNames = new HashSet<>();
        JsonNode tools = params.get("tools");
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                if (t.isTextual()) {
                    toolNames.add(t.asText());
                } else if (t.isObject() && t.has("name")) {
                    toolNames.add(t.get("name").asText());
                }
            }
        }

        registry.confirmTools(groupId, toolNames);
        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        r.put("confirmed_count", toolNames.size());
        return r;
    }

    /**
     * mcphub_checkpoint: persist session state for next-session handoff.
     * Reads tasks.json and nexus issues, writes checkpoint file to data directory.
     */
    private JsonNode handleCheckpoint(JsonNode params, String sessionId,
                                       long startMs, int requestSizeBytes, String intentAnnotation) {
        String message = params != null ? params.path("arguments").path("message").asText(null) : null;
        String project = params != null ? params.path("arguments").path("project").asText(null) : null;

        if (message == null || message.isBlank()) {
            return failureResponse("missing_parameter",
                    "message is required for mcphub_checkpoint",
                    "abort", null, null);
        }

        ObjectNode checkpoint = mapper.createObjectNode();
        checkpoint.put("checkpoint_time", Instant.now().toString());
        checkpoint.put("session_id", sessionId != null ? sessionId : "no-session");
        checkpoint.put("handover_message", message);
        if (project != null && !project.isBlank()) {
            checkpoint.put("project", project);
        }

        // Read tasks from tasks.json
        String tasksPath = System.getProperty("user.home") + "/.config/mcphub/tasks.json";
        java.io.File tasksFile = new java.io.File(tasksPath);
        if (tasksFile.exists()) {
            try {
                JsonNode tasksNode = mapper.readTree(tasksFile);
                checkpoint.set("tasks", tasksNode);
            } catch (Exception e) {
                checkpoint.put("tasks_error", "Failed to read tasks: " + e.getMessage());
            }
        } else {
            ArrayNode emptyTasks = mapper.createArrayNode();
            checkpoint.set("tasks", emptyTasks);
            checkpoint.put("tasks_note", "No tasks.json found — no tasks to save");
        }

        // Read nexus issues for project (if specified)
        if (project != null && !project.isBlank()) {
            String nexusBase = System.getenv("MCPHUB_NEXUS_BASE");
            if (nexusBase == null || nexusBase.isBlank()) {
                nexusBase = System.getProperty("user.home") + "/issue-store";
            }
            java.io.File projectDir = new java.io.File(nexusBase, project);
            if (projectDir.exists() && projectDir.isDirectory()) {
                ArrayNode issues = mapper.createArrayNode();
                for (java.io.File f : projectDir.listFiles((dir, name) -> name.endsWith(".md"))) {
                    ObjectNode issue = mapper.createObjectNode();
                    issue.put("file", f.getName());
                    issue.put("size", f.length());
                    issue.put("last_modified", Instant.ofEpochMilli(f.lastModified()).toString());
                    issues.add(issue);
                }
                checkpoint.set("nexus_issues", issues);
                checkpoint.put("nexus_issue_count", issues.size());
            } else {
                checkpoint.put("nexus_note", "No issue-store directory found for project: " + project);
            }
        }

        // Check for path traversal in checkpoint dir
        String resolvedDir;
        try {
            java.io.File dirFile = new java.io.File(checkpointDir).getCanonicalFile();
            dirFile.mkdirs();
            resolvedDir = dirFile.getAbsolutePath();
        } catch (Exception e) {
            return failureResponse("checkpoint_error",
                    "Cannot create checkpoint directory: " + e.getMessage(),
                    "abort", null, null);
        }

        // Write checkpoint file
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String checkpointFileName = "checkpoint_" + timestamp + ".json";
        java.io.File checkpointFile = new java.io.File(resolvedDir, checkpointFileName);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(checkpointFile, checkpoint);
        } catch (Exception e) {
            return failureResponse("checkpoint_error",
                    "Failed to write checkpoint: " + e.getMessage(),
                    "abort", null, null);
        }

        // Log route
        logRoute(sessionId, CHECKPOINT_TOOL, "mcphub-internal", "builtin_hosted",
                "allowed", null, System.currentTimeMillis() - startMs,
                requestSizeBytes, checkpoint.toString().length(), intentAnnotation, null);

        // Build response
        ObjectNode resp = mapper.createObjectNode();
        resp.put("checkpoint_file", checkpointFile.getAbsolutePath());
        resp.set("content", wrapTextContent(checkpoint.toString()));
        return resp;
    }

    /**
     * Called by Go daemon on provider state transitions.
     * Params: { "group_id": "web", "status": "running|stopped|unavailable" }
     * REQ-4.7.2
     */
    private JsonNode handleProviderHealthUpdate(JsonNode params) {
        if (params == null || !params.has("group_id") || !params.has("status")) {
            ObjectNode err = mapper.createObjectNode();
            err.put("status", "error");
            err.put("reason", "missing group_id or status");
            return err;
        }

        String groupId = params.get("group_id").asText();
        String status = params.get("status").asText();
        if (healthTracker != null) {
            healthTracker.updateGroup(groupId, status);
        }

        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        return r;
    }

    /** Build structured failure response with capability-gap explanation. OS-15 */
    private ObjectNode failureResponseWithGap(String errorCode, String reason,
            String nextAction, List<String> fallbackTools, String policyDetail,
            com.fasterxml.jackson.databind.node.ObjectNode gapExplanation) {
        ObjectNode r = failureResponse(errorCode, reason, nextAction, fallbackTools, policyDetail);
        if (gapExplanation != null) {
            r.set("capability_gap", gapExplanation);
            // Add gap explanation as a second content item
            ArrayNode content = (ArrayNode) r.get("content");
            ObjectNode gapItem = mapper.createObjectNode();
            gapItem.put("type", "text");
            gapItem.put("text", "[MCPHUB capability_gap] " + gapExplanation.toString());
            content.add(gapItem);
        }
        return r;
    }

    /** Build structured failure response. REQ-5.6.1 (P1-03) — MCP CallToolResult compliant */
    private ObjectNode failureResponse(String errorCode, String reason,
            String nextAction, List<String> fallbackTools, String policyDetail) {
        ObjectNode r = mapper.createObjectNode();
        // MCP-compliant content array (REQ-5.6.1 structured failure as text)
        ArrayNode content = mapper.createArrayNode();
        ObjectNode textItem = mapper.createObjectNode();
        textItem.put("type", "text");
        StringBuilder msg = new StringBuilder();
        msg.append("[MCPHUB error] ").append(errorCode).append(": ").append(reason);
        if (nextAction != null) {
            msg.append(" | next_action: ").append(nextAction);
        }
        if (policyDetail != null) {
            msg.append(" | policy: ").append(policyDetail);
        }
        if (fallbackTools != null && !fallbackTools.isEmpty()) {
            msg.append(" | available_tools: ").append(String.join(", ", fallbackTools));
        }
        textItem.put("text", msg.toString());
        content.add(textItem);
        r.set("content", content);
        r.put("isError", true);
        return r;
    }

    private String sessionOpenRecoveryReason(String reason) {
        return reason + " If the client cannot call that MCP tool, run CLI: " +
                cliOpenCommand() + " | cli_recovery_command: " + cliOpenCommand();
    }

    private String cliOpenCommand() {
        String command = System.getenv(CLI_COMMAND_ENV);
        if (command == null || command.isBlank()) {
            command = "mcphub";
        }
        return command + " open";
    }

    private boolean isTaskContextHide(PolicyEngine.PolicyResult policyResult) {
        return policyResult != null
                && policyResult.matchedRuleId() != null
                && policyResult.matchedRuleId().startsWith("task-ctx-hide-");
    }

    private void addIfMissing(List<String> tools, String toolName) {
        if (tools != null && !tools.contains(toolName)) {
            tools.add(toolName);
        }
    }

    /** Fire-and-forget route log (IS-05, REQ-8.3.2: async, must not block) */
    private void logRoute(String sessionId, String toolName, String providerId,
            String providerType, String decision, String policyRuleId,
            long latencyMs, int reqBytes, Integer respBytes,
            String intentAnnotation, String errorCode) {
        if (db == null) return;
        // REQ-8.3.2: async
        Thread.ofVirtual().start(() ->
            db.logRouteEntry(
                sessionId != null ? sessionId : "no-session",
                toolName != null ? toolName : "(unknown)",
                providerId != null ? providerId : "(unresolved)",
                providerType != null ? providerType : "builtin_hosted",
                decision, policyRuleId,
                latencyMs, reqBytes, respBytes,
                intentAnnotation, errorCode
            )
        );
    }

    /** Map tool display name to provider group ID. REQ-6.2.7 */
    private String resolveGroupId(String toolName) {
        // AMD-MCPHUB-001: delegate to ProviderManager if available
        if (providerManager != null) {
            return providerManager.resolveGroupId(toolName);
        }
        // Fallback for tests without ProviderManager
        return switch (toolName) {
            case "webfetch", "websearch" -> "web";
            case "apply_patch" -> "edit";
            case "todowrite", "list", "codesearch", "lsp",
                 "task_create", "task_list", "task_update", "task_delete" -> "project";
            case "nexus_issue_create", "nexus_issue_list",
                 "nexus_issue_close", "nexus_issue_update" -> "nexus";
            case "plan_enter", "plan_exit", "skill", "batch",
                 "mcphub_checkpoint" -> "session";
            case "synthetic_delay" -> "synthetic";
            default -> "unknown";
        };
    }

    private String providerTypeForEntry(CapabilityEntry entry) {
        if (entry != null && providerManager != null) {
            String groupId = providerManager.resolveGroupId(entry.displayName);
            if ("relay".equals(providerManager.getProviderType(groupId))) {
                return "relay";
            }
        }
        return "builtin_hosted";
    }

    private ArrayNode wrapTextContent(String text) {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode c = mapper.createObjectNode();
        c.put("type", "text");
        c.put("text", text);
        arr.add(c);
        return arr;
    }
}
