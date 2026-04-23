package dev.sorted.mcphub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for ProviderHealthTracker.
 * Spec: REQ-4.7.2 — provider_health per entry reflects current subprocess state.
 */
class ProviderHealthTrackerTest {

    private ProviderHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ProviderHealthTracker();
    }

    // --- updateGroup ---

    @Test
    void updateGroup_storesStatus() {
        tracker.updateGroup("web", "running");
        assertEquals("running", tracker.getGroupHealth("web"));
    }

    @Test
    void updateGroup_overwritesPreviousStatus() {
        tracker.updateGroup("web", "running");
        tracker.updateGroup("web", "stopped");
        assertEquals("stopped", tracker.getGroupHealth("web"));
    }

    @Test
    void updateGroup_nullGroupId_isIgnored() {
        // Must not crash or store a null key
        tracker.updateGroup(null, "running");
        // No exception thrown
    }

    @Test
    void updateGroup_blankGroupId_isIgnored() {
        tracker.updateGroup("", "running");
        tracker.updateGroup("   ", "running");
        // No exception, no side effect
        assertEquals("unavailable", tracker.getGroupHealth(""));
    }

    // --- getGroupHealth ---

    @Test
    void getGroupHealth_unknownGroup_returnsUnavailable() {
        // Default when no update has been received
        assertEquals("unavailable", tracker.getGroupHealth("nonexistent"));
    }

    // --- healthForTool ---

    @Test
    void healthForTool_mapsViaRegistry_web() {
        tracker.updateGroup("web", "running");
        assertEquals("running", tracker.healthForTool("webfetch"));
        assertEquals("running", tracker.healthForTool("websearch"));
    }

    @Test
    void healthForTool_mapsViaRegistry_edit() {
        tracker.updateGroup("edit", "stopped");
        assertEquals("stopped", tracker.healthForTool("apply_patch"));
    }

    @Test
    void healthForTool_mapsViaRegistry_project() {
        tracker.updateGroup("project", "running");
        assertEquals("running", tracker.healthForTool("todowrite"));
        assertEquals("running", tracker.healthForTool("list"));
        assertEquals("running", tracker.healthForTool("codesearch"));
        assertEquals("running", tracker.healthForTool("lsp"));
    }

    @Test
    void healthForTool_mapsViaRegistry_session() {
        tracker.updateGroup("session", "unavailable");
        assertEquals("unavailable", tracker.healthForTool("plan_enter"));
        assertEquals("unavailable", tracker.healthForTool("plan_exit"));
        assertEquals("unavailable", tracker.healthForTool("skill"));
        assertEquals("unavailable", tracker.healthForTool("batch"));
    }

    @Test
    void healthForTool_mapsViaRegistry_relay_cofferPrefix() {
        tracker.updateGroup("relay", "running");
        assertEquals("running", tracker.healthForTool("coffer_list_projects"));
        assertEquals("running", tracker.healthForTool("coffer_retrieve"));
    }

    @Test
    void healthForTool_unmapped_returnsUnavailable() {
        assertEquals("unavailable", tracker.healthForTool("nonexistent_tool"));
    }

    @Test
    void healthForTool_nullToolName_returnsUnavailable() {
        // Must not crash
        assertEquals("unavailable", tracker.healthForTool(null));
    }

    // --- clear ---

    @Test
    void clear_removesAllStatuses() {
        tracker.updateGroup("web", "running");
        tracker.updateGroup("session", "stopped");
        tracker.clear();
        assertEquals("unavailable", tracker.getGroupHealth("web"));
        assertEquals("unavailable", tracker.getGroupHealth("session"));
    }

    // --- snapshot ---

    @Test
    void snapshot_returnsImmutableCopy() {
        tracker.updateGroup("web", "running");
        tracker.updateGroup("edit", "stopped");
        var snap = tracker.snapshot();
        assertEquals(2, snap.size());
        assertEquals("running", snap.get("web"));
        assertEquals("stopped", snap.get("edit"));
        // Modifying snapshot must not affect tracker
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
    }
}
