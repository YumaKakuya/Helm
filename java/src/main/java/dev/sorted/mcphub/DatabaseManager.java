package dev.sorted.mcphub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;

/**
 * SQLite persistence layer for MCPHUB alpha.
 * Spec: Chapter 8 §8.2 (storage engine), §8.3 (route_log), §8.4 (failure_log),
 *       §8.5 (body_budget_snapshot), §8.6 (session_log), §8.8 (lock, rollback)
 *
 * REQ-8.2.1: SQLite as sole local storage engine
 * REQ-8.2.2: Java MUST be the sole write-opener
 * REQ-8.2.3: database at $MCPHUB_DATA_DIR/mcphub.db
 * REQ-8.2.5: schema_version table, forward-only migrations
 */
public class DatabaseManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final int ALPHA_SCHEMA_VERSION = 1;

    private final String dbPath;
    private Connection connection;

    public DatabaseManager() {
        this(defaultDbPath());
    }

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /** Open database, run migrations, acquire write lock. REQ-8.2.4, REQ-8.2.5 */
    public void open() throws SQLException {
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();
        boolean isNew = !dbFile.exists();

        String url = "jdbc:sqlite:" + dbPath;
        connection = DriverManager.getConnection(url);
        connection.setAutoCommit(true);

        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }

        if (isNew) {
            log.info("New database created at {}", dbPath);
        }

        runMigrations();
        acquireWriteLock();
    }

    /** Return the JDBC connection. */
    public Connection getConnection() { return connection; }

    @Override
    public void close() {
        releaseWriteLock();
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Migration engine (REQ-8.2.5, REQ-8.2.6)
    // -------------------------------------------------------------------------

    private void runMigrations() throws SQLException {
        createSchemaVersionTable();
        int current = getCurrentSchemaVersion();
        if (current < ALPHA_SCHEMA_VERSION) {
            log.info("Running schema migration: {} -> {}", current, ALPHA_SCHEMA_VERSION);
            migrateV1();
            setSchemaVersion(ALPHA_SCHEMA_VERSION);
            log.info("Schema migration complete. Version = {}", ALPHA_SCHEMA_VERSION);
        } else {
            log.info("Schema up to date (version {})", current);
        }
    }

    private void createSchemaVersionTable() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL
                )
            """);
        }
    }

    private int getCurrentSchemaVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setSchemaVersion(int version) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM schema_version");
            st.execute("INSERT INTO schema_version(version) VALUES(" + version + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Schema version 1 — Alpha tables
    // -------------------------------------------------------------------------

    private void migrateV1() throws SQLException {
        createRouteLog();
        createFailureLog();
        createBodyBudgetSnapshot();
        createSessionLog();
        createLockMetadata();
        createRollbackCheckpoint();
        createLoadedStateReservation();
    }

    /** REQ-8.3.1: route_log schema */
    private void createRouteLog() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS route_log (
                id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc         TEXT    NOT NULL,
                session_id            TEXT    NOT NULL,
                tool_name             TEXT    NOT NULL,
                provider_id           TEXT    NOT NULL,
                provider_type         TEXT    NOT NULL,
                route_decision        TEXT    NOT NULL,
                policy_rule_id        TEXT,
                latency_ms            INTEGER NOT NULL,
                request_size_bytes    INTEGER NOT NULL,
                response_size_bytes   INTEGER,
                intent_annotation     TEXT,
                lock_reason           TEXT,
                error_code            TEXT
            )
        """);
        exec("CREATE INDEX IF NOT EXISTS idx_route_log_session ON route_log(session_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_route_log_tool ON route_log(tool_name)");
    }

    /** REQ-8.4.1: failure_log schema */
    private void createFailureLog() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS failure_log (
                id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc           TEXT    NOT NULL,
                session_id              TEXT,
                tool_name               TEXT,
                provider_id             TEXT,
                failure_class           TEXT    NOT NULL,
                failure_detail          TEXT    NOT NULL,
                recovery_action         TEXT    NOT NULL,
                correlated_route_log_id INTEGER REFERENCES route_log(id)
            )
        """);
        exec("CREATE INDEX IF NOT EXISTS idx_failure_log_session ON failure_log(session_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_failure_log_class ON failure_log(failure_class)");
    }

    /** REQ-8.5.7: body_budget_snapshot schema */
    private void createBodyBudgetSnapshot() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS body_budget_snapshot (
                id                          INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc               TEXT    NOT NULL,
                session_id                  TEXT,
                inline_builtin_count        INTEGER NOT NULL,
                request_body_tool_schema_bytes INTEGER NOT NULL,
                mcphub_hosted_tool_count    INTEGER NOT NULL,
                tier_tool_count             TEXT    NOT NULL,
                tier_byte_size              TEXT    NOT NULL,
                effective_tier              TEXT    NOT NULL,
                trigger                     TEXT    NOT NULL
            )
        """);
    }

    /** REQ-8.6.1: session_log schema */
    private void createSessionLog() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS session_log (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc TEXT    NOT NULL,
                session_id    TEXT    NOT NULL,
                event_type    TEXT    NOT NULL,
                from_state    TEXT,
                to_state      TEXT    NOT NULL,
                trigger       TEXT    NOT NULL
            )
        """);
        exec("CREATE INDEX IF NOT EXISTS idx_session_log_session ON session_log(session_id)");
    }

    /** REQ-8.8.3: lock_metadata shape reservation */
    private void createLockMetadata() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS lock_metadata (
                lock_name    TEXT    PRIMARY KEY,
                holder_pid   INTEGER NOT NULL,
                acquired_utc TEXT    NOT NULL,
                ttl_seconds  INTEGER NOT NULL
            )
        """);
    }

    /** REQ-8.8.5: rollback_checkpoint shape reservation */
    private void createRollbackCheckpoint() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS rollback_checkpoint (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc   TEXT    NOT NULL,
                config_snapshot TEXT    NOT NULL,
                schema_version  INTEGER NOT NULL,
                reason          TEXT    NOT NULL
            )
        """);
    }

    /** REQ-8.8.8: loaded_state shape reservation */
    private void createLoadedStateReservation() throws SQLException {
        exec("""
            CREATE TABLE IF NOT EXISTS loaded_state (
                session_id    TEXT    NOT NULL,
                provider_id   TEXT    NOT NULL,
                loaded        INTEGER NOT NULL,
                reason        TEXT    NOT NULL,
                timestamp_utc TEXT    NOT NULL,
                PRIMARY KEY (session_id, provider_id)
            )
        """);
    }

    // -------------------------------------------------------------------------
    // Write-lock management (REQ-8.8.1, REQ-8.8.4)
    // -------------------------------------------------------------------------

    private void acquireWriteLock() throws SQLException {
        String now = java.time.Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO lock_metadata(lock_name, holder_pid, acquired_utc, ttl_seconds)
                VALUES('db_write', ?, ?, 86400)
            """)) {
            ps.setInt(1, (int) ProcessHandle.current().pid());
            ps.setString(2, now);
            ps.executeUpdate();
        }
        log.info("db_write lock acquired (pid {})", ProcessHandle.current().pid());
    }

    private void releaseWriteLock() {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM lock_metadata WHERE lock_name='db_write'")) {
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    // -------------------------------------------------------------------------
    // REQ-3.8.7/REQ-3.8.8: Persistent session lock guard (locked_until_unlock)
    // Stored in lock_metadata with lock_name='session_locked_until_unlock'.
    // Presence of row = locked; absence of row = unlocked.
    // -------------------------------------------------------------------------

    /** Set the persistent lock_until_unlock flag. REQ-3.8.7 */
    public void setSessionLockGuard(boolean locked) {
        if (connection == null) return;
        try {
            if (locked) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT OR REPLACE INTO lock_metadata(lock_name, holder_pid, acquired_utc, ttl_seconds)
                        VALUES('session_locked_until_unlock', ?, ?, 0)
                    """)) {
                    ps.setInt(1, (int) ProcessHandle.current().pid());
                    ps.setString(2, java.time.Instant.now().toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM lock_metadata WHERE lock_name='session_locked_until_unlock'")) {
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to persist session lock guard: {}", e.getMessage());
        }
    }

    /** Load the persistent lock_until_unlock flag from DB. REQ-3.8.7 */
    public boolean isSessionLockGuardSet() {
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM lock_metadata WHERE lock_name='session_locked_until_unlock'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            log.warn("Failed to read session lock guard: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Logging helpers
    // -------------------------------------------------------------------------

    /** Insert a session lifecycle event. REQ-8.6.1, REQ-3.10.2 */
    public void logSessionEvent(String sessionId, String eventType,
                                 String fromState, String toState, String trigger) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO session_log(timestamp_utc, session_id, event_type, from_state, to_state, trigger)
                VALUES(?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, java.time.Instant.now().toString());
            ps.setString(2, sessionId);
            ps.setString(3, eventType);
            ps.setString(4, fromState);
            ps.setString(5, toState);
            ps.setString(6, trigger);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to log session event", e);
        }
    }

    /** Insert a failure log entry. REQ-8.4.1 */
    public void logFailure(String sessionId, String toolName, String providerId,
                            String failureClass, String detail, String recoveryAction,
                            Integer correlatedRouteLogId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO failure_log(timestamp_utc, session_id, tool_name, provider_id,
                    failure_class, failure_detail, recovery_action, correlated_route_log_id)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, java.time.Instant.now().toString());
            ps.setString(2, sessionId);
            ps.setString(3, toolName);
            ps.setString(4, providerId);
            ps.setString(5, failureClass);
            ps.setString(6, detail);
            ps.setString(7, recoveryAction);
            if (correlatedRouteLogId != null) ps.setInt(8, correlatedRouteLogId);
            else ps.setNull(8, Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to log failure", e);
        }
    }

    /** Check if a table exists. */
    public boolean tableExists(String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * Insert a route log entry. REQ-8.3.1 (IS-05)
     * Returns the generated row id, or -1 on failure.
     * Write is fire-and-forget per REQ-8.3.2 (MUST NOT block call path).
     */
    public long logRouteEntry(String sessionId, String toolName, String providerId,
            String providerType, String routeDecision, String policyRuleId,
            long latencyMs, int requestSizeBytes, Integer responseSizeBytes,
            String intentAnnotation, String errorCode) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO route_log(timestamp_utc, session_id, tool_name, provider_id,
                    provider_type, route_decision, policy_rule_id, latency_ms,
                    request_size_bytes, response_size_bytes, intent_annotation, error_code)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, java.time.Instant.now().toString());
            ps.setString(2, sessionId);
            ps.setString(3, toolName);
            ps.setString(4, providerId);
            ps.setString(5, providerType);
            ps.setString(6, routeDecision);
            if (policyRuleId != null) ps.setString(7, policyRuleId); else ps.setNull(7, Types.VARCHAR);
            ps.setLong(8, latencyMs);
            ps.setInt(9, requestSizeBytes);
            if (responseSizeBytes != null) ps.setInt(10, responseSizeBytes); else ps.setNull(10, Types.INTEGER);
            if (intentAnnotation != null) ps.setString(11, intentAnnotation); else ps.setNull(11, Types.VARCHAR);
            if (errorCode != null) ps.setString(12, errorCode); else ps.setNull(12, Types.VARCHAR);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("Failed to log route entry", e);
            return -1;
        }
    }

    /**
     * Insert a body-budget snapshot. REQ-8.5.7 (IS-09)
     */
    public void logBodyBudgetSnapshot(String sessionId, int inlineBuiltinCount,
            int requestBodyToolSchemaBytes, int mcphubHostedToolCount,
            String tierToolCount, String tierByteSize, String effectiveTier, String trigger) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO body_budget_snapshot(timestamp_utc, session_id,
                    inline_builtin_count, request_body_tool_schema_bytes, mcphub_hosted_tool_count,
                    tier_tool_count, tier_byte_size, effective_tier, trigger)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, java.time.Instant.now().toString());
            if (sessionId != null) ps.setString(2, sessionId); else ps.setNull(2, Types.VARCHAR);
            ps.setInt(3, inlineBuiltinCount);
            ps.setInt(4, requestBodyToolSchemaBytes);
            ps.setInt(5, mcphubHostedToolCount);
            ps.setString(6, tierToolCount);
            ps.setString(7, tierByteSize);
            ps.setString(8, effectiveTier);
            ps.setString(9, trigger);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to log body budget snapshot", e);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    public static String defaultDbPath() {
        String dataDir = System.getenv("MCPHUB_DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            String home = System.getProperty("user.home");
            dataDir = home + "/.local/share/mcphub";
        }
        return dataDir + "/mcphub.db";
    }
}
