package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * MCP stdio bridge: reads JSON-RPC from stdin (AI client), forwards to daemon UDS,
 * relays response back to stdout.
 *
 * Ported from Go bridge.go — now Java owns the bridge directly.
 *
 * AMD-MCPHUB-001: Go daemon layer scrap.
 *
 * REQ-2.3.1: stdio JSON-RPC 2.0
 * REQ-2.3.2: forward tools/call and tools/list to daemon
 * REQ-2.3.3: bridge MUST NOT perform routing/policy
 * REQ-2.3.4: daemon-unreachable error
 * REQ-2.3.5: stdin close → session-detach
 */
public class StdioBridge {
    private static final Logger log = LoggerFactory.getLogger(StdioBridge.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Run the bridge loop. Blocks until stdin is closed.
     */
    public void run() throws IOException {
        BufferedReader stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter stdoutWriter = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        log.info("mcphub bridge started");

        String line;
        while ((line = stdinReader.readLine()) != null) {
            if (line.isBlank()) continue;

            JsonNode req;
            try {
                req = mapper.readTree(line);
            } catch (Exception e) {
                writeParseError(stdoutWriter, null, e.getMessage());
                continue;
            }

            JsonNode id = req.get("id");

            // Forward to daemon via UDS
            String response = forwardToDaemon(line);
            if (response != null) {
                stdoutWriter.println(response);
                stdoutWriter.flush();
            } else {
                // REQ-2.3.4: daemon unreachable
                writeError(stdoutWriter, id, -32000,
                        "MCPHUB daemon is not running. Start it with 'mcphub start'.");
            }
        }

        // REQ-2.3.5: stdin closed → bridge detaching (session stays open)
        log.info("stdin closed, bridge detaching");
        sendDetach();
    }

    /**
     * Forward a raw JSON-RPC request to the daemon and return the response.
     * Each request opens a new UDS connection (matches Go bridge behavior).
     */
    private String forwardToDaemon(String requestLine) {
        try {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(DaemonServer.socketPath());
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

                writer.write(requestLine);
                writer.newLine();
                writer.flush();

                return reader.readLine();
            }
        } catch (IOException e) {
            log.warn("Failed to connect to daemon: {}", e.getMessage());
            return null;
        }
    }

    /** Send session-detach notification to daemon (best-effort). */
    private void sendDetach() {
        // Notify daemon so it can track last-bridge-exit and auto-close.
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "mcphub.control.bridge_detach");
        req.set("params", mapper.createObjectNode());
        try {
            String response = forwardToDaemon(mapper.writeValueAsString(req));
            if (response == null) {
                log.warn("bridge_detach notification failed: daemon unreachable");
            }
        } catch (Exception e) {
            log.warn("bridge_detach notification failed: {}", e.getMessage());
        }
        log.info("Bridge detaching (session kept open for other bridges)");
    }

    private void writeParseError(PrintWriter writer, JsonNode id, String message) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id != null ? id : mapper.nullNode());
            ObjectNode error = mapper.createObjectNode();
            error.put("code", -32700);
            error.put("message", "Parse error: " + message);
            resp.set("error", error);
            writer.println(mapper.writeValueAsString(resp));
            writer.flush();
        } catch (Exception e) {
            log.error("Failed to write parse error response", e);
        }
    }

    private void writeError(PrintWriter writer, JsonNode id, int code, String message) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id != null ? id : mapper.nullNode());
            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            resp.set("error", error);
            writer.println(mapper.writeValueAsString(resp));
            writer.flush();
        } catch (Exception e) {
            log.error("Failed to write error response", e);
        }
    }
}
