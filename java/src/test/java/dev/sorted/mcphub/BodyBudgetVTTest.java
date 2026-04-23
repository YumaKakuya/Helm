package dev.sorted.mcphub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VT-027 〜 VT-033 — body-budget alerting verification (Ch.10 §10.3.6).
 *
 * REQ-10.3.8: Uses controlled synthetic inputs via BodyBudgetService setters
 * (no external Anthropic API interaction required).
 */
class BodyBudgetVTTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;
    private BodyBudgetService svc;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        db.open();
        svc = new BodyBudgetService(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void vt027_nominalTier_noAlert() throws Exception {
        // 8 inline builtins => nominal count; 8*2048=16384 bytes => nominal bytes
        svc.setTotalRegisteredHatchTools(8);
        svc.setMcphubHostedToolCount(0);

        String tier = svc.recordSnapshot("vt027-session", "session_open");

        assertEquals("nominal", tier, "VT-027: effective_tier must be nominal");
        assertEquals(0, countFailureClassLike("body_budget%"), "VT-027: no body-budget alert expected");
    }

    @Test
    void vt028_warningTier_countDriven_alertLogged() throws Exception {
        // 9 inline builtins => warning count tier, force bytes nominal
        svc.setTotalRegisteredHatchTools(9);
        svc.setMcphubHostedToolCount(0);
        svc.setBaselineSchemaKbPerTool(1024); // 9KB total

        String tier = svc.recordSnapshot("vt028-session", "session_open");

        assertEquals("warning", tier, "VT-028: effective_tier must be warning");
        assertTrue(countFailureClassLike("body_budget_warning") >= 1,
                "VT-028: failure_log must contain body_budget_warning");
    }

    @Test
    void vt029_criticalTier_countDriven_alertLogged() throws Exception {
        // 11 inline builtins => critical count tier, force bytes nominal
        svc.setTotalRegisteredHatchTools(11);
        svc.setMcphubHostedToolCount(0);
        svc.setBaselineSchemaKbPerTool(1024); // 11KB total

        String tier = svc.recordSnapshot("vt029-session", "session_open");

        assertEquals("critical", tier, "VT-029: effective_tier must be critical");
        assertTrue(countFailureClassLike("body_budget_critical") >= 1,
                "VT-029: failure_log must contain body_budget_critical");
    }

    @Test
    void vt030_warningTier_byteDriven() throws Exception {
        // 5 inline tools => nominal count; 5 * 7KB = 35KB => warning byte tier
        svc.setTotalRegisteredHatchTools(5);
        svc.setMcphubHostedToolCount(0);
        svc.setBaselineSchemaKbPerTool(7 * 1024);

        String tier = svc.recordSnapshot("vt030-session", "session_open");

        assertEquals("warning", tier, "VT-030: byte-driven warning expected");
        assertEquals("warning", getLatestSnapshotTierByteSize("vt030-session"),
                "VT-030: tier_byte_size must be warning");
    }

    @Test
    void vt031_criticalTier_byteDriven() throws Exception {
        // 5 inline tools => nominal count; 5 * 11KB = 55KB => critical byte tier
        svc.setTotalRegisteredHatchTools(5);
        svc.setMcphubHostedToolCount(0);
        svc.setBaselineSchemaKbPerTool(11 * 1024);

        String tier = svc.recordSnapshot("vt031-session", "session_open");

        assertEquals("critical", tier, "VT-031: byte-driven critical expected");
        assertEquals("critical", getLatestSnapshotTierByteSize("vt031-session"),
                "VT-031: tier_byte_size must be critical");
    }

    @Test
    void vt032_mixedTiers_worstWins() throws Exception {
        // 8 inline tools => nominal count; 8 * 5KB = 40KB => warning bytes; worst => warning
        svc.setTotalRegisteredHatchTools(8);
        svc.setMcphubHostedToolCount(0);
        svc.setBaselineSchemaKbPerTool(5 * 1024);

        String tier = svc.recordSnapshot("vt032-session", "session_open");

        assertEquals("warning", tier, "VT-032: effective_tier must be warning (worst tier)");
    }

    @Test
    void vt033_snapshotsTriggeredOnSessionOpenAndClose() throws Exception {
        svc.setTotalRegisteredHatchTools(5);
        svc.setMcphubHostedToolCount(0);
        String sessionId = "vt033-session";

        svc.recordSnapshot(sessionId, "session_open");
        svc.recordSnapshot(sessionId, "provider_change");
        svc.recordSnapshot(sessionId, "session_close");

        int count = countBodyBudgetSnapshots(sessionId);
        assertTrue(count >= 3, "VT-033: expected >=3 snapshots for session, got " + count);
        assertTrue(hasSnapshotTrigger(sessionId, "session_open"), "session_open trigger must be recorded");
        assertTrue(hasSnapshotTrigger(sessionId, "provider_change"), "provider_change trigger must be recorded");
        assertTrue(hasSnapshotTrigger(sessionId, "session_close"), "session_close trigger must be recorded");
    }

    private int countFailureClassLike(String failureClassPattern) throws Exception {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM failure_log WHERE failure_class LIKE ?")) {
            ps.setString(1, failureClassPattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String getLatestSnapshotTierByteSize(String sessionId) throws Exception {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT tier_byte_size FROM body_budget_snapshot WHERE session_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Expected at least one body_budget_snapshot row for session " + sessionId);
                return rs.getString(1);
            }
        }
    }

    private int countBodyBudgetSnapshots(String sessionId) throws Exception {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM body_budget_snapshot WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean hasSnapshotTrigger(String sessionId, String trigger) throws Exception {
        Connection c = db.getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM body_budget_snapshot WHERE session_id = ? AND \"trigger\" = ? LIMIT 1")) {
            ps.setString(1, sessionId);
            ps.setString(2, trigger);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
