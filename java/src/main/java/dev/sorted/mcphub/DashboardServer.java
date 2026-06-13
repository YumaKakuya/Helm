package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Embedded HTTP dashboard for MCPHUB operational visibility.
 * Lives inside the daemon JVM. Serves a self-contained HTML dashboard
 * with live provider health, tool call stats, session history, body budget,
 * provider restart, log tail, and error details.
 *
 * Endpoints:
 *   /                                 — Dashboard HTML
 *   /api/summary[?since=ISO&until=ISO]— Aggregated stats (SQL)
 *   /api/tool-stats[?since=ISO&until=ISO] — Per-tool frequency + latency (SQL)
 *   /api/sessions[?since=ISO&until=ISO] — Recent session events (SQL)
 *   /api/budget                       — Body budget snapshot history (SQL)
 *   /api/providers                    — Live provider health (in-memory)
 *   /api/providers/restart?group=NAME — Restart a specific provider group
 *   /api/state                        — Live daemon state (in-memory)
 *   /api/models                       — Client model analytics (SQL)
 *   /api/logs/tail?lines=N            — Recent route_log entries with error detail
 *   /api/errors                       — Error breakdown report (SQL)
 *   /api/export/tool-stats[?format=csv] — CSV export
 */
public class DashboardServer {
    private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final DatabaseManager db;
    private final String dbPath;
    private final ProviderHealthTracker healthTracker;
    private final StateMachine stateMachine;
    private final CapabilityRegistry registry;
    private final ProviderManager providerManager;
    private final int port;
    private final String bindAddr;
    private HttpServer server;

