package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IpcClient (AMD-MCPHUB-001: Java-native IPC client).
 */
class IpcClientTest {

    @Test
    void call_throwsWhenDaemonNotRunning() {
        // Use a unique socket path that cannot have a daemon listening,
        // to avoid false negatives when another daemon happens to be
        // running on the default path.
        Path nonExistent = Path.of(
                System.getProperty("java.io.tmpdir"),
                "mcphub-test-no-daemon-" + System.nanoTime() + ".sock");
        assertThrows(IOException.class, () ->
                IpcClient.call("mcphub.control.health", null, nonExistent));
    }

    @Test
    void responseRecord_holdsValues() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = mapper.createObjectNode();
        result.put("status", "ok");

        var resp = new IpcClient.Response(result, null);
        assertNotNull(resp.result());
        assertNull(resp.error());
        assertEquals("ok", resp.result().path("status").asText());
    }
}
