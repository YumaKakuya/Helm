package dev.sorted.mcphub;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * One capability registry entry. Spec: Chapter 4 §4.2.2 (REQ-4.2.3)
 * access_class: "safe" | "guarded" | "restricted"
 * rw_boundary: "read" | "write" | "execute"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapabilityEntry {
    @JsonProperty("capability_id") public String capabilityId;
    @JsonProperty("display_name") public String displayName;
    @JsonProperty("original_tool_name") public String originalToolName;
    @JsonProperty("provider_id") public String providerId;
    public CapabilityContract contract;
    @JsonProperty("access_class") public String accessClass;
    @JsonProperty("rw_boundary") public String rwBoundary;
    public boolean enabled = true;
    public Map<String, Object> schema;
    public Integer priority;

    /** Runtime state: "pending", "confirmed", "missing_from_adapter". Not persisted. */
    @JsonIgnore public String runtimeState = "pending";
}
