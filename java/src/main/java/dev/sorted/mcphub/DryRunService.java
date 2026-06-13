package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Dry-run mode for destructive tools.
 * Proposal §6.5 / OS-13 → Alpha scope (CEO 2026-04-26).
 *
 * When _dry_run=true is passed in a tools/call request, MCPHUB returns
 * a preview of what WOULD happen without actually calling the provider:
 *   - Policy decision (allow/deny/hide)
 *   - Contract metadata (purpose, may_do, must_not_do, side_effect_class)
 *   - Access class and rw_boundary
 *   - Provider routing info (group_id, provider_type)
 *
 * Dry-run is available for all tools but is most useful for guarded/restricted tools
 * where side effects are non-trivial.
 */
public class DryRunService {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Generate a dry-run preview response for a tool call.
     *
     * @param entry      the capability entry for the tool
     * @param policyResult the policy evaluation result
     * @param groupId    the resolved provider group ID
     * @param providerType the provider type (builtin_hosted or relay)
     * @return MCP-compliant CallToolResult with dry-run preview
     */
    public static ObjectNode preview(CapabilityEntry entry, PolicyEngine.PolicyResult policyResult,
                                      String groupId, String providerType) {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode textItem = mapper.createObjectNode();
        textItem.put("type", "text");

        ObjectNode preview = mapper.createObjectNode();
        preview.put("dry_run", true);
        preview.put("tool_name", entry.displayName);

        // Policy
        preview.put("policy_decision", policyResult.decision().name().toLowerCase());
        if (policyResult.matchedRuleId() != null) {
            preview.put("policy_rule_id", policyResult.matchedRuleId());
        }

        // Access classification
        preview.put("access_class", entry.accessClass != null ? entry.accessClass : "unknown");
        preview.put("rw_boundary", entry.rwBoundary != null ? entry.rwBoundary : "unknown");

        // Routing
        preview.put("provider_group", groupId);
        preview.put("provider_type", providerType);
        preview.put("provider_running", true);

        // Contract details
        if (entry.contract != null) {
            ObjectNode contract = mapper.createObjectNode();
            if (entry.contract.purpose != null) contract.put("purpose", entry.contract.purpose);
            if (entry.contract.sideEffectClass != null) contract.put("side_effect_class", entry.contract.sideEffectClass);
            if (entry.contract.mayDo != null) {
                ArrayNode mayDo = mapper.createArrayNode();
                entry.contract.mayDo.forEach(mayDo::add);
                contract.set("may_do", mayDo);
            }
            if (entry.contract.mustNotDo != null) {
                ArrayNode mustNotDo = mapper.createArrayNode();
                entry.contract.mustNotDo.forEach(mustNotDo::add);
                contract.set("must_not_do", mustNotDo);
            }
            preview.set("contract", contract);
        }

        // Safety warning for destructive tools
        if ("restricted".equals(entry.accessClass) || "execute".equals(entry.rwBoundary)) {
            preview.put("safety_warning", "This tool modifies external state. Review the contract before execution.");
        }
        if ("guarded".equals(entry.accessClass)) {
            preview.put("safety_warning", "This tool requires caution. Side effects may be significant.");
        }

        textItem.put("text", preview.toString());
        content.add(textItem);
        r.set("content", content);
        r.put("isError", false);
        return r;
    }
}
