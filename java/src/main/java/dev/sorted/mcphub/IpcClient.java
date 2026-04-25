package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * IPC client for sending JSON-RPC requests to the daemon over UDS.
 * Used by CLI subcommands (status, open, close, lock, unlock, health, capabilities).
 *
 * Ported from Go ipc/client.go — now Java owns the CLI client.
 *
 * AMD-MCPHUB-001: Go daemon layer scrap.
 *
 * REQ-2.5.1: UDS on Linux/macOS
 * REQ-2.5.2: JSON-RPC 2.0 over IPC
 * REQ-2.6.2: control methods callable from CLI
 */
public class IpcClient {
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Response from a JSON-RPC call. */
    public record Response(JsonNode result, JsonNode error) {}

    /**
     * Send a JSON-RPC request to the daemon and return the response.
     * @throws IOException if the daemon is not running or connection fails
     */
    public static Response call(String method, JsonNode params) throws IOException {
        return call(method, params, DaemonServer.socketPath());
    }

    /**
     * Send a JSON-RPC request to the daemon at the specified socket path.
     * Package-private for testability.
     * @throws IOException if the daemon is not running or connection fails
     */
    static Response call(String method, JsonNode params, java.nio.file.Path socketPath) throws IOException {
        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(addr);

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                            java.nio.channels.Channels.newOutputStream(channel),
                            StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            java.nio.channels.Channels.newInputStream(channel),
                            StandardCharsets.UTF_8));

            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", 1);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }

            writer.write(mapper.writeValueAsString(req));
            writer.newLine();
            writer.flush();

            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Daemon closed connection without response");
            }

            JsonNode resp = mapper.readTree(line);
            JsonNode result = resp.get("result");
            JsonNode error = resp.get("error");
            return new Response(result, (error != null && !error.isNull()) ? error : null);
        }
    }
}
