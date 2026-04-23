package dev.sorted.mcphub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One allow/deny/hide policy rule. Spec: Chapter 7 §7.3.1 (REQ-7.3.1)
 * action: "allow" | "deny" | "hide"
 * scope: "global" | "session"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyRule {
    @JsonProperty("rule_id") public String ruleId;
    @JsonProperty("tool_pattern") public String toolPattern;
    public String action;
    public int priority;
    public String scope = "global";
}
