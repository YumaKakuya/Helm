package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DaemonServer (AMD-MCPHUB-001: Java-native daemon).
 * Full integration tests require daemon startup; these test static helpers.
 */
class DaemonServerTest {

    @Test
    void socketPath_isUnderMcphubDir() {
        Path p = DaemonServer.socketPath();
        assertNotNull(p);
        assertTrue(p.toString().contains("mcphub"));
        assertTrue(p.toString().endsWith("daemon.sock"));
    }

    @Test
    void pidFilePath_isUnderMcphubDir() {
        Path p = DaemonServer.pidFilePath();
        assertNotNull(p);
        assertTrue(p.toString().contains("mcphub"));
        assertTrue(p.toString().endsWith("mcphub.pid"));
    }
}
