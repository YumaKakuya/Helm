package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StdioBridge (AMD-MCPHUB-001: Java-native bridge).
 *
 * The bridge is a thin relay (REQ-2.3.3: MUST NOT perform routing/policy).
 * Full end-to-end tests require a running daemon; these verify construction.
 */
class StdioBridgeTest {

    @Test
    void bridgeCanBeInstantiated() {
        // Basic construction test — bridge is a simple relay
        StdioBridge bridge = new StdioBridge();
        assertNotNull(bridge);
    }

    @Test
    void daemonUnavailableMessageIncludesDefaultStartAndOpenRecovery() {
        String command = StdioBridge.cliBaseCommand(Map.of());
        String message = StdioBridge.daemonUnavailableMessage(command);

        assertEquals("mcphub", command);
        assertTrue(message.contains("cli_recovery_start_command: mcphub start"));
        assertTrue(message.contains("cli_recovery_open_command: mcphub open"));
    }

    @Test
    void daemonUnavailableMessageUsesProvidedCliCommand() {
        String command = StdioBridge.cliBaseCommand(Map.of("MCPHUB_CLI_COMMAND", "/tmp/mcphub-bin"));
        String message = StdioBridge.daemonUnavailableMessage(command);

        assertEquals("/tmp/mcphub-bin", command);
        assertTrue(message.contains("cli_recovery_start_command: /tmp/mcphub-bin start"));
        assertTrue(message.contains("cli_recovery_open_command: /tmp/mcphub-bin open"));
    }
}