    public DashboardServer(DatabaseManager db, ProviderHealthTracker healthTracker,
                           StateMachine stateMachine, CapabilityRegistry registry,
                           ProviderManager providerManager, int port, String bindAddr) {
        this.db = db;
        this.healthTracker = healthTracker;
        this.stateMachine = stateMachine;
        this.registry = registry;
        this.providerManager = providerManager;
        this.port = port;
        this.bindAddr = bindAddr;
        this.dbPath = DatabaseManager.defaultDbPath();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindAddr, port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/summary", this::handleSummary);
        server.createContext("/api/tool-stats", this::handleToolStats);
        server.createContext("/api/sessions", this::handleSessions);
        server.createContext("/api/budget", this::handleBudget);
        server.createContext("/api/providers", this::handleProviders);
        server.createContext("/api/providers/restart", this::handleProviderRestart);
        server.createContext("/api/state", this::handleState);
        server.createContext("/api/models", this::handleModels);
        server.createContext("/api/logs/tail", this::handleLogTail);
        server.createContext("/api/errors", this::handleErrors);
        server.createContext("/api/export/tool-stats", this::handleExportToolStats);
        server.setExecutor(null);
        server.start();
        String url = "http://" + bindAddr + ":" + port + "/";
        log.info("Dashboard available at {}", url);
        tryOpenBrowser(url);
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    private void tryOpenBrowser(String url) {
        if (!"0".equals(System.getenv("MCPHUB_DASHBOARD_NO_BROWSER"))) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ignored) {}
        }
    }

    // ---- HTTP handlers ----

    private void handleIndex(HttpExchange ex) throws IOException {
        byte[] html = loadDashboardHtml();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
        ex.getResponseBody().close();
    }

    private void handleSummary(HttpExchange ex) throws IOException {
        String since = getQueryParam(ex, "since");
        String until = getQueryParam(ex, "until");
        String where = timeFilter(since, until);
        try {
            ObjectNode summary = mapper.createObjectNode();
            execQuery("SELECT COUNT(*) FROM route_log" + where, rs ->
                summary.put("totalCalls", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT COUNT(*) FROM session_log" + where, rs ->
                summary.put("totalSessions", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT ROUND(AVG(latency_ms)) FROM route_log" + where, rs ->
                summary.put("avgLatencyMs", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT COUNT(*) FROM route_log WHERE error_code IS NOT NULL" +
                (since != null ? " AND timestamp_utc >= '" + since + "'" : "") +
                (until != null ? " AND timestamp_utc <= '" + until + "'" : ""), rs ->
                summary.put("errorCalls", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT COUNT(*) FROM route_log WHERE timestamp_utc > datetime('now','-1 day')", rs ->
                summary.put("calls24h", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT COUNT(*) FROM session_log WHERE timestamp_utc > datetime('now','-1 day')", rs ->
                summary.put("sessions24h", rs.next() ? rs.getInt(1) : 0));
            execQuery("SELECT COALESCE(SUM(request_size_bytes),0) FROM route_log" + where, rs ->
                summary.put("totalRequestBytes", rs.next() ? rs.getLong(1) : 0L));
            execQuery("SELECT COALESCE(SUM(response_size_bytes),0) FROM route_log" + where, rs ->
                summary.put("totalResponseBytes", rs.next() ? rs.getLong(1) : 0L));
            sendJson(ex, 200, summary);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleToolStats(HttpExchange ex) throws IOException {
        String since = getQueryParam(ex, "since");
        String until = getQueryParam(ex, "until");
        String where = timeFilter(since, until);
        try {
            ArrayNode arr = mapper.createArrayNode();
            execQuery("SELECT tool_name, COUNT(*) as cnt, ROUND(AVG(latency_ms)) as avg_ms, " +
                "SUM(CASE WHEN error_code IS NOT NULL THEN 1 ELSE 0 END) as errors " +
                "FROM route_log" + where + " GROUP BY tool_name ORDER BY cnt DESC", rs -> {
                while (rs.next()) {
                    ObjectNode t = mapper.createObjectNode();
                    t.put("name", rs.getString("tool_name"));
                    t.put("count", rs.getInt("cnt"));
                    t.put("avgMs", rs.getInt("avg_ms"));
                    t.put("errors", rs.getInt("errors"));
                    arr.add(t);
                }
            });
            sendJson(ex, 200, arr);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        String since = getQueryParam(ex, "since");
        String until = getQueryParam(ex, "until");
        String where = timeFilter(since, until);
        try {
            ArrayNode arr = mapper.createArrayNode();
            execQuery("SELECT sl.session_id, sl.timestamp_utc, sl.event_type as event, " +
                "sl.from_state, sl.to_state, " +
                "(SELECT COUNT(*) FROM route_log rl WHERE rl.session_id = sl.session_id) as calls " +
                "FROM session_log sl" + where + " ORDER BY sl.timestamp_utc DESC LIMIT 100", rs -> {
                while (rs.next()) {
                    ObjectNode sess = mapper.createObjectNode();
                    sess.put("sessionId", rs.getString("session_id"));
                    sess.put("timestamp", rs.getString("timestamp_utc"));
                    sess.put("event", rs.getString("event"));
                    sess.put("fromState", rs.getString("from_state"));
                    sess.put("toState", rs.getString("to_state"));
                    sess.put("toolCalls", rs.getInt("calls"));
                    arr.add(sess);
                }
            });
            sendJson(ex, 200, arr);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleBudget(HttpExchange ex) throws IOException {
        try {
            ArrayNode arr = mapper.createArrayNode();
            execQuery("SELECT timestamp_utc, session_id, effective_tier, inline_builtin_count, " +
                "mcphub_hosted_tool_count, request_body_tool_schema_bytes " +
                "FROM body_budget_snapshot ORDER BY timestamp_utc DESC LIMIT 50", rs -> {
                while (rs.next()) {
                    ObjectNode b = mapper.createObjectNode();
                    b.put("timestamp", rs.getString("timestamp_utc"));
                    b.put("sessionId", rs.getString("session_id"));
                    b.put("effectiveTier", rs.getString("effective_tier"));
                    b.put("inlineBuiltinCount", rs.getInt("inline_builtin_count"));
                    b.put("mcphubHostedCount", rs.getInt("mcphub_hosted_tool_count"));
                    b.put("requestBodyBytes", rs.getInt("request_body_tool_schema_bytes"));
                    arr.add(b);
                }
            });
            sendJson(ex, 200, arr);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleProviders(HttpExchange ex) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        Map<String, String> health = healthTracker != null ? healthTracker.snapshot() : Map.of();
        ArrayNode groups = mapper.createArrayNode();
        for (var e : health.entrySet()) {
            ObjectNode g = mapper.createObjectNode();
            g.put("group", e.getKey());
            g.put("status", e.getValue());
            g.put("running", providerManager != null && providerManager.isRunning(e.getKey()));
            groups.add(g);
        }
        result.set("providers", groups);
        result.put("total", health.size());
        result.put("running", health.values().stream().filter(s -> "running".equals(s)).count());
        result.put("stopped", health.values().stream().filter(s -> "stopped".equals(s)).count());
        sendJson(ex, 200, result);
    }

    /** Restart a specific provider group. POST expected from dashboard UI. */
    private void handleProviderRestart(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Use POST to restart a provider");
            return;
        }
        String group = getQueryParam(ex, "group");
        if (group == null || group.isBlank()) {
            sendError(ex, 400, "Missing ?group= query parameter");
            return;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("group", group);
        try {
            if (providerManager == null) {
                result.put("status", "error");
                result.put("message", "Provider manager not available");
                sendJson(ex, 503, result);
                return;
            }
            boolean ok = providerManager.restartGroup(group);
            result.put("status", ok ? "restarted" : "error");
            result.put("message", ok ? "Provider group '" + group + "' restarted"
                    : "Failed to restart group '" + group + "'");
            sendJson(ex, ok ? 200 : 500, result);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            sendJson(ex, 500, result);
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        ObjectNode s = mapper.createObjectNode();
        if (stateMachine != null) {
            s.put("state", stateMachine.getState().name());
            s.put("isLocked", stateMachine.isLockedUntilUnlock());
        }
        if (registry != null) {
            s.put("capabilitiesLoaded", registry.getLoadedCount());
            s.put("capabilitiesRejected", registry.getRejectedCount());
        }
        sendJson(ex, 200, s);
    }

    private void handleModels(HttpExchange ex) throws IOException {
        try {
            ArrayNode arr = mapper.createArrayNode();
            execQuery("SELECT COALESCE(client_name,'unknown') as name, " +
                "COALESCE(client_version,'-') as version, COUNT(*) as calls, " +
                "ROUND(AVG(latency_ms)) as avg_ms, " +
                "SUM(CASE WHEN error_code IS NOT NULL THEN 1 ELSE 0 END) as errors " +
                "FROM route_log GROUP BY client_name, client_version ORDER BY calls DESC", rs -> {
                while (rs.next()) {
                    ObjectNode m = mapper.createObjectNode();
                    m.put("name", rs.getString("name"));
                    m.put("version", rs.getString("version"));
                    m.put("calls", rs.getInt("calls"));
                    m.put("avgMs", rs.getInt("avg_ms"));
                    m.put("errors", rs.getInt("errors"));
                    arr.add(m);
                }
            });
            ObjectNode resp = mapper.createObjectNode();
            resp.set("models", arr);
            resp.put("totalModels", arr.size());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** Recent route_log entries — tail view with error details. */
    private void handleLogTail(HttpExchange ex) throws IOException {
        int lines;
        try {
            lines = Integer.parseInt(getQueryParamOrDefault(ex, "lines", "50"));
            if (lines < 1) lines = 1;
            if (lines > 500) lines = 500;
        } catch (NumberFormatException e) {
            lines = 50;
        }
        try {
            ArrayNode arr = mapper.createArrayNode();
            execQuery("SELECT timestamp_utc, session_id, tool_name, provider_type, " +
                "route_decision, latency_ms, request_size_bytes, response_size_bytes, " +
                "error_code, COALESCE(client_name,'-') as client_name " +
                "FROM route_log ORDER BY id DESC LIMIT " + lines, rs -> {
                while (rs.next()) {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.put("ts", rs.getString("timestamp_utc"));
                    entry.put("sessionId", abbrev(rs.getString("session_id"), 8));
                    entry.put("tool", rs.getString("tool_name"));
                    entry.put("provider", rs.getString("provider_type"));
                    entry.put("decision", rs.getString("route_decision"));
                    entry.put("latencyMs", rs.getInt("latency_ms"));
                    entry.put("reqBytes", rs.getInt("request_size_bytes"));
                    entry.put("respBytes", rs.getInt("response_size_bytes"));
                    String err = rs.getString("error_code");
                    entry.put("error", err != null ? err : "");
                    entry.put("client", rs.getString("client_name"));
                    arr.add(entry);
                }
            });
            sendJson(ex, 200, arr);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** Error breakdown report — per-tool error counts with detail. */
    private void handleErrors(HttpExchange ex) throws IOException {
        try {
            ObjectNode report = mapper.createObjectNode();

            // Per-tool error breakdown
            ArrayNode perTool = mapper.createArrayNode();
            execQuery("SELECT tool_name, error_code, COUNT(*) as cnt " +
                "FROM route_log WHERE error_code IS NOT NULL " +
                "GROUP BY tool_name, error_code ORDER BY cnt DESC", rs -> {
                while (rs.next()) {
                    ObjectNode e = mapper.createObjectNode();
                    e.put("tool", rs.getString("tool_name"));
                    e.put("errorCode", rs.getString("error_code"));
                    e.put("count", rs.getInt("cnt"));
                    perTool.add(e);
                }
            });
            report.set("perTool", perTool);

            // Total error summary
            execQuery("SELECT COUNT(*) as total, COUNT(DISTINCT tool_name) as affected_tools " +
                "FROM route_log WHERE error_code IS NOT NULL", rs -> {
                if (rs.next()) {
                    report.put("totalErrors", rs.getInt("total"));
                    report.put("affectedTools", rs.getInt("affected_tools"));
                }
            });

            // Recent errors with detail
            ArrayNode recent = mapper.createArrayNode();
            execQuery("SELECT timestamp_utc, session_id, tool_name, error_code, " +
                "route_decision, provider_type " +
                "FROM route_log WHERE error_code IS NOT NULL " +
                "ORDER BY id DESC LIMIT 20", rs -> {
                while (rs.next()) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("ts", rs.getString("timestamp_utc"));
                    r.put("sessionId", abbrev(rs.getString("session_id"), 8));
                    r.put("tool", rs.getString("tool_name"));
                    r.put("error", rs.getString("error_code"));
                    r.put("decision", rs.getString("route_decision"));
                    r.put("provider", rs.getString("provider_type"));
                    recent.add(r);
                }
            });
            report.set("recentErrors", recent);

            sendJson(ex, 200, report);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    private void handleExportToolStats(HttpExchange ex) throws IOException {
        String format = getQueryParam(ex, "format");
        boolean csv = "csv".equalsIgnoreCase(format);
        String since = getQueryParam(ex, "since");
        String until = getQueryParam(ex, "until");
        String where = timeFilter(since, until);

        StringBuilder sb = new StringBuilder();
        try {
            execQuery("SELECT tool_name, COUNT(*) as cnt, ROUND(AVG(latency_ms)) as avg_ms, " +
                "SUM(CASE WHEN error_code IS NOT NULL THEN 1 ELSE 0 END) as errors " +
                "FROM route_log" + where + " GROUP BY tool_name ORDER BY cnt DESC", rs -> {
                if (csv) {
                    sb.append("tool_name,count,avg_ms,errors\n");
                    while (rs.next()) {
                        sb.append(rs.getString("tool_name")).append(',')
                          .append(rs.getInt("cnt")).append(',')
                          .append(rs.getInt("avg_ms")).append(',')
                          .append(rs.getInt("errors")).append('\n');
                    }
                } else {
                    ArrayNode arr = buildToolStatsArray(rs);
                    try {
                        sb.append(mapper.writeValueAsString(arr));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        sb.append("[]");
                    }
                }
            });
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
            return;
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",
            csv ? "text/csv; charset=utf-8" : "application/json");
        ex.getResponseHeaders().set("Content-Disposition",
            csv ? "attachment; filename=mcphub-tool-stats.csv" : "inline");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private ArrayNode buildToolStatsArray(ResultSet rs) throws SQLException {
        ArrayNode arr = mapper.createArrayNode();
        while (rs.next()) {
            ObjectNode t = mapper.createObjectNode();
            t.put("name", rs.getString("tool_name"));
            t.put("count", rs.getInt("cnt"));
            t.put("avgMs", rs.getInt("avg_ms"));
            t.put("errors", rs.getInt("errors"));
            arr.add(t);
        }
        return arr;
    }

    // ---- helpers ----

    private String getQueryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String p : q.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private String getQueryParamOrDefault(HttpExchange ex, String key, String defaultValue) {
        String val = getQueryParam(ex, key);
        return val != null ? val : defaultValue;
    }

    private String timeFilter(String since, String until) {
        StringBuilder w = new StringBuilder();
        if (since != null && !since.isBlank()) {
            w.append(" WHERE timestamp_utc >= '").append(since).append("'");
        }
        if (until != null && !until.isBlank()) {
            w.append(w.isEmpty() ? " WHERE " : " AND ")
             .append("timestamp_utc <= '").append(until).append("'");
        }
        return w.toString();
    }

    private static String abbrev(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    @FunctionalInterface
    private interface ResultSetConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private void execQuery(String sql, ResultSetConsumer consumer) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            consumer.accept(rs);
        }
    }

    private void sendJson(HttpExchange ex, int code, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private void sendError(HttpExchange ex, int code, String msg) throws IOException {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", msg);
        sendJson(ex, code, err);
    }

    private byte[] loadDashboardHtml() {
        try (InputStream is = getClass().getResourceAsStream("/dashboard.html")) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignored) {}
        return ("<!DOCTYPE html><html><body><h1>MCPHUB Dashboard</h1><p>Dashboard template not found.</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }
}
