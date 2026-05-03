package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages hosted-provider and relay-provider subprocess lifecycle.
 * Ported from Go provider.go — now Java owns both judgment AND delivery.
 *
 * AMD-MCPHUB-001: Go daemon layer scrap. Java directly manages provider processes.
 *
 * REQ-6.2.1: subprocess-bridge model (stdio JSON-RPC)
 * REQ-6.2.3: Java decides AND executes provider lifecycle
 * REQ-6.2.7: tools MAY be grouped
 * REQ-6.2.8: each group independently restartable
 * REQ-6.7.3: graceful shutdown (SIGTERM + wait + SIGKILL)
 * REQ-6.7.5: crash recovery with bounded retry (max 3 in 60s)
 */
public class ProviderManager {
    private static final Logger log = LoggerFactory.getLogger(ProviderManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long CALL_TIMEOUT_MS = 35_000;

    /** Static configuration for one adapter group. */
    public record GroupConfig(
            String id,
            String script,        // relative to adapters/dist/
            String[] command,     // direct command invocation (overrides script)
            String[] defaultTools,
            String providerType   // "builtin_hosted" or "relay"
    ) {}

    /** A running provider subprocess. */
    private static class ProviderProcess {
        final Process process;
        final BufferedWriter stdin;
        final BufferedReader stdout;
        final AtomicInteger reqId = new AtomicInteger(1);

        ProviderProcess(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private final ConcurrentHashMap<String, ProviderProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> groupTools = new ConcurrentHashMap<>();
    private final String adapterBase;
    private final List<GroupConfig> groups;
    private ProviderHealthTracker healthTracker;
    private CapabilityRegistry registry;

    public ProviderManager(String adapterBase, List<GroupConfig> groups) {
        this.adapterBase = adapterBase;
        this.groups = groups;
    }

    public void setHealthTracker(ProviderHealthTracker healthTracker) {
        this.healthTracker = healthTracker;
    }

    public void setRegistry(CapabilityRegistry registry) {
        this.registry = registry;
    }

    /** Get the default group configurations for alpha. */
    public static List<GroupConfig> defaultGroups() {
        return List.of(
            new GroupConfig("web", "web/index.js", null,
                    new String[]{"webfetch", "websearch"}, "builtin_hosted"),
            new GroupConfig("edit", "edit/index.js", null,
                    new String[]{"apply_patch"}, "builtin_hosted"),
            new GroupConfig("project", "project/index.js", null,
                    new String[]{"todowrite", "list", "codesearch", "lsp", "task_create", "task_list", "task_update", "task_delete", "mcphub_checkpoint"}, "builtin_hosted"),
            new GroupConfig("session", "session/index.js", null,
                    new String[]{"plan_enter", "plan_exit", "skill", "batch"}, "builtin_hosted"),
            new GroupConfig("synthetic", "synthetic/index.js", null,
                    new String[]{"synthetic_delay"}, "builtin_hosted")
        );
    }

    /** Resolve group ID from tool name. */
    public String resolveGroupId(String toolName) {
        // Check runtime tool mappings first
        for (var entry : groupTools.entrySet()) {
            if (entry.getValue().contains(toolName)) {
                return entry.getKey();
            }
        }
        // Fallback to static config
        for (GroupConfig g : groups) {
            for (String t : g.defaultTools) {
                if (t.equals(toolName)) return g.id;
            }
        }
        return "unknown";
    }

    /** Lookup the providerType for a given group ID. */
    public String getProviderType(String groupId) {
        for (GroupConfig g : groups) {
            if (g.id.equals(groupId)) {
                return g.providerType;
            }
        }
        return "unknown";
    }

    /**
     * Load relay provider groups from an external YAML config file.
     * Format:
     * relays:
     *   - id: "my-relay"
     *     command: ["/path/to/binary", "arg1"]
     *     tools: ["tool_a", "tool_b"]
     *
     * @return list of loaded GroupConfig entries with providerType "relay"
     */
    public static List<GroupConfig> loadRelays(java.nio.file.Path configPath) {
        List<GroupConfig> result = new ArrayList<>();
        if (!Files.exists(configPath)) {
            return result;
        }
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.findAndRegisterModules();
            JsonNode root = yamlMapper.readTree(configPath.toFile());
            JsonNode relays = root.path("relays");
            if (relays.isArray()) {
                for (JsonNode relay : relays) {
                    String id = relay.path("id").asText(null);
                    JsonNode cmdNode = relay.get("command");
                    List<String> cmdList = new ArrayList<>();
                    if (cmdNode != null && cmdNode.isArray()) {
                        for (JsonNode c : cmdNode) {
                            cmdList.add(c.asText());
                        }
                    }
                    JsonNode toolsNode = relay.get("tools");
                    List<String> toolsList = new ArrayList<>();
                    if (toolsNode != null && toolsNode.isArray()) {
                        for (JsonNode t : toolsNode) {
                            toolsList.add(t.asText());
                        }
                    }
                    if (id != null && !id.isBlank() && !cmdList.isEmpty()) {
                        result.add(new GroupConfig(id, null,
                                cmdList.toArray(new String[0]),
                                toolsList.toArray(new String[0]), "relay"));
                    } else {
                        log.warn("Skipping invalid relay entry: missing id or command");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load relay config from {}: {}", configPath, e.getMessage());
        }
        return result;
    }

    /** Start all provider groups. Called on session Open. */
    public void startAll() {
        for (GroupConfig g : groups) {
            try {
                start(g);
                reportHealth(g.id, "running");
                confirmWithRegistry(g);
            } catch (Exception e) {
                log.error("Failed to start provider group '{}': {}", g.id, e.getMessage());
                reportHealth(g.id, "unavailable");
            }
        }
    }

    /** Stop all provider groups gracefully. REQ-6.7.3 */
    public void stopAll() {
        List<String> ids = new ArrayList<>(processes.keySet());
        for (String id : ids) {
            reportHealth(id, "stopped");
        }

        // Phase 1: destroy (sends SIGTERM on Unix)
        Map<String, ProviderProcess> snapshot = new HashMap<>(processes);
        processes.clear();

        for (var entry : snapshot.entrySet()) {
            Process p = entry.getValue().process;
            if (p.isAlive()) {
                p.destroy();
            }
        }

        // Phase 2: wait up to 3 seconds
        for (var entry : snapshot.entrySet()) {
            Process p = entry.getValue().process;
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Send a JSON-RPC request to a provider group and return the result.
     * Used for tools/call dispatch and tools/list queries.
     */
    public synchronized JsonNode call(String groupId, String method, JsonNode params) throws Exception {
        ProviderProcess proc = processes.get(groupId);
        if (proc == null) {
            throw new IllegalStateException("Provider group '" + groupId + "' not running");
        }

        synchronized (proc) {
            int id = proc.reqId.getAndIncrement();
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }

            String reqLine = mapper.writeValueAsString(req);
            proc.stdin.write(reqLine);
            proc.stdin.newLine();
            proc.stdin.flush();

            // Read response with timeout via a virtual thread
            var futureResult = new java.util.concurrent.CompletableFuture<JsonNode>();
            Thread.ofVirtual().start(() -> {
                try {
                    String line = proc.stdout.readLine();
                    if (line == null) {
                        futureResult.completeExceptionally(
                                new IOException("Provider '" + groupId + "' closed stdout"));
                        return;
                    }
                    JsonNode resp = mapper.readTree(line);
                    if (resp.has("error") && !resp.get("error").isNull()) {
                        JsonNode err = resp.get("error");
                        futureResult.completeExceptionally(
                                new IOException("Provider error " + err.path("code").asInt()
                                        + ": " + err.path("message").asText()));
                        return;
                    }
                    futureResult.complete(resp.get("result"));
                } catch (Exception e) {
                    futureResult.completeExceptionally(e);
                }
            });

            try {
                return futureResult.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                restartBrokenProvider(groupId);
                throw new IOException("Provider '" + groupId + "' call timed out after " + CALL_TIMEOUT_MS + "ms");
            } catch (java.util.concurrent.ExecutionException e) {
                String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (causeMsg != null && causeMsg.contains("closed stdout")) {
                    restartBrokenProvider(groupId);
                }
                throw new IOException("Provider '" + groupId + "' call failed: " + causeMsg);
            }
        }
    }

    /** Check if a group is running. */
    public boolean isRunning(String groupId) {
        ProviderProcess proc = processes.get(groupId);
        return proc != null && proc.process.isAlive();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /** Kill and restart a provider whose stdin/stdout sync may be broken
     *  (e.g., after a call timeout or stdout close). */
    private void restartBrokenProvider(String groupId) {
        ProviderProcess broken = processes.remove(groupId);
        if (broken != null) {
            broken.process.destroy();
            log.warn("Provider group '{}' call failed — destroying and restarting", groupId);
            for (GroupConfig g : groups) {
                if (g.id.equals(groupId)) {
                    try {
                        start(g);
                        reportHealth(groupId, "running");
                        confirmWithRegistry(g);
                    } catch (Exception restartEx) {
                        log.error("Failed to restart provider group '{}' after failure: {}", groupId, restartEx.getMessage());
                        reportHealth(groupId, "unavailable");
                    }
                    break;
                }
            }
        }
    }

    private void start(GroupConfig cfg) throws IOException {
        // Prevent orphan processes: skip if group already has a running process
        ProviderProcess existing = processes.get(cfg.id);
        if (existing != null && existing.process.isAlive()) {
            log.info("Provider group '{}' already running (pid {}), skipping", cfg.id, existing.process.pid());
            return;
        }
        // Clean up dead process entry if present
        if (existing != null) {
            log.info("Provider group '{}' had dead process, cleaning up", cfg.id);
            processes.remove(cfg.id);
        }

        ProcessBuilder pb;
        if (cfg.command != null && cfg.command.length > 0) {
            File binary = new File(cfg.command[0]);
            if (!binary.exists()) {
                throw new IOException("Relay binary not found: " + cfg.command[0]);
            }
            pb = new ProcessBuilder(cfg.command);
        } else {
            String scriptPath = adapterBase + "/" + cfg.script;
            File script = new File(scriptPath);
            if (!script.exists()) {
                throw new IOException("Adapter script not found: " + scriptPath);
            }
            pb = new ProcessBuilder("node", scriptPath);
        }
        pb.redirectErrorStream(false);
        // Inherit stderr so adapter logs appear in daemon stderr
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        ProviderProcess proc = new ProviderProcess(process);
        processes.put(cfg.id, proc);
        groupTools.put(cfg.id, new ArrayList<>(Arrays.asList(cfg.defaultTools)));

        log.info("Started provider group '{}' (pid {})", cfg.id, process.pid());

        // Watch for unexpected exit and restart (REQ-6.7.5)
        Thread.ofVirtual().name("provider-watch-" + cfg.id).start(() -> watchAndRestart(cfg));
    }

    private void watchAndRestart(GroupConfig cfg) {
        int retries = 0;
        while (retries < MAX_RESTART_ATTEMPTS) {
            ProviderProcess proc = processes.get(cfg.id);
            if (proc == null) return; // intentionally stopped

            try {
                proc.process.waitFor();
            } catch (InterruptedException e) {
                return;
            }

            // Check if we intentionally stopped it
            if (!processes.containsKey(cfg.id)) return;

            reportHealth(cfg.id, "unavailable");
            processes.remove(cfg.id);

            retries++;
            log.warn("Provider group '{}' exited unexpectedly. Restart attempt {}/{}",
                    cfg.id, retries, MAX_RESTART_ATTEMPTS);

            try {
                Thread.sleep(retries * 500L);
                start(cfg);
                reportHealth(cfg.id, "running");
                confirmWithRegistry(cfg);
                return; // successful restart
            } catch (Exception e) {
                log.error("Restart failed for '{}': {}", cfg.id, e.getMessage());
            }
        }

        log.error("Provider group '{}' failed {} times, not restarting", cfg.id, retries);
        reportHealth(cfg.id, "stopped");
    }

    /** Query adapter tools/list and register with capability registry. REQ-6.2.6 */
    private void confirmWithRegistry(GroupConfig cfg) {
        try {
            JsonNode result = call(cfg.id, "tools/list", null);
            JsonNode toolsNode = result.path("tools");
            List<String> names = new ArrayList<>();
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    String name = t.has("name") ? t.get("name").asText() : null;
                    if (name != null) names.add(name);
                }
            }
            groupTools.put(cfg.id, names);

            // Register with capability registry
            if (registry != null) {
                registry.confirmTools(cfg.id, new HashSet<>(names));
            }
            log.info("Provider group '{}' confirmed {} tools: {}", cfg.id, names.size(), names);
        } catch (Exception e) {
            log.warn("Provider group '{}' tools/list failed: {}", cfg.id, e.getMessage());
        }
    }

    private void reportHealth(String groupId, String status) {
        if (healthTracker != null) {
            healthTracker.updateGroup(groupId, status);
        }
    }
}
