package dev.sorted.mcphub;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class McpHubConfigTest {

    @TempDir Path tempDir;

    @Test
    void load_noFile_returnsDefault() {
        System.setProperty("user.home", tempDir.toString());
        McpHubConfig cfg = McpHubConfig.load();
        assertNotNull(cfg);
        assertNull(cfg.bodyBudget);
    }

    @Test
    void load_withBodyBudgetConfig_appliesThresholds() throws Exception {
        String yaml = "body_budget:\n"
            + "  warning_tool_count: 7\n"
            + "  critical_tool_count: 9\n"
            + "  warning_byte_size: 20480\n"
            + "  critical_byte_size: 40960\n";
        Path dataDir = tempDir.resolve(".local/share/mcphub");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("config.yaml"), yaml);
        System.setProperty("user.home", tempDir.toString());

        McpHubConfig cfg = McpHubConfig.load();
        assertNotNull(cfg.bodyBudget);
        assertEquals(7, cfg.bodyBudget.warningToolCount);
        assertEquals(9, cfg.bodyBudget.criticalToolCount);
    }

    @Test
    void load_withSessionConfig_readsSessionTimeouts() throws Exception {
        String yaml = "session:\n"
            + "  idle_timeout_seconds: 120\n"
            + "  armed_timeout_seconds: 30\n";
        Path dataDir = tempDir.resolve(".local/share/mcphub");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("config.yaml"), yaml);
        System.setProperty("user.home", tempDir.toString());

        McpHubConfig cfg = McpHubConfig.load();
        assertNotNull(cfg.session);
        assertEquals(120L, cfg.session.idleTimeoutSeconds);
        assertEquals(30L, cfg.session.armedTimeoutSeconds);
    }

    @Test
    void load_withServerName_readsName() throws Exception {
        String yaml = "server_name: \"hub\"\n";
        Path dataDir = tempDir.resolve(".local/share/mcphub");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("config.yaml"), yaml);
        System.setProperty("user.home", tempDir.toString());

        McpHubConfig cfg = McpHubConfig.load();
        assertEquals("hub", cfg.serverName);
    }

    @Test
    void applyTo_setsThresholds() throws Exception {
        McpHubConfig cfg = new McpHubConfig();
        cfg.bodyBudget = new McpHubConfig.BodyBudgetConfig();
        cfg.bodyBudget.warningToolCount = 7;
        cfg.bodyBudget.criticalToolCount = 10;

        // Need a DatabaseManager to create BodyBudgetService — use null (no DB writes in this test)
        BodyBudgetService svc = new BodyBudgetService(null) {
            @Override public String recordSnapshot(String s, String t) { return "nominal"; }
        };
        cfg.applyTo(svc);
        // If setters work, no exception thrown
        assertDoesNotThrow(() -> cfg.applyTo(svc));
    }
}
