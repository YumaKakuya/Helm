package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Capability-gap explanation service.
 * Proposal §6.5 / OS-15 → Alpha scope (CEO 2026-04-26).
 *
 * When a tool call fails due to policy denial, tool not found, or provider unavailability,
 * this service generates a structured explanation of:
 *   - What capability was missing or blocked
 *   - Why it was blocked (policy rule, provider state)
 *   - What alternative capabilities exist
 *   - What the operator could do to resolve the gap
 *
 * REQ (from Proposal §6.5): "When a miss would have been prevented by a premium capability,
 * the hub can expose a factual explanation the AI may relay to the human operator."
 *
 * REQ-1.10.6: explanations MUST be factual structured feedback, not upsell.
 * REQ-1.10.7: MAY only be emitted when hub can map failure to known capability gap deterministically.
 */
public class CapabilityGapExplainer {
    private static final Logger log = LoggerFactory.getLogger(CapabilityGapExplainer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Generate a capability-gap explanation for a denied tool.
     *
     * @param toolName      the denied tool name
     * @param errorCode     the MCPHUB error code (tool_denied, tool_not_found, etc.)
     * @param policyRuleId  the policy rule that caused the denial (may be null)
     * @param entry         the capability entry (may be null if tool_not_found)
     * @param alternatives  list of available alternative tools
     * @return structured explanation as JSON, or null if no deterministic explanation possible
     */
    public static ObjectNode explain(String toolName, String errorCode,
                                      String policyRuleId, CapabilityEntry entry,
                                      List<CapabilityEntry> alternatives) {
        ObjectNode explanation = mapper.createObjectNode();
        explanation.put("tool_requested", toolName);
        explanation.put("error_code", errorCode);

        switch (errorCode) {
            case "tool_denied" -> {
                String explanationText =
                        "Tool '" + toolName + "' is registered but denied by policy rule: " + policyRuleId;
                explanation.put("gap_type", "policy_restriction");
                explanation.put("explanation", explanationText);
                explanation.put("premium_feature_id", "policy_governance");
                explanation.put("reason", explanationText);
                explanation.put("policy_rule_id", policyRuleId);
                if (entry != null) {
                    addCapabilityInfo(explanation, entry);
                }
                explanation.put("operator_action",
                        "Review policy rule '" + policyRuleId + "'. " +
                        "To allow this tool, update the policy in capabilities.yaml or add a session rule with higher priority.");
            }
            case "tool_not_found" -> {
                if (isTaskContextHide(policyRuleId)) {
                    String explanationText =
                            "Tool '" + toolName + "' is registered but currently hidden by task-context filtering.";
                    explanation.put("gap_type", "task_context_filter");
                    explanation.put("explanation", explanationText);
                    explanation.put("premium_feature_id", "task_context_filtering");
                    explanation.put("reason", explanationText);
                    explanation.put("policy_rule_id", policyRuleId);
                    if (entry != null) {
                        addCapabilityInfo(explanation, entry);
                    }
                    explanation.put("operator_action",
                            "Call mcphub_set_task_context with {\"context\":\"all\"} to restore the full tool list, " +
                            "or switch to a task context where this tool is primary.");
                } else {
                    String explanationText =
                            "Tool '" + toolName + "' is not registered in the MCPHUB capability registry. " +
                            "It may need to be added as a relay provider or is not part of the current configuration.";
                    explanation.put("gap_type", "capability_not_registered");
                    explanation.put("explanation", explanationText);
                    explanation.put("premium_feature_id", "capability_registry_governance");
                    explanation.put("reason", explanationText);
                    explanation.put("operator_action",
                            "If this tool should be available, add it as a relay provider in ~/.config/mcphub/relays.yaml " +
                            "with its command, tools list, and capability definitions.");
                }
            }
            case "provider_unreachable" -> {
                String explanationText =
                        "Tool '" + toolName + "' is registered but its provider is not reachable.";
                explanation.put("gap_type", "provider_not_running");
                explanation.put("explanation", explanationText);
                explanation.put("premium_feature_id", "provider_lifecycle_governance");
                explanation.put("reason", explanationText);
                if (entry != null) {
                    addCapabilityInfo(explanation, entry);
                }
                explanation.put("operator_action",
                        "The provider may have crashed or failed to start. " +
                        "Check daemon logs and try reopening the session with 'mcphub close && mcphub open'.");
            }
            default -> {
                return null; // No deterministic explanation for unknown error types
            }
        }

        // Add alternatives
        if (alternatives != null && !alternatives.isEmpty()) {
            ArrayNode alts = mapper.createArrayNode();
            for (CapabilityEntry alt : alternatives) {
                ObjectNode a = mapper.createObjectNode();
                a.put("tool", alt.displayName);
                if (alt.contract != null && alt.contract.purpose != null) {
                    a.put("purpose", alt.contract.purpose);
                }
                a.put("access_class", alt.accessClass != null ? alt.accessClass : "unknown");

                // If the denied tool has disambiguation info against this alternative, include it
                if (entry != null && entry.contract != null && entry.contract.disambiguatesFrom != null) {
                    for (var df : entry.contract.disambiguatesFrom) {
                        if (alt.capabilityId != null && alt.capabilityId.equals(df.capabilityId)) {
                            a.put("distinction", df.distinction);
                        }
                    }
                }
                alts.add(a);
            }
            explanation.set("alternatives", alts);
        }

        return explanation;
    }

    private static void addCapabilityInfo(ObjectNode explanation, CapabilityEntry entry) {
        explanation.put("access_class", entry.accessClass != null ? entry.accessClass : "unknown");
        explanation.put("rw_boundary", entry.rwBoundary != null ? entry.rwBoundary : "unknown");
        if (entry.contract != null) {
            if (entry.contract.purpose != null) {
                explanation.put("capability_purpose", entry.contract.purpose);
            }
            if (entry.contract.sideEffectClass != null) {
                explanation.put("side_effect_class", entry.contract.sideEffectClass);
            }
        }
    }

    private static boolean isTaskContextHide(String policyRuleId) {
        return policyRuleId != null && policyRuleId.startsWith("task-ctx-hide-");
    }
}
