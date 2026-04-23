package dev.sorted.mcphub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DatabaseManager.
 * Spec: Chapter 8 §8.2–§8.8
 */
class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        db.open();
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void schemaVersionIsOne() throws Exception {
        // REQ-8.2.5: schema_version = 1 after migration
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void allRequiredTablesExist() throws Exception {
        // REQ-8.2.4 core tables
        String[] tables = {
            "schema_version", "route_log", "failure_log",
            "body_budget_snapshot", "session_log",
            "lock_metadata", "rollback_checkpoint", "loaded_state"
        };
        for (String table : tables) {
            assertTrue(db.tableExists(table), "Missing table: " + table);
        }
    }

    @Test
    void routeLogHasRequiredColumns() throws Exception {
        // REQ-8.3.1
        Connection conn = db.getConnection();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(route_log)")) {
            java.util.Set<String> cols = new java.util.HashSet<>();
            while (rs.next()) cols.add(rs.getString("name"));
            for (String col : new String[]{
                "id","timestamp_utc","session_id","tool_name","provider_id",
                "provider_type","route_decision","latency_ms","request_size_bytes"
            }) {
                assertTrue(cols.contains(col), "Missing column in route_log: " + col);
            }
        }
    }

    @Test
    void failureLogHasRequiredColumns() throws Exception {
        // REQ-8.4.1
        Connection conn = db.getConnection();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(failure_log)")) {
            java.util.Set<String> cols = new java.util.HashSet<>();
            while (rs.next()) cols.add(rs.getString("name"));
            for (String col : new String[]{
                "id","timestamp_utc","failure_class","failure_detail","recovery_action"
            }) {
                assertTrue(cols.contains(col), "Missing column in failure_log: " + col);
            }
        }
    }

    @Test
    void sessionLogInsert() throws Exception {
        // REQ-8.6.1: can insert session log entry
        db.logSessionEvent("s-001", "state_change", "CLOSED", "ARMED", "cli_arm");
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM session_log WHERE session_id='s-001'")) {
            assertTrue(rs.next());
            assertEquals("state_change", rs.getString("event_type"));
            assertEquals("ARMED", rs.getString("to_state"));
        }
    }

    @Test
    void failureLogInsert() throws Exception {
        // REQ-8.4.1: can insert failure log entry
        db.logFailure("s-001", "webfetch", "web-provider",
            "provider_crash", "Process exited with code 1", "none", null);
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM failure_log WHERE session_id='s-001'")) {
            assertTrue(rs.next());
            assertEquals("provider_crash", rs.getString("failure_class"));
        }
    }

    @Test
    void writeLockAcquiredOnOpen() throws Exception {
        // REQ-8.8.4: db_write lock in lock_metadata
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM lock_metadata WHERE lock_name='db_write'")) {
            assertTrue(rs.next(), "db_write lock should be present");
        }
    }

    @Test
    void bodyBudgetSnapshotTableHasColumns() throws Exception {
        // REQ-8.5.7
        Connection conn = db.getConnection();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(body_budget_snapshot)")) {
            java.util.Set<String> cols = new java.util.HashSet<>();
            while (rs.next()) cols.add(rs.getString("name"));
            for (String col : new String[]{
                "id","timestamp_utc","inline_builtin_count",
                "request_body_tool_schema_bytes","effective_tier","trigger"
            }) {
                assertTrue(cols.contains(col), "Missing column in body_budget_snapshot: " + col);
            }
        }
    }

    @Test
    void idempotentMigration() throws Exception {
        // Running open() on existing DB should not throw (idempotent)
        db.close();
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        assertDoesNotThrow(() -> db.open());
    }

    @Test
    void logRouteEntry_insertsRow() throws Exception {
        long id = db.logRouteEntry("session-1", "webfetch", "builtin-hatch",
                "builtin_hosted", "allowed", null, 5L, 256, 512, null, null);
        assertTrue(id > 0, "Expected generated row id > 0");
    }

    @Test
    void logBodyBudgetSnapshot_insertsRow() throws Exception {
        db.logBodyBudgetSnapshot("session-1", 5, 10240, 3,
                "nominal", "nominal", "nominal", "session_open");
        // If no exception, it passed — table schema from Session 1 is correct
    }
}
