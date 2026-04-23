package dev.sorted.mcphub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Capability contract vocabulary. Spec: Chapter 4 §4.3.2 (REQ-4.3.2)
 * side_effect_class: "none" | "local_state" | "external_state" | "irreversible"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapabilityContract {
    public String purpose;
    @JsonProperty("may_do") public List<String> mayDo;
    @JsonProperty("must_not_do") public List<String> mustNotDo;
    @JsonProperty("when_to_call") public List<String> whenToCall;
    @JsonProperty("when_not_to_call") public List<String> whenNotToCall;
    public String fallback;
    @JsonProperty("disambiguates_from") public List<DisambiguatesFrom> disambiguatesFrom;
    @JsonProperty("side_effect_class") public String sideEffectClass;
    @JsonProperty("timeout_hint_ms") public Integer timeoutHintMs;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DisambiguatesFrom {
        @JsonProperty("capability_id") public String capabilityId;
        public String distinction;
    }
}
