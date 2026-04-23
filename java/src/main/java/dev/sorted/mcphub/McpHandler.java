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
    private static final String SERVER_VERSION = "0.1.0-alpha";

    private final StateMachine stateMachine;
    private final CapabilityRegistry registry;
    private final PolicyEngine policy;
    private final DatabaseManager db;
    private final BodyBudgetService bodyBudget;
    private ProviderHealthTracker healthTracker;
    private SessionManager sessionManager;  // nullable; required for REQ-3.7.3 idle reset
    private ProviderManager providerManager; // AMD-MCPHUB-001: Java-native provider dispatch
    private String serverName = "mcphub";

    public McpHandler(StateMachine stateMachine, CapabilityRegistry registry,
                      PolicyEngine policy, DatabaseManager db, BodyBudgetService bodyBudget) {
        this.stateMachine = stateMachine;
        this.registry = registry;
        this.policy = policy;
        this.db = db;
        this.bodyBudget = bodyBudget;
        this.healthTracker = null;
        this.sessionManager = null;
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

        boolean isOpen = stateMachine.getState() == StateMachine.State.OPEN;
        if (!isOpen) {
            // REQ-7.4.2: not Open → empty tool list
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

        // --- Disambiguation tool is handled specially ---
        if (DISAMBIGUATION_TOOL.equals(toolName)) {
            String taskDesc = params != null ? params.path("arguments").path("task_description").asText(null) : null;
            JsonNode disambResult = handleDisambiguate(taskDesc, params);
            logRoute(null, DISAMBIGUATION_TOOL, "mcphub-internal", "builtin_hosted",
                    "allowed", null, System.currentTimeMillis() - startMs,
                    requestSizeBytes, disambResult.toString().length(), intentAnnotation, null);
            ObjectNode resp = mapper.createObjectNode();
            resp.set("content", wrapTextContent(disambResult.toString()));
            return resp;
        }

        // --- State guard (REQ-2.4.5, REQ-5.6.2 session_not_open) ---
        if (stateMachine.getState() != StateMachine.State.OPEN) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation,
                    "session_not_open");
            return failureResponse("session_not_open",
                    "Session is not Open. Call mcphub.control.open first.",
                    "wait_session", null, null);
        }

        // REQ-3.7.3: reset idle timer on every tools/call for the active session.
        // Without this, the session auto-closes after 5min even during active use.
        if (sessionManager != null) {
            sessionManager.resetActivity();
        }

        // --- Tool existence check ---
        if (toolName == null) {
            long latency = System.currentTimeMillis() - startMs;
            // REQ-5.2.7: every tools/call dispatch must produce a route log entry
            logRoute(null, "(missing)", null, null,
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
            List<String> available = policy.filterForAI(registry.getConfirmed())
                    .stream().map(e -> e.displayName).collect(Collectors.toList());
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "tool_not_found");
            return failureResponse("tool_not_found",
                    "Tool '" + toolName + "' is not registered in MCPHUB.",
                    "use_alternative", available, null);
        }

        CapabilityEntry entry = entryOpt.get();
        String providerType = providerTypeForEntry(entry);
        if (!"confirmed".equals(entry.runtimeState)) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, entry.providerId, providerType,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "provider_unavailable");
            List<String> available = policy.filterForAI(registry.getConfirmed())
                    .stream().map(e -> e.displayName).collect(Collectors.toList());
            return failureResponse("provider_unavailable",
                    "Tool '" + toolName + "' is registered but its provider adapter is not running.",
                    "wait_session", available, null);
        }

        // --- Policy check (IS-06, REQ-2.4.3, REQ-5.2.6) ---
        PolicyEngine.PolicyResult policyResult = policy.evaluate(toolName);
        if (policyResult.decision() == PolicyEngine.Decision.DENY) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, entry.providerId, providerType,
                    "denied", policyResult.matchedRuleId(), latency, requestSizeBytes, null,
                    intentAnnotation, "tool_denied");
            return failureResponse("tool_denied",
                    "Tool '" + toolName + "' is denied by policy rule: " + policyResult.matchedRuleId(),
                    "abort", null, policyResult.matchedRuleId());
        }
        if (policyResult.decision() == PolicyEngine.Decision.HIDE) {
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, null, null,
                    "error", null, latency, requestSizeBytes, null, intentAnnotation, "tool_not_found");
            // REQ-7.3.4: hidden tools appear as not found to AI
            // REQ-5.4.2: include available_tools so AI can self-correct
            List<String> available = policy.filterForAI(registry.getConfirmed())
                    .stream().map(e -> e.displayName).collect(Collectors.toList());
            return failureResponse("tool_not_found",
                    "Tool '" + toolName + "' is not registered in MCPHUB.", "use_alternative", available, null);
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
            try {
                JsonNode providerResult = providerManager.call(groupId, "tools/call", forwardParams);
                long latency = System.currentTimeMillis() - startMs;
                int respBytes = providerResult != null ? providerResult.toString().length() : 0;
                logRoute(null, toolName, entry.providerId, providerType,
                        "allowed", policyResult.matchedRuleId(), latency,
                        requestSizeBytes, respBytes, intentAnnotation, null);
                return providerResult;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - startMs;
                logRoute(null, toolName, entry.providerId, providerType,
                        "error", policyResult.matchedRuleId(), latency,
                        requestSizeBytes, null, intentAnnotation, "provider_call_failed");
                return failureResponse("provider_call_failed",
                        "Provider '" + groupId + "' call failed: " + e.getMessage(),
                        "retry", null, null);
            }
        } else {
            // Provider not running — return structured failure
            long latency = System.currentTimeMillis() - startMs;
            logRoute(null, toolName, entry.providerId, providerType,
                    "error", policyResult.matchedRuleId(), latency,
                    requestSizeBytes, null, intentAnnotation, "provider_unavailable");
            List<String> available = policy.filterForAI(registry.getConfirmed())
                    .stream().map(e2 -> e2.displayName).collect(Collectors.toList());
            return failureResponse("provider_unavailable",
                    "Provider group '" + groupId + "' is not running.",
                    "wait_session", available, null);
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
                    "Disambiguation requires an Open session.", "wait_session", null, null);
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
            case "todowrite", "list", "codesearch", "lsp" -> "project";
            case "plan_enter", "plan_exit", "skill", "batch" -> "session";
            case "synthetic_delay" -> "synthetic";
            default -> {
                var entry = registry.findByDisplayName(toolName);
                if (entry.isPresent()
                        && entry.get().providerId != null
                        && entry.get().providerId.startsWith("coffer-")) {
                    yield "relay";
                }
                yield "unknown";
            }
        };
    }

    private String providerTypeForEntry(CapabilityEntry entry) {
        if (entry != null && entry.providerId != null && entry.providerId.startsWith("coffer-")) {
            return "relay";
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
