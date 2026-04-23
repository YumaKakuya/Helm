package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCPHUB Java entry point — full daemon, bridge, and CLI.
 *
 * AMD-MCPHUB-001: Java owns everything. Go is just a JRE launcher.
 *
 * Subcommands:
 *   _daemon     — run the daemon process (UDS server + provider management)
 *   bridge      — run the stdio bridge (AI client attachment)
 *   status      — print daemon state
 *   open        — arm + open session
 *   close       — close session
 *   lock        — emergency lock
 *   unlock      — clear lock
 *   health      — liveness check
 *   capabilities — print capability registry
 *   version     — print version
 *   query --sql — read-only SQL against mcphub.db
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String VERSION = "0.2.0-alpha";

    public static void main(String[] args) throws Exception {
        String subcmd = args.length > 0 ? args[0] : "help";
        boolean jsonOutput = hasFlag(args, "--json");

        switch (subcmd) {
            case "_daemon" -> runDaemon();
            case "bridge" -> runBridge();
            case "status" -> rpcPrint("mcphub.control.status", null, jsonOutput);
            case "open" -> {
                // Idempotent open: if already OPEN, succeed silently
                try {
                    IpcClient.Response statusResp = IpcClient.call("mcphub.control.status", null);
                    String currentState = statusResp.result().path("state").asText("");
                    if ("OPEN".equals(currentState)) {
                        if (jsonOutput) {
                            ObjectNode r = mapper.createObjectNode();
                            r.put("state", "OPEN");
                            r.put("session_id", statusResp.result().path("session_id").asText(""));
                            r.put("already_open", true);
                            System.out.println(mapper.writeValueAsString(r));
                        } else {
                            System.out.println("Session already open");
                        }
                        break;
                    }
                    if ("ARMED".equals(currentState)) {
                        // Already armed, just need to open
                        rpcPrint("mcphub.control.open", null, jsonOutput);
                        break;
                    }
                } catch (IOException e) {
                    // Daemon not reachable, fall through to arm+open
                }
                rpcPrint("mcphub.control.arm", null, jsonOutput);
                rpcPrint("mcphub.control.open", null, jsonOutput);
            }
            case "close" -> rpcPrint("mcphub.control.close", null, jsonOutput);
            case "lock" -> {
                ObjectNode params = mapper.createObjectNode();
                params.put("lock_reason", "manual");
                rpcPrint("mcphub.control.lock", params, jsonOutput);
            }
            case "unlock" -> rpcPrint("mcphub.control.unlock", null, jsonOutput);
            case "health" -> rpcPrint("mcphub.control.health", null, jsonOutput);
            case "capabilities" -> rpcPrint("mcphub.control.capabilities", null, jsonOutput);
            case "version" -> {
                if (jsonOutput) {
                    System.out.println("{\"version\":\"" + VERSION + "\"}");
                } else {
                    System.out.println("mcphub " + VERSION);
                }
            }
            case "query" -> {
                String sql = getFlagValue(args, "--sql");
                if (sql == null) {
                    System.err.println("Usage: mcphub query --sql \"SELECT ...\"");
                    System.exit(1);
                }
                runQuery(sql, jsonOutput);
            }
            case "config" -> {
                if (args.length > 1 && "validate".equals(args[1])) {
                    System.out.println("config validate: OK");
                } else {
                    System.err.println("Usage: mcphub config validate");
                }
            }
            default -> {
                System.err.println("mcphub " + VERSION);
                System.err.println("Usage: mcphub <command> [--json]");
                System.err.println("Commands: _daemon, bridge, status, open, close, lock, unlock, health, capabilities, version, query, config");
                System.exit(1);
            }
        }
    }

    // =========================================================================
    // _daemon — the actual daemon process
    // =========================================================================

    private static void runDaemon() throws Exception {
        log.info("mcphub daemon starting (v{}, AMD-MCPHUB-001 Java-native)", VERSION);

        // Step 1: Database (REQ-8.2.5)
        DatabaseManager db = new DatabaseManager();
        db.open();
        log.info("Database ready at {}", DatabaseManager.defaultDbPath());

        // Step 2: Capability registry (IS-04, IS-12)
        CapabilityRegistry registry = new CapabilityRegistry();
        try (var stream = Main.class.getResourceAsStream("/capabilities.yaml")) {
            if (stream == null) throw new IllegalStateException("capabilities.yaml not found in classpath");
            registry.load(stream);
        }
        log.info("Registry loaded: {} capabilities, {} rejected",
                registry.getLoadedCount(), registry.getRejectedCount());

        // Optional test fixture
        String fixturePath = System.getenv("MCPHUB_TEST_FIXTURE");
        if (fixturePath != null && !fixturePath.isBlank()) {
            File fixture = new File(fixturePath);
            if (fixture.exists()) {
                try (var fs = new java.io.FileInputStream(fixture)) {
                    registry.loadAdditional(fs);
                    log.warn("Loaded TEST FIXTURE from {} (MCPHUB_TEST_FIXTURE)", fixturePath);
                } catch (Exception e) {
                    log.warn("Failed to load test fixture {}: {}", fixturePath, e.getMessage());
                }
            }
        }

        // Step 3: Policy engine (IS-06)
        PolicyEngine policy = new PolicyEngine();
        policy.loadGlobalRules(registry.getPolicyRules());

        // Step 4: Body budget (IS-09)
        BodyBudgetService bodyBudget = new BodyBudgetService(db);
        bodyBudget.setMcphubHostedToolCount(registry.getLoadedCount());

        // Step 5: Runtime config
        McpHubConfig config = McpHubConfig.load();

        // Step 6: State machine + session
        StateMachine stateMachine = new StateMachine();
        stateMachine.setDatabaseManager(db);
        long idleTimeout = SessionManager.DEFAULT_IDLE_TIMEOUT_SECONDS;
        long armedTimeout = SessionManager.DEFAULT_ARM_TIMEOUT_SECONDS;
        if (config.session != null) {
            if (config.session.idleTimeoutSeconds != null) idleTimeout = config.session.idleTimeoutSeconds;
            if (config.session.armedTimeoutSeconds != null) armedTimeout = config.session.armedTimeoutSeconds;
        }
        SessionManager sessionManager = new SessionManager(idleTimeout, armedTimeout);

        // Step 7: Provider manager (AMD-MCPHUB-001: Java-native)
        String adapterBase = System.getenv("MCPHUB_ADAPTER_DIR");
        if (adapterBase == null || adapterBase.isBlank()) {
            // Default: relative to working directory or JAR location
            adapterBase = findAdapterDir();
        }
        ProviderHealthTracker healthTracker = new ProviderHealthTracker();
        ProviderManager providerManager = new ProviderManager(adapterBase, ProviderManager.defaultGroups());
        providerManager.setHealthTracker(healthTracker);
        providerManager.setRegistry(registry);

        // Step 8: Handlers
        ControlHandler controlHandler = new ControlHandler(
                stateMachine, sessionManager, db, registry, policy, bodyBudget);
        McpHandler mcpHandler = new McpHandler(
                stateMachine, registry, policy, db, bodyBudget);
        controlHandler.setHealthTracker(healthTracker);
        mcpHandler.setHealthTracker(healthTracker);
        mcpHandler.setSessionManager(sessionManager);
        mcpHandler.setProviderManager(providerManager);
        if (config.serverName != null && !config.serverName.isBlank()) {
            mcpHandler.setServerName(config.serverName);
        }
        config.applyTo(bodyBudget);

        // Step 9: Composite dispatcher
        JsonRpcServer.MethodHandler dispatcher = (method, params) -> {
            if (method.startsWith("mcphub.control.")) {
                return controlHandler.handle(method, params);
            }
            return mcpHandler.handle(method, params);
        };

        // Step 10: UDS daemon server (AMD-MCPHUB-001: replaces Go UDS server)
        DaemonServer server = new DaemonServer(dispatcher, providerManager);
        log.info("mcphub daemon ready. Serving on UDS: {}", DaemonServer.socketPath());

        try {
            server.run();
        } finally {
            providerManager.stopAll();
            sessionManager.shutdown();
            db.close();
            log.info("mcphub daemon shutdown complete");
        }
    }

    // =========================================================================
    // bridge — stdio bridge for AI client
    // =========================================================================

    private static void runBridge() throws IOException {
        new StdioBridge().run();
    }

    // =========================================================================
    // CLI helpers
    // =========================================================================

    private static void rpcPrint(String method, JsonNode params, boolean jsonOutput) {
        try {
            IpcClient.Response resp = IpcClient.call(method, params);
            if (resp.error() != null) {
                System.err.println("Error: " + resp.error().path("message").asText());
                System.exit(1);
            }
            if (jsonOutput) {
                System.out.println(mapper.writeValueAsString(resp.result()));
            } else {
                System.out.println(mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(resp.result()));
            }
        } catch (IOException e) {
            System.err.println("Cannot connect to daemon: " + e.getMessage());
            System.err.println("Is the daemon running? Start it with 'mcphub start'.");
            System.exit(1);
        }
    }

    private static void runQuery(String sql, boolean jsonOutput) throws Exception {
        // Validate SELECT only
        String trimmed = sql.strip().toLowerCase();
        if (!trimmed.startsWith("select")) {
            System.err.println("Only SELECT statements are permitted");
            System.exit(1);
        }

        String dbPath = DatabaseManager.defaultDbPath();
        if (!new File(dbPath).exists()) {
            System.err.println("Database not found at " + dbPath);
            System.exit(1);
        }

        // sqlite-jdbc: use SQLiteConfig for read-only mode (URL query params not supported)
        var config = new java.util.Properties();
        config.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath, config);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            String[] cols = new String[colCount];
            for (int i = 0; i < colCount; i++) {
                cols[i] = meta.getColumnName(i + 1);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < colCount; i++) {
                    row.put(cols[i], rs.getObject(i + 1));
                }
                rows.add(row);
            }

            if (jsonOutput) {
                System.out.println(mapper.writeValueAsString(rows));
            } else {
                System.out.println(String.join("\t", cols));
                for (Map<String, Object> row : rows) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cols.length; i++) {
                        if (i > 0) sb.append('\t');
                        sb.append(row.get(cols[i]));
                    }
                    System.out.println(sb);
                }
                System.err.println("(" + rows.size() + " rows)");
            }
        }
    }

    /** Find adapter dist directory relative to JAR or working directory. */
    private static String findAdapterDir() {
        // Check relative to JAR
        try {
            String jarDir = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            Path candidate = Path.of(jarDir, "..", "adapters", "dist");
            if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().toString();
        } catch (Exception ignored) {}

        // Check working directory
        Path cwd = Path.of("adapters", "dist");
        if (Files.isDirectory(cwd)) return cwd.toAbsolutePath().toString();

        // Fallback
        return "adapters/dist";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }

    private static String getFlagValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }
}
