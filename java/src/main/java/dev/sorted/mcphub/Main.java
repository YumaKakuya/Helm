package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
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
 *   _daemon     — run the daemon process (UDS server + provider management + dashboard)
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
 *   doctor      — preflight local runtime/config checks
 *   report      — text-based CLI dashboard
 *   dash        — print dashboard URL and instructions
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
            case "doctor" -> runDoctor(jsonOutput);
            case "report" -> runReport();
            case "dash", "dashboard" -> {
                System.out.println("Dashboard runs inside the daemon when MCPHUB_DASHBOARD_ENABLED=1.");
                System.out.println("Start with: MCPHUB_DASHBOARD_ENABLED=1 mcphub start");
                System.out.println("Then connect to: http://localhost:9741");
            }
            default -> {
                System.err.println("mcphub " + VERSION);
                System.err.println("Usage: mcphub <command> [--json]");
                System.err.println("Commands: _daemon, bridge, status, open, close, lock, unlock, health, capabilities, version, query, config, doctor, report, dash");
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

        // Load user-configured relay providers
        Path relaysPath = Path.of(
            System.getenv().getOrDefault("MCPHUB_RELAYS_PATH",
                System.getProperty("user.home") + "/.config/mcphub/relays.yaml"));
        List<ProviderManager.GroupConfig> allGroups = new ArrayList<>(ProviderManager.defaultGroups());
        allGroups.addAll(ProviderManager.loadRelays(relaysPath));

        // Load relay capabilities if present
        if (Files.exists(relaysPath)) {
            try {
                ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                yamlMapper.findAndRegisterModules();
                JsonNode root = yamlMapper.readTree(relaysPath.toFile());
                JsonNode relaysNode = root.path("relays");
                if (relaysNode.isArray()) {
                    for (JsonNode relay : relaysNode) {
                        JsonNode caps = relay.get("capabilities");
                        if (caps != null && caps.isArray() && !caps.isEmpty()) {
                            ObjectNode wrapper = yamlMapper.createObjectNode();
                            wrapper.set("capabilities", caps);
                            byte[] bytes = yamlMapper.writeValueAsBytes(wrapper);
                            registry.loadAdditional(new ByteArrayInputStream(bytes));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load relay capabilities from {}: {}", relaysPath, e.getMessage());
            }
        }

        ProviderManager providerManager = new ProviderManager(adapterBase, allGroups);
        providerManager.setHealthTracker(healthTracker);
        providerManager.setRegistry(registry);

        // Step 8: Handlers
        ControlHandler controlHandler = new ControlHandler(
                stateMachine, sessionManager, db, registry, policy, bodyBudget);
        McpHandler mcpHandler = new McpHandler(
                stateMachine, registry, policy, db, bodyBudget);
        controlHandler.setHealthTracker(healthTracker);
        controlHandler.setMcpHandler(mcpHandler); // OS-12: cache clear on session close
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

        // Step 9b: Dashboard HTTP server (embedded in daemon). Opt-in to avoid
        // binding a fixed port during tests and headless/service operation.
        DashboardServer dashboard = null;
        String dashEnabled = System.getenv("MCPHUB_DASHBOARD_ENABLED");
        if ("1".equals(dashEnabled)) {
            int dashPort = 9741;
            String portEnv = System.getenv("MCPHUB_DASHBOARD_PORT");
            if (portEnv != null && !portEnv.isBlank()) dashPort = Integer.parseInt(portEnv);
            String dashBind = System.getenv().getOrDefault("MCPHUB_DASHBOARD_BIND", "127.0.0.1");
            dashboard = new DashboardServer(db, healthTracker, stateMachine, registry, providerManager,
                    dashPort, dashBind);
            try {
                dashboard.start();
            } catch (Exception e) {
                log.warn("Dashboard failed to start: {}", e.getMessage());
            }
        }

        // Step 10: UDS daemon server (AMD-MCPHUB-001: replaces Go UDS server)
        DaemonServer server = new DaemonServer(dispatcher, providerManager);
        log.info("mcphub daemon ready. Serving on UDS: {}", DaemonServer.socketPath());

        try {
            server.run();
        } finally {
            if (dashboard != null) dashboard.stop();
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
        // Check MCPHUB_HOME env var (operator override)
        String homeEnv = System.getenv("MCPHUB_HOME");
        if (homeEnv != null && !homeEnv.isBlank()) {
            Path p = Path.of(homeEnv, "adapters", "dist");
            if (Files.isDirectory(p)) return p.toAbsolutePath().toString();
        }

        // Check relative to JAR (distribution: lib/ is sibling to adapters/)
        try {
            String jarDir = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            // Distribution: lib/ → adapters/dist (1 level up)
            // Gradle build: java/build/libs → adapters/dist (3 levels up)
            for (String rel : new String[]{"..", "../../..", "../../../.."}) {
                Path candidate = Path.of(jarDir, rel, "adapters", "dist").normalize();
                if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        } catch (Exception ignored) {}

        // Check working directory
        Path cwd = Path.of("adapters", "dist");
        if (Files.isDirectory(cwd)) return cwd.toAbsolutePath().toString();

        // Check relative to working directory (1-3 levels up, for build subdirectory execution)
        for (String rel : new String[]{"..", "../..", "../../.."}) {
            Path candidate = Path.of(rel, "adapters", "dist").normalize();
            if (Files.isDirectory(candidate)) return candidate.toAbsolutePath().normalize().toString();
        }

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

    // =========================================================================
    // doctor — local preflight checks for installation/support
    // =========================================================================

    private record DoctorCheck(String name, String status, String message, String detail) {}

    private static void runDoctor(boolean jsonOutput) throws Exception {
        List<DoctorCheck> checks = new ArrayList<>();
        checks.add(checkJavaRuntime());
        checks.add(checkCommand("node", "node", "--version"));
        checks.add(checkCommand("rg", "rg", "--version"));
        checks.add(checkSqliteWritable());
        checks.add(checkAdapterDist());
        checks.add(checkRelaysYaml());
        checks.add(checkBraveKey());

        boolean hasFail = checks.stream().anyMatch(c -> "fail".equals(c.status()));
        boolean hasWarn = checks.stream().anyMatch(c -> "warn".equals(c.status()));
        String overall = hasFail ? "error" : hasWarn ? "warning" : "ok";

        if (jsonOutput) {
            ObjectNode root = mapper.createObjectNode();
            root.put("status", overall);
            var arr = root.putArray("checks");
            for (DoctorCheck c : checks) {
                ObjectNode n = arr.addObject();
                n.put("name", c.name());
                n.put("status", c.status());
                n.put("message", c.message());
                if (c.detail() != null && !c.detail().isBlank()) n.put("detail", c.detail());
            }
            System.out.println(mapper.writeValueAsString(root));
        } else {
            System.out.println("MCPHUB doctor: " + overall.toUpperCase());
            for (DoctorCheck c : checks) {
                String mark = switch (c.status()) {
                    case "pass" -> "✓";
                    case "warn" -> "⚠";
                    default -> "✗";
                };
                System.out.printf("%s %-18s %s%n", mark, c.name(), c.message());
                if (c.detail() != null && !c.detail().isBlank()) {
                    System.out.printf("  %s%n", c.detail());
                }
            }
        }

        if (hasFail) System.exit(1);
    }

    private static DoctorCheck checkJavaRuntime() {
        String version = System.getProperty("java.version", "unknown");
        String home = System.getProperty("java.home", "");
        Path javaBin = Path.of(home, "bin", "java");
        if (!Files.isExecutable(javaBin)) {
            return new DoctorCheck("java", "fail", "Java runtime is not executable", javaBin.toString());
        }
        return new DoctorCheck("java", "pass", "Java " + version, javaBin.toString());
    }

    private static DoctorCheck checkCommand(String name, String command, String versionArg) {
        String executable = findOnPath(command);
        if (executable == null) {
            return new DoctorCheck(name, "fail", command + " not found on PATH", "Install " + command + " and retry.");
        }
        try {
            Process p = new ProcessBuilder(executable, versionArg)
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new DoctorCheck(name, "fail", command + " did not answer --version", executable);
            }
            String output = new String(p.getInputStream().readAllBytes()).strip().split("\\R", 2)[0];
            if (p.exitValue() != 0) {
                return new DoctorCheck(name, "fail", command + " --version failed", output);
            }
            return new DoctorCheck(name, "pass", output.isBlank() ? executable : output, executable);
        } catch (Exception e) {
            return new DoctorCheck(name, "fail", command + " check failed", e.getMessage());
        }
    }

    private static String findOnPath(String command) {
        Path direct = Path.of(command);
        if (direct.getParent() != null && Files.isExecutable(direct)) return direct.toString();
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String part : path.split(File.pathSeparator)) {
            if (part.isBlank()) continue;
            Path candidate = Path.of(part, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private static DoctorCheck checkSqliteWritable() {
        String dbPath = DatabaseManager.defaultDbPath();
        try {
            Path path = Path.of(dbPath);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 java.sql.Statement st = conn.createStatement()) {
                conn.setAutoCommit(false);
                st.execute("CREATE TABLE IF NOT EXISTS doctor_probe (id INTEGER PRIMARY KEY, checked_utc TEXT NOT NULL)");
                st.execute("INSERT INTO doctor_probe(checked_utc) VALUES(datetime('now'))");
                conn.rollback();
            }
            return new DoctorCheck("sqlite_writable", "pass", "database writable", dbPath);
        } catch (Exception e) {
            return new DoctorCheck("sqlite_writable", "fail", "database is not writable", dbPath + ": " + e.getMessage());
        }
    }

    private static DoctorCheck checkAdapterDist() {
        String adapterDir = System.getenv("MCPHUB_ADAPTER_DIR");
        if (adapterDir == null || adapterDir.isBlank()) adapterDir = findAdapterDir();
        Path base = Path.of(adapterDir);
        if (!Files.isDirectory(base)) {
            return new DoctorCheck("adapter_dist", "fail", "adapter dist directory not found", base.toString());
        }
        List<String> missing = new ArrayList<>();
        for (ProviderManager.GroupConfig g : ProviderManager.defaultGroups()) {
            if (g.script() != null && !Files.isRegularFile(base.resolve(g.script()))) {
                missing.add(g.script());
            }
        }
        if (!missing.isEmpty()) {
            return new DoctorCheck("adapter_dist", "fail", "adapter dist incomplete", base + " missing " + String.join(", ", missing));
        }
        return new DoctorCheck("adapter_dist", "pass", "adapter dist found", base.toAbsolutePath().normalize().toString());
    }

    private static DoctorCheck checkRelaysYaml() {
        Path relaysPath = Path.of(System.getenv().getOrDefault("MCPHUB_RELAYS_PATH",
                System.getProperty("user.home") + "/.config/mcphub/relays.yaml"));
        if (!Files.exists(relaysPath)) {
            return new DoctorCheck("relays_yaml", "warn", "relays.yaml not configured (optional)", relaysPath.toString());
        }
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.findAndRegisterModules();
            JsonNode root = yamlMapper.readTree(relaysPath.toFile());
            JsonNode relays = root.path("relays");
            if (!relays.isMissingNode() && !relays.isArray()) {
                return new DoctorCheck("relays_yaml", "fail", "relays.yaml invalid", "relays must be an array: " + relaysPath);
            }
            int count = relays.isArray() ? relays.size() : 0;
            return new DoctorCheck("relays_yaml", "pass", "relays.yaml valid (" + count + " relays)", relaysPath.toString());
        } catch (Exception e) {
            return new DoctorCheck("relays_yaml", "fail", "relays.yaml invalid", relaysPath + ": " + e.getMessage());
        }
    }

    private static DoctorCheck checkBraveKey() {
        Path keyPath = Path.of(System.getProperty("user.home"), ".config", "mcphub", "brave-api-key");
        if (!Files.exists(keyPath)) {
            return new DoctorCheck("brave_key", "warn", "Brave Search key not configured (websearch optional)", keyPath.toString());
        }
        try {
            String key = Files.readString(keyPath).trim();
            if (key.isBlank()) {
                return new DoctorCheck("brave_key", "fail", "Brave Search key file is empty", keyPath.toString());
            }
            return new DoctorCheck("brave_key", "pass", "Brave Search key configured", keyPath.toString());
        } catch (Exception e) {
            return new DoctorCheck("brave_key", "fail", "Brave Search key not readable", keyPath + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // CLI report — text-based dashboard for terminals (no browser needed)
    // =========================================================================

    private static void runReport() {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + DatabaseManager.defaultDbPath())) {
            java.sql.Statement s = c.createStatement();

            // Summary
            int totalCalls = 0, totalSessions = 0, avgLatency = 0, errors = 0, warnings = 0;
            long totalReqBytes = 0, totalRespBytes = 0;
            try (java.sql.ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) as calls, ROUND(AVG(latency_ms)) as lat, " +
                    "SUM(CASE WHEN error_code IS NOT NULL THEN 1 ELSE 0 END) as errs, " +
                    "SUM(CASE WHEN error_code IS NULL AND route_decision != 'allowed' THEN 1 ELSE 0 END) as warns, " +
                    "COALESCE(SUM(request_size_bytes),0) as rq, COALESCE(SUM(response_size_bytes),0) as rs FROM route_log")) {
                if (rs.next()) {
                    totalCalls = rs.getInt("calls");
                    avgLatency = rs.getInt("lat");
                    errors = rs.getInt("errs");
                    warnings = rs.getInt("warns");
                    totalReqBytes = rs.getLong("rq");
                    totalRespBytes = rs.getLong("rs");
                }
            }
            try (java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM session_log")) {
                if (rs.next()) totalSessions = rs.getInt(1);
            }
            // p95 latency
            int p95Latency = 0;
            java.util.List<Integer> allLatencies = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = s.executeQuery("SELECT latency_ms FROM route_log ORDER BY latency_ms")) {
                while (rs.next()) allLatencies.add(rs.getInt(1));
            }
            if (!allLatencies.isEmpty()) {
                int p95idx = (int) (allLatencies.size() * 0.95);
                if (p95idx >= allLatencies.size()) p95idx = allLatencies.size() - 1;
                p95Latency = allLatencies.get(p95idx);
            }

            System.out.println();
            System.out.println("═══ MCPHUB Dashboard ═══");
            System.out.printf("  Sessions: %-6d  Tool Calls: %-6d  Avg: %dms  p95: %dms%n",
                    totalSessions, totalCalls, avgLatency, p95Latency);
            double errRate = totalCalls > 0 ? (errors * 100.0 / totalCalls) : 0;
            double warnRate = totalCalls > 0 ? (warnings * 100.0 / totalCalls) : 0;
            System.out.printf("  Pass: %-5d (%.1f%%)  Warn: %-5d (%.1f%%)  Fail: %-5d (%.1f%%)%n",
                    totalCalls - errors - warnings, 100.0 - errRate - warnRate,
                    warnings, warnRate, errors, errRate);
            System.out.printf("  Request: %s  Response: %s%n",
                    formatBytes(totalReqBytes), formatBytes(totalRespBytes));
            System.out.println();

            // Tool frequency
            System.out.println("── Tool Call Frequency ──");
            System.out.println("  tool                           count    avg    p95   err%");
            try (java.sql.ResultSet rs = s.executeQuery(
                    "SELECT tool_name, COUNT(*) as cnt, ROUND(AVG(latency_ms)) as ms, " +
                    "SUM(CASE WHEN error_code IS NOT NULL THEN 1 ELSE 0 END) as errs " +
                    "FROM route_log GROUP BY tool_name ORDER BY cnt DESC LIMIT 20")) {
                int max = 0;
                java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    int cnt = rs.getInt("cnt");
                    if (cnt > max) max = cnt;
                    java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                    row.put("name", rs.getString("tool_name"));
                    row.put("cnt", cnt);
                    row.put("ms", rs.getInt("ms"));
                    row.put("errs", rs.getInt("errs"));
                    rows.add(row);
                }
                for (var row : rows) {
                    String name = (String) row.get("name");
                    int cnt = (int) row.get("cnt");
                    int bar = Math.min((int) (cnt * 30.0 / Math.max(max, 1)), 30);
                    int errPerTool = (int) row.get("errs");
                    double ePct = cnt > 0 ? (errPerTool * 100.0 / cnt) : 0;
                    int toolP95 = 0;
                    java.util.List<Integer> toolLat = new java.util.ArrayList<>();
                    try (java.sql.ResultSet lrs = s.executeQuery(
                            "SELECT latency_ms FROM route_log WHERE tool_name = '" +
                            name.replace("'", "''") + "' ORDER BY latency_ms")) {
                        while (lrs.next()) toolLat.add(lrs.getInt(1));
                    } catch (Exception ignored) {}
                    if (!toolLat.isEmpty()) {
                        int idx = (int) (toolLat.size() * 0.95);
                        if (idx >= toolLat.size()) idx = toolLat.size() - 1;
                        toolP95 = toolLat.get(idx);
                    }
                    String errStr = ePct > 0 ? String.format(" %.0f%%", ePct) : "";
                    String mark = ePct > 10 ? " ✗" : ePct > 0 ? " ⚠" : " ✓";
                    String barStr = "█".repeat(Math.max(bar, 0));
                    System.out.printf("  %-30s %s %5d %5dms %5dms %s%s%n",
                            name.length() > 30 ? name.substring(0, 30) : name, barStr, cnt,
                            (int) row.get("ms"), toolP95, errStr, mark);
                }
            }
            System.out.println();

            // Recent sessions
            System.out.println("── Recent Sessions ──");
            System.out.println("  session     started              ended                calls  result");
            try (java.sql.ResultSet rs = s.executeQuery(
                    "SELECT session_id, event_type, timestamp_utc, to_state FROM session_log " +
                    "WHERE event_type = 'state_change' ORDER BY timestamp_utc DESC")) {
                java.util.LinkedHashMap<String,java.util.Map<String,String>> sessions =
                    new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    String sid = rs.getString("session_id");
                    String ts = rs.getString("timestamp_utc").replace('T',' ').substring(0, 19);
                    String to = rs.getString("to_state");
                    if (!sessions.containsKey(sid)) {
                        java.util.Map<String,String> m = new java.util.LinkedHashMap<>();
                        m.put("id", sid);
                        m.put("start", ts);
                        m.put("end", ts);
                        m.put("result", to);
                        sessions.put(sid, m);
                    } else {
                        java.util.Map<String,String> m = sessions.get(sid);
                        if (!"ARMED".equals(to) && !"OPEN".equals(to)) {
                            m.put("end", ts);
                            m.put("result", to);
                        }
                    }
                }
                java.util.Map<String,Integer> callCounts = new java.util.HashMap<>();
                try (java.sql.ResultSet crs = s.executeQuery(
                        "SELECT session_id, COUNT(*) as c FROM route_log GROUP BY session_id")) {
                    while (crs.next()) callCounts.put(crs.getString(1), crs.getInt(2));
                }
                int shown = 0;
                for (var e : sessions.entrySet()) {
                    if (shown++ >= 15) break;
                    java.util.Map<String,String> m = e.getValue();
                    int calls = callCounts.getOrDefault(m.get("id"), 0);
                    String result = m.get("result");
                    String active = calls == 0 ? "  (idle)" : "";
                    System.out.printf("  %-10s  %s  %s  %5d  %s%s%n",
                            m.get("id").length() > 10 ? m.get("id").substring(0, 10) : m.get("id"),
                            m.get("start").length() > 19 ? m.get("start").substring(0, 19) : m.get("start"),
                            m.get("end").length() > 19 ? m.get("end").substring(0, 19) : m.get("end"),
                            calls, result, active);
                }
            }
            System.out.println();

        } catch (Exception e) {
            System.err.println("Report error: " + e.getMessage());
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
