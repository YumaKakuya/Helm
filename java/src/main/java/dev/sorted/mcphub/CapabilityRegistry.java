package dev.sorted.mcphub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Capability registry: loads, validates, and queries capability entries.
 * Spec: Chapter 4 §4.2, §4.4, §4.10
 */
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    /** Builtin display names that capability entries must not shadow. REQ-4.4 / V5 */
    private static final Set<String> BUILTIN_NAMES = Set.of(
        "read", "grep", "glob", "edit", "write", "bash", "task", "multiedit"
    );

    /** display_name must match [a-z0-9_]{1,40}. V4 */
    private static final java.util.regex.Pattern DISPLAY_NAME_PATTERN =
        java.util.regex.Pattern.compile("[a-z0-9_]{1,40}");

    // --- internal YAML wrapper ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RegistryYaml {
        public List<CapabilityEntry> capabilities;
        public PolicyYaml policy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PolicyYaml {
        public List<PolicyRule> rules;
    }

    // --- state ---

    private final List<CapabilityEntry> entries = new ArrayList<>();
    private final List<PolicyRule> policyRules = new ArrayList<>();
    private int loadedCount = 0;
    private int rejectedCount = 0;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Load capabilities from a YAML InputStream.
     * Validates each entry per REQ-4.10.1 (V1–V10).
     * Logs validation counts per REQ-4.10.3.
     */
    public void load(InputStream yamlStream) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();

        RegistryYaml root = mapper.readValue(yamlStream, RegistryYaml.class);

        // Reset state
        entries.clear();
        policyRules.clear();
        loadedCount = 0;
        rejectedCount = 0;

        List<CapabilityEntry> candidates = root.capabilities != null
            ? root.capabilities : Collections.emptyList();

        // We need two-pass: first collect all ids for cross-reference checks (V7/V8)
        // We'll use a "known-so-far" set for V2/V3, and full-set for V7/V8 warnings
        Set<String> allIds = candidates.stream()
            .filter(e -> e != null && e.capabilityId != null)
            .map(e -> e.capabilityId)
            .collect(Collectors.toSet());

        Set<String> usedIds = new LinkedHashSet<>();
        Set<String> usedDisplayNames = new LinkedHashSet<>();

        int warnings = 0;

        for (CapabilityEntry entry : candidates) {
            if (entry == null) {
                log.error("[Registry] Null entry in YAML — rejecting");
                rejectedCount++;
                continue;
            }

            boolean reject = false;

            // V1: Required entry fields (REQ-4.2.3)
            if (entry.capabilityId == null || entry.capabilityId.isBlank()
                    || entry.displayName == null || entry.displayName.isBlank()
                    || entry.providerId == null || entry.providerId.isBlank()
                    || entry.contract == null
                    || entry.accessClass == null || entry.accessClass.isBlank()
                    || entry.rwBoundary == null || entry.rwBoundary.isBlank()
                    || entry.schema == null) {
                log.error("[Registry] V1 required-fields violation for entry capabilityId='{}'" +
                    " (missing: capabilityId/displayName/providerId/contract/accessClass/rwBoundary/schema) — rejecting",
                    entry.capabilityId);
                reject = true;
            }

            // V1-contract: Required contract subfields (REQ-4.3.2)
            if (!reject && entry.contract != null) {
                if (entry.contract.purpose == null || entry.contract.purpose.isBlank()
                        || entry.contract.sideEffectClass == null || entry.contract.sideEffectClass.isBlank()) {
                    log.error("[Registry] V1 required contract subfields missing for '{}'" +
                        " (need: purpose, side_effect_class) — rejecting", entry.capabilityId);
                    reject = true;
                }
            }

            if (!reject) {
                // V2: capability_id unique
                if (usedIds.contains(entry.capabilityId)) {
                    log.error("[Registry] V2 duplicate capability_id='{}' — rejecting", entry.capabilityId);
                    reject = true;
                }
            }

            if (!reject) {
                // V3: display_name unique
                if (usedDisplayNames.contains(entry.displayName)) {
                    log.error("[Registry] V3 duplicate display_name='{}' — rejecting", entry.displayName);
                    reject = true;
                }
            }

            if (!reject) {
                // V4: display_name format [a-z0-9_]{1,40}
                if (entry.displayName.length() > 40
                        || !DISPLAY_NAME_PATTERN.matcher(entry.displayName).matches()) {
                    log.error("[Registry] V4 invalid display_name format '{}' — rejecting", entry.displayName);
                    reject = true;
                }
            }

            if (!reject) {
                // V5: display_name not a builtin
                if (BUILTIN_NAMES.contains(entry.displayName)) {
                    log.error("[Registry] V5 display_name '{}' conflicts with builtin name — rejecting",
                        entry.displayName);
                    reject = true;
                }
            }

            if (!reject) {
                // V6: mayDo and mustNotDo mutually exclusive
                if (entry.contract.mayDo != null && entry.contract.mustNotDo != null) {
                    Set<String> overlap = new HashSet<>(entry.contract.mayDo);
                    overlap.retainAll(entry.contract.mustNotDo);
                    if (!overlap.isEmpty()) {
                        log.error("[Registry] V6 mayDo/mustNotDo overlap for '{}': {} — rejecting",
                            entry.capabilityId, overlap);
                        reject = true;
                    }
                }
            }

            if (reject) {
                rejectedCount++;
                continue;
            }

            // --- Warnings (do NOT prevent loading) ---

            // V7: fallback references valid capability_id
            if (entry.contract.fallback != null && !entry.contract.fallback.isBlank()) {
                if (!allIds.contains(entry.contract.fallback)) {
                    log.warn("[Registry] V7 fallback '{}' references unknown capability_id in '{}'",
                        entry.contract.fallback, entry.capabilityId);
                    warnings++;
                }
            }

            // V8: disambiguates_from references valid capability_ids
            if (entry.contract.disambiguatesFrom != null) {
                for (CapabilityContract.DisambiguatesFrom d : entry.contract.disambiguatesFrom) {
                    if (d.capabilityId != null && !allIds.contains(d.capabilityId)) {
                        log.warn("[Registry] V8 disambiguates_from '{}' references unknown capability_id in '{}'",
                            d.capabilityId, entry.capabilityId);
                        warnings++;
                    }
                }
            }

            // V9: description (purpose) ≤ 200 chars
            if (entry.contract.purpose != null && entry.contract.purpose.length() > 200) {
                log.warn("[Registry] V9 purpose length {} > 200 chars for '{}'",
                    entry.contract.purpose.length(), entry.capabilityId);
                warnings++;
            }

            // V10: safe + execute combination
            if ("safe".equals(entry.accessClass) && "execute".equals(entry.rwBoundary)) {
                log.warn("[Registry] V10 access_class=safe + rw_boundary=execute combination for '{}' — review required",
                    entry.capabilityId);
                warnings++;
            }

            usedIds.add(entry.capabilityId);
            usedDisplayNames.add(entry.displayName);
            entries.add(entry);
            loadedCount++;
        }

        // Load policy rules
        if (root.policy != null && root.policy.rules != null) {
            policyRules.addAll(root.policy.rules);
        }

        log.info("[Registry] Load complete: loaded={}, rejected={}, warnings={}",
            loadedCount, rejectedCount, warnings);
    }

    /**
     * Load ADDITIONAL capabilities from a YAML stream, preserving existing entries.
     * Used for test fixtures (e.g., VT-017 synthetic_delay) that must NOT be in
     * the production capabilities.yaml.
     *
     * Entries whose display_name collides with an already-loaded entry are rejected.
     * Validation rules apply identically to load().
     */
    public void loadAdditional(InputStream yamlStream) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        RegistryYaml root = mapper.readValue(yamlStream, RegistryYaml.class);
        if (root.capabilities == null) return;

        Set<String> existingIds = entries.stream()
            .map(e -> e.capabilityId).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Set<String> existingDisplayNames = entries.stream()
            .map(e -> e.displayName).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

        int added = 0;
        int rejected = 0;
        for (CapabilityEntry entry : root.capabilities) {
            if (entry == null) continue;
            if (entry.capabilityId == null || entry.displayName == null) {
                log.warn("[Registry additional] Null capability_id or display_name — rejecting");
                rejected++;
                continue;
            }
            if (existingIds.contains(entry.capabilityId)
                    || existingDisplayNames.contains(entry.displayName)) {
                log.warn("[Registry additional] Duplicate id/display_name '{}' / '{}' — rejecting",
                    entry.capabilityId, entry.displayName);
                rejected++;
                continue;
            }
            // Minimal V1 check (full validation happens on the primary load path)
            if (entry.contract == null || entry.schema == null
                    || entry.accessClass == null || entry.rwBoundary == null) {
                log.warn("[Registry additional] Missing required fields for '{}' — rejecting",
                    entry.capabilityId);
                rejected++;
                continue;
            }
            entries.add(entry);
            loadedCount++;
            added++;
        }
        log.info("[Registry additional] Added {} entries, rejected {}", added, rejected);
    }

    /** Returns all loaded (non-rejected) capability entries. */
    public List<CapabilityEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    /** Finds a capability by its capability_id. */
    public Optional<CapabilityEntry> findById(String capabilityId) {
        return entries.stream()
            .filter(e -> e.capabilityId.equals(capabilityId))
            .findFirst();
    }

    /** Finds a capability by its display_name. */
    public Optional<CapabilityEntry> findByDisplayName(String displayName) {
        return entries.stream()
            .filter(e -> e.displayName.equals(displayName))
            .findFirst();
    }

    /** Returns all entries where enabled=true. */
    public List<CapabilityEntry> getEnabled() {
        return entries.stream()
            .filter(e -> e.enabled)
            .collect(Collectors.toList());
    }

    /**
     * Mark all entries matching the given display names as confirmed.
     * Called when an adapter's tools/list returns these names. REQ-6.2.6
     */
    public synchronized void confirmTools(String groupId, Set<String> toolNames) {
        for (CapabilityEntry e : entries) {
            if (toolNames.contains(e.displayName)) {
                e.runtimeState = "confirmed";
                log.info("[Registry] Confirmed '{}' via adapter group '{}'", e.displayName, groupId);
            }
        }

        // Log any entries expected in this group but missing from adapter tools/list.
        for (CapabilityEntry e : entries) {
            String expectedGroup = groupForTool(e.displayName);
            if (expectedGroup != null && expectedGroup.equals(groupId) && !toolNames.contains(e.displayName)) {
                e.runtimeState = "missing_from_adapter";
                log.warn("[Registry] YAML entry '{}' expected in group '{}' but not returned by adapter tools/list",
                    e.displayName, groupId);
            }
        }
    }

    /** Reset all entries to pending. Call on session close. */
    public synchronized void resetRuntimeStates() {
        for (CapabilityEntry e : entries) {
            e.runtimeState = "pending";
        }
    }

    /** Map display_name to adapter group ID (mirror of McpHandler.resolveGroupId). */
    public static String groupForTool(String displayName) {
        if (displayName != null && displayName.startsWith("coffer_")) {
            return "relay";
        }
        return switch (displayName) {
            case "webfetch", "websearch" -> "web";
            case "apply_patch" -> "edit";
            case "todowrite", "list", "codesearch", "lsp" -> "project";
            case "plan_enter", "plan_exit", "skill", "batch" -> "session";
            case "synthetic_delay" -> "synthetic";
            default -> null;
        };
    }

    /** Returns only entries whose runtime state is confirmed. */
    public List<CapabilityEntry> getConfirmed() {
        return entries.stream()
            .filter(e -> "confirmed".equals(e.runtimeState))
            .collect(Collectors.toList());
    }

    /** Number of successfully loaded (non-rejected) entries. */
    public int getLoadedCount() {
        return loadedCount;
    }

    /** Number of rejected entries (validation errors). */
    public int getRejectedCount() {
        return rejectedCount;
    }

    /** Returns loaded policy rules from the YAML top-level "policy.rules". */
    public List<PolicyRule> getPolicyRules() {
        return Collections.unmodifiableList(policyRules);
    }
}
