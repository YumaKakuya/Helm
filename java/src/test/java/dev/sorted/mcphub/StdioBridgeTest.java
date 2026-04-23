package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

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
}
