package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unix domain socket daemon server.
 * Ported from Go server.go — now Java owns the daemon process directly.
 *
 * AMD-MCPHUB-001: Go daemon layer scrap. Java is the daemon.
 *
 * REQ-2.5.1: UDS on Linux/macOS
 * REQ-2.5.2: JSON-RPC 2.0 over IPC
 * REQ-2.5.5: local-only (no TCP/UDP)
 * REQ-2.10.1: socket restricted to owning user
 * REQ-2.10.2: no TCP/UDP ports
 */
public class DaemonServer {
    private static final Logger log = LoggerFactory.getLogger(DaemonServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JsonRpcServer.MethodHandler handler;
    private final ProviderManager providerManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocketChannel serverChannel;

    public DaemonServer(JsonRpcServer.MethodHandler handler, ProviderManager providerManager) {
        this.handler = handler;
        this.providerManager = providerManager;
    }

    /** Get the UDS socket path. Mirrors Go ipc.SocketPath(). */
    public static Path socketPath() {
        // Allow override for integration tests
        String override = System.getenv("MCPHUB_SOCKET_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (runtimeDir == null || runtimeDir.isBlank()) {
            runtimeDir = System.getProperty("java.io.tmpdir");
        }
        return Path.of(runtimeDir, "mcphub", "daemon.sock");
    }

    /** Get the PID file path. */
    public static Path pidFilePath() {
        String dataDir = System.getenv("MCPHUB_DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = System.getProperty("user.home") + "/.local/share/mcphub";
        }
        return Path.of(dataDir, "mcphub.pid");
    }

    /**
     * Run the daemon server. Blocks until shutdown.
     * REQ-2.3.6: lifecycle management
     * REQ-2.6.1: control surface over UDS
     */
    public void run() throws IOException {
        Path sockPath = socketPath();
        Files.createDirectories(sockPath.getParent());

        // Remove stale socket
        Files.deleteIfExists(sockPath);

        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(sockPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(addr);
        running.set(true);

        // REQ-2.10.1: restrict permissions
        try {
            Files.setPosixFilePermissions(sockPath,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows — skip POSIX permissions
        }

        // Write PID file (REQ-2.3.10)
        Path pidPath = pidFilePath();
        Files.createDirectories(pidPath.getParent());
        Files.writeString(pidPath, String.valueOf(ProcessHandle.current().pid()));

        log.info("mcphub daemon ready (pid {}, socket {})",
                ProcessHandle.current().pid(), sockPath);

        // Signal handling for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            shutdown();
        }, "mcphub-shutdown"));

        try {
            while (running.get()) {
                try {
                    SocketChannel client = serverChannel.accept();
                    if (client != null) {
                        Thread.ofVirtual().name("ipc-handler").start(() -> handleClient(client));
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.warn("Accept error: {}", e.getMessage());
                    }
                    // else: shutdown, expected
                }
            }
        } finally {
            cleanup();
        }
    }

    /** Graceful shutdown. */
    public void shutdown() {
        running.set(false);
        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server channel: {}", e.getMessage());
        }
    }

    private void cleanup() {
        // Remove PID file
        try { Files.deleteIfExists(pidFilePath()); } catch (IOException ignored) {}
        // Remove socket
        try { Files.deleteIfExists(socketPath()); } catch (IOException ignored) {}
    }

    private void handleClient(SocketChannel channel) {
        try (channel) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(java.nio.channels.Channels.newInputStream(channel),
                            StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel),
                            StandardCharsets.UTF_8));

            String line = reader.readLine();
            if (line == null || line.isBlank()) return;

            JsonNode req;
            try {
                req = mapper.readTree(line);
            } catch (Exception e) {
                writeError(writer, null, JsonRpcServer.ERR_PARSE, "Parse error: " + e.getMessage());
                return;
            }

            JsonNode id = req.get("id");
            String method = req.path("method").asText(null);
            JsonNode params = req.path("params");

            if (method == null) {
                writeError(writer, id, JsonRpcServer.ERR_INVALID_REQ, "Missing method");
                return;
            }

            try {
                JsonNode result = handler.handle(method, params);

                // Check for provider lifecycle directives from ControlHandler
                if (result instanceof ObjectNode resultObj) {
                    if (resultObj.has("mcphub_providers")) {
                        String directive = resultObj.get("mcphub_providers").asText();
                        resultObj.remove("mcphub_providers");
                        switch (directive) {
                            case "start" -> {
                                if (providerManager != null) providerManager.startAll();
                            }
                            case "stop" -> {
                                if (providerManager != null) providerManager.stopAll();
                            }
                        }
                    }

                    // Handle route decision — Java now dispatches directly
                    if (resultObj.has("mcphub_route")) {
                        JsonNode routeInfo = resultObj.get("mcphub_route");
                        String groupId = routeInfo.path("group_id").asText("");
                        String forwardMethod = resultObj.path("forward_method").asText("");
                        JsonNode forwardParams = resultObj.path("forward_params");

                        if (!groupId.isEmpty() && !forwardMethod.isEmpty() && providerManager != null) {
                            try {
                                JsonNode providerResult = providerManager.call(groupId, forwardMethod, forwardParams);
                                writeSuccess(writer, id, providerResult);
                            } catch (Exception e) {
                                writeError(writer, id, JsonRpcServer.ERR_INTERNAL,
                                        "Provider '" + groupId + "' error: " + e.getMessage());
                            }
                            return;
                        }
                    }
                }

                writeSuccess(writer, id, result);
            } catch (JsonRpcServer.JsonRpcException e) {
                writeError(writer, id, e.getCode(), e.getMessage());
            } catch (Exception e) {
                log.error("Internal error handling request", e);
                writeError(writer, id, JsonRpcServer.ERR_INTERNAL, "Internal error: " + e.getMessage());
            }
        } catch (IOException e) {
            log.debug("Client connection error: {}", e.getMessage());
        }
    }

    private void writeSuccess(BufferedWriter writer, JsonNode id, JsonNode result) throws IOException {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id != null ? id : mapper.nullNode());
        resp.set("result", result);
        writer.write(mapper.writeValueAsString(resp));
        writer.newLine();
        writer.flush();
    }

    private void writeError(BufferedWriter writer, JsonNode id, int code, String message) throws IOException {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id != null ? id : mapper.nullNode());
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        resp.set("error", error);
        writer.write(mapper.writeValueAsString(resp));
        writer.newLine();
        writer.flush();
    }
}
