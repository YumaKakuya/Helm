package dev.sorted.mcphub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CapabilityRegistry.
 * Spec: Chapter 4 §4.2, §4.4, §4.10
 */
class CapabilityRegistryTest {

    private CapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistry();
    }

    // -----------------------------------------------------------------------
    // Test 1: load() with embedded capabilities.yaml — 11 entries, 0 rejected
    // (11 builtin-hosted). synthetic_delay is a TEST FIXTURE,
    // kept out of production capabilities.yaml — loaded via MCPHUB_TEST_FIXTURE.
    // -----------------------------------------------------------------------

    @Test
    void loadEmbeddedYaml_11EntriesLoaded_0Rejected() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            assertNotNull(is, "capabilities.yaml must be on classpath");
            registry.load(is);
        }
        assertEquals(11, registry.getLoadedCount(), "Expected 11 capabilities (builtin-hosted)");
        assertEquals(0, registry.getRejectedCount(), "Expected 0 rejected entries");
        assertEquals(11, registry.getAll().size());
    }

    // -----------------------------------------------------------------------
    // Test 2: findById("webfetch") returns correct fields
    // -----------------------------------------------------------------------

    @Test
    void findById_webfetch_correctFields() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        Optional<CapabilityEntry> opt = registry.findById("webfetch");
        assertTrue(opt.isPresent(), "webfetch should be found by id");
        CapabilityEntry e = opt.get();
        assertEquals("webfetch", e.capabilityId);
        assertEquals("webfetch", e.displayName);
        assertEquals("builtin-hatch", e.providerId);
        assertEquals("restricted", e.accessClass);
        assertEquals("execute", e.rwBoundary);
        assertTrue(e.enabled);
        assertNotNull(e.contract);
        assertEquals("external_state", e.contract.sideEffectClass);
        assertEquals(30000, e.contract.timeoutHintMs);
        assertNotNull(e.contract.disambiguatesFrom);
        assertFalse(e.contract.disambiguatesFrom.isEmpty());
        assertEquals("websearch", e.contract.disambiguatesFrom.get(0).capabilityId);
    }

    // -----------------------------------------------------------------------
    // Test 3: findByDisplayName("websearch") works
    // -----------------------------------------------------------------------

    @Test
    void findByDisplayName_websearch_found() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        Optional<CapabilityEntry> opt = registry.findByDisplayName("websearch");
        assertTrue(opt.isPresent(), "websearch should be found by display name");
        assertEquals("websearch", opt.get().capabilityId);
        assertEquals("safe", opt.get().accessClass);
        assertEquals("read", opt.get().rwBoundary);
    }

    // -----------------------------------------------------------------------
    // Test 4: getEnabled() == getAll() when all enabled=true
    // -----------------------------------------------------------------------

    @Test
    void getEnabled_allEnabledTrue_matchesGetAll() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        List<CapabilityEntry> all = registry.getAll();
        List<CapabilityEntry> enabled = registry.getEnabled();
        assertEquals(all.size(), enabled.size(), "getEnabled() should equal getAll() when all enabled=true");
        assertTrue(all.containsAll(enabled));
        assertTrue(enabled.containsAll(all));
    }

    // -----------------------------------------------------------------------
    // Test 5: V2 — duplicate capability_id → entry rejected, loadedCount decremented
    // -----------------------------------------------------------------------

    @Test
    void validation_duplicateCapabilityId_rejected() throws Exception {
        // Both entries are V1-valid (have schema + side_effect_class); second is rejected by V2 (dup id)
        String yaml = "capabilities:\n"
            + "  - capability_id: \"tool_a\"\n"
            + "    display_name: \"tool_a\"\n"
            + "    provider_id: \"p1\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    schema:\n"
            + "      type: object\n"
            + "    contract:\n"
            + "      purpose: \"First tool\"\n"
            + "      side_effect_class: \"none\"\n"
            + "  - capability_id: \"tool_a\"\n"
            + "    display_name: \"tool_a_dup\"\n"
            + "    provider_id: \"p1\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    schema:\n"
            + "      type: object\n"
            + "    contract:\n"
            + "      purpose: \"Duplicate id\"\n"
            + "      side_effect_class: \"none\"\n";
        try (InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            registry.load(is);
        }
        assertEquals(1, registry.getLoadedCount(), "Only first entry should be loaded");
        assertEquals(1, registry.getRejectedCount(), "Duplicate should be rejected");
    }

    // -----------------------------------------------------------------------
    // Test 6: V4 — invalid display_name format → entry rejected
    // -----------------------------------------------------------------------

    @Test
    void validation_invalidDisplayNameFormat_rejected() throws Exception {
        // V1-valid except for display_name format (V4 violation)
        String yaml = "capabilities:\n"
            + "  - capability_id: \"bad_name_tool\"\n"
            + "    display_name: \"Bad-Name!\"\n"  // invalid: uppercase + special chars
            + "    provider_id: \"p1\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    schema:\n"
            + "      type: object\n"
            + "    contract:\n"
            + "      purpose: \"Tool with bad name\"\n"
            + "      side_effect_class: \"none\"\n";
        try (InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            registry.load(is);
        }
        assertEquals(0, registry.getLoadedCount(), "Entry with invalid display_name should not be loaded");
        assertEquals(1, registry.getRejectedCount());
    }

    // -----------------------------------------------------------------------
    // Test 7: V6 — mayDo / mustNotDo overlap → entry rejected
    // -----------------------------------------------------------------------

    @Test
    void validation_mayDoMustNotDoOverlap_rejected() throws Exception {
        // V1-valid (has schema + side_effect_class); rejected by V6 (may/must overlap)
        String yaml = "capabilities:\n"
            + "  - capability_id: \"overlap_tool\"\n"
            + "    display_name: \"overlap_tool\"\n"
            + "    provider_id: \"p1\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    schema:\n"
            + "      type: object\n"
            + "    contract:\n"
            + "      purpose: \"Tool with overlap\"\n"
            + "      side_effect_class: \"none\"\n"
            + "      may_do:\n"
            + "        - \"Do thing A\"\n"
            + "        - \"Do thing B\"\n"
            + "      must_not_do:\n"
            + "        - \"Do thing A\"\n"  // overlap with may_do
            + "        - \"Do thing C\"\n";
        try (InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            registry.load(is);
        }
        assertEquals(0, registry.getLoadedCount(), "Entry with mayDo/mustNotDo overlap should not be loaded");
        assertEquals(1, registry.getRejectedCount());
    }

    // -----------------------------------------------------------------------
    // Test 8: getPolicyRules() returns default-allow-all rule
    // -----------------------------------------------------------------------

    @Test
    void getPolicyRules_defaultAllowAll_present() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        List<PolicyRule> rules = registry.getPolicyRules();
        assertNotNull(rules);
        assertFalse(rules.isEmpty(), "At least one policy rule should be loaded");
        PolicyRule first = rules.get(0);
        assertEquals("default-allow-all", first.ruleId);
        assertEquals("*", first.toolPattern);
        assertEquals("allow", first.action);
        assertEquals(1, first.priority);
        assertEquals("global", first.scope);
    }

    // -----------------------------------------------------------------------
    // loadAdditional (test fixture mechanism — VT-017)
    // -----------------------------------------------------------------------

    @Test
    void loadAdditional_addsEntriesWithoutReset() throws Exception {
        // Load production first
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        int before = registry.getLoadedCount();

        // Load additional (test fixture)
        String fixtureYaml = "capabilities:\n"
            + "  - capability_id: \"test_extra\"\n"
            + "    display_name: \"test_extra\"\n"
            + "    provider_id: \"builtin-test\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    enabled: true\n"
            + "    schema: { type: object }\n"
            + "    contract:\n"
            + "      purpose: \"test fixture\"\n"
            + "      side_effect_class: \"none\"\n";
        try (InputStream is = new ByteArrayInputStream(fixtureYaml.getBytes(StandardCharsets.UTF_8))) {
            registry.loadAdditional(is);
        }

        assertEquals(before + 1, registry.getLoadedCount());
        assertTrue(registry.findByDisplayName("test_extra").isPresent());
        // Production entries still present
        assertTrue(registry.findByDisplayName("webfetch").isPresent());
    }

    @Test
    void loadAdditional_rejectsDuplicateDisplayName() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("capabilities.yaml")) {
            registry.load(is);
        }
        int before = registry.getLoadedCount();

        String fixtureYaml = "capabilities:\n"
            + "  - capability_id: \"webfetch_dup\"\n"
            + "    display_name: \"webfetch\"\n"   // collision with production
            + "    provider_id: \"builtin-test\"\n"
            + "    access_class: \"safe\"\n"
            + "    rw_boundary: \"read\"\n"
            + "    enabled: true\n"
            + "    schema: { type: object }\n"
            + "    contract: { purpose: \"dup\", side_effect_class: \"none\" }\n";
        try (InputStream is = new ByteArrayInputStream(fixtureYaml.getBytes(StandardCharsets.UTF_8))) {
            registry.loadAdditional(is);
        }
        assertEquals(before, registry.getLoadedCount(), "Duplicate must not be added");
    }
}
