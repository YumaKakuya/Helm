package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProviderManager (AMD-MCPHUB-001: Java-native provider management).
 *
 * Tests subprocess lifecycle, group resolution, and error handling
 * without requiring real adapter scripts.
 */
class ProviderManagerTest {

    @Test
    void defaultGroups_contains5Groups() {
        List<ProviderManager.GroupConfig> groups = ProviderManager.defaultGroups();
        assertEquals(5, groups.size());
        assertEquals("web", groups.get(0).id());
        assertEquals("edit", groups.get(1).id());
        assertEquals("project", groups.get(2).id());
        assertEquals("session", groups.get(3).id());
        assertEquals("synthetic", groups.get(4).id());
    }

    @Test
    void resolveGroupId_staticMappings() {
        ProviderManager pm = new ProviderManager("/nonexistent",
                ProviderManager.defaultGroups());

        assertEquals("web", pm.resolveGroupId("webfetch"));
        assertEquals("web", pm.resolveGroupId("websearch"));
        assertEquals("edit", pm.resolveGroupId("apply_patch"));
        assertEquals("project", pm.resolveGroupId("todowrite"));
        assertEquals("project", pm.resolveGroupId("list"));
        assertEquals("project", pm.resolveGroupId("codesearch"));
        assertEquals("project", pm.resolveGroupId("lsp"));
        assertEquals("session", pm.resolveGroupId("plan_enter"));
        assertEquals("session", pm.resolveGroupId("plan_exit"));
        assertEquals("session", pm.resolveGroupId("skill"));
        assertEquals("session", pm.resolveGroupId("batch"));
        assertEquals("unknown", pm.resolveGroupId("nonexistent_tool"));
    }

    @Test
    void isRunning_returnsFalseWhenNotStarted() {
        ProviderManager pm = new ProviderManager("/nonexistent",
                ProviderManager.defaultGroups());
        assertFalse(pm.isRunning("web"));
        assertFalse(pm.isRunning("nonexistent"));
    }

    @Test
    void call_throwsWhenGroupNotRunning() {
        ProviderManager pm = new ProviderManager("/nonexistent",
                ProviderManager.defaultGroups());
        assertThrows(IllegalStateException.class, () ->
                pm.call("web", "tools/list", null));
    }

    @Test
    void startAll_handlesNonexistentAdapterDir() {
        // Should log errors but not throw
        ProviderManager pm = new ProviderManager("/nonexistent/path",
                List.of(new ProviderManager.GroupConfig(
                        "test", "test/index.js", null,
                        new String[]{"test_tool"}, "builtin_hosted")));
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        pm.setHealthTracker(tracker);

        pm.startAll(); // should not throw
        assertFalse(pm.isRunning("test"));
        assertEquals("unavailable", tracker.healthForTool("test_tool"));
    }

    @Test
    void stopAll_handlesEmptyState() {
        ProviderManager pm = new ProviderManager("/nonexistent",
                ProviderManager.defaultGroups());
        // Should not throw on empty state
        pm.stopAll();
    }

    @Test
    void startAll_withEchoScript_spawnsProcess(@TempDir Path tempDir) throws Exception {
        // Create a minimal node script that responds to tools/list
        Path scriptDir = tempDir.resolve("test");
        Files.createDirectories(scriptDir);
        Path script = scriptDir.resolve("index.js");
        Files.writeString(script, """
                const readline = require('readline');
                const rl = readline.createInterface({ input: process.stdin });
                rl.on('line', (line) => {
                    const req = JSON.parse(line);
                    const resp = {jsonrpc: '2.0', id: req.id, result: {tools: [{name: 'test_tool'}]}};
                    process.stdout.write(JSON.stringify(resp) + '\\n');
                });
                """);

        ProviderManager pm = new ProviderManager(tempDir.toString(),
                List.of(new ProviderManager.GroupConfig(
                        "test", "test/index.js", null,
                        new String[]{"test_tool"}, "builtin_hosted")));
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        pm.setHealthTracker(tracker);

        try {
            pm.startAll();
            // Give adapter process time to start and respond to tools/list
            Thread.sleep(1000);
            assertTrue(pm.isRunning("test"));
            // Use group-level health check (tool-level requires CapabilityRegistry wiring)
            assertEquals("running", tracker.getGroupHealth("test"));
        } finally {
            pm.stopAll();
        }
        assertFalse(pm.isRunning("test"));
    }
}
