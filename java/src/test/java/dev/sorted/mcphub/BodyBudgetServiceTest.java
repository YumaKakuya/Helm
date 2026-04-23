package dev.sorted.mcphub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BodyBudgetService.
 * Spec: Chapter 8 §8.5 (IS-09)
 */
class BodyBudgetServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;
    private BodyBudgetService service;

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseManager(tempDir.resolve("test.db").toString());
        db.open();
        service = new BodyBudgetService(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    // --- computeToolCountTier ---

    @Test
    void computeToolCountTier_nominal() {
        assertEquals("nominal", service.computeToolCountTier(5));
    }

    @Test
    void computeToolCountTier_warning() {
        assertEquals("warning", service.computeToolCountTier(9));
    }

    @Test
    void computeToolCountTier_critical() {
        assertEquals("critical", service.computeToolCountTier(11));
    }

    // --- computeByteSizeTier ---

    @Test
    void computeByteSizeTier_nominal() {
        assertEquals("nominal", service.computeByteSizeTier(20 * 1024));
    }

    @Test
    void computeByteSizeTier_warning() {
        assertEquals("warning", service.computeByteSizeTier(35 * 1024));
    }

    @Test
    void computeByteSizeTier_critical() {
        assertEquals("critical", service.computeByteSizeTier(55 * 1024));
    }

    // --- worstTier ---

    @Test
    void worstTier() {
        assertEquals("warning", service.worstTier("warning", "nominal"));
    }

    @Test
    void worstTier_critical_wins() {
        assertEquals("critical", service.worstTier("warning", "critical"));
    }

    // --- recordSnapshot DB integration ---

    @Test
    void recordSnapshot_writes_to_db() throws Exception {
        service.recordSnapshot("session-bbs-1", "test_trigger");

        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM body_budget_snapshot WHERE session_id='session-bbs-1'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Expected exactly 1 row in body_budget_snapshot");
        }
    }

    // --- setMcphubHostedToolCount affects inlineBuiltinCount ---

    @Test
    void setMcphubHostedTools_affects_inline_count() throws Exception {
        // totalRegisteredHatchTools = 8 (default), mcphubHostedToolCount = 8
        // => inlineBuiltinCount = max(0, 8 - 8) = 0 => nominal
        service.setMcphubHostedToolCount(8);
        String tier = service.recordSnapshot("session-bbs-2", "test");

        // Verify DB row has inline_builtin_count = 0
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT inline_builtin_count FROM body_budget_snapshot WHERE session_id='session-bbs-2'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("inline_builtin_count"),
                    "inlineBuiltinCount should be 0 when hosted == total");
        }
        assertEquals("nominal", tier);
    }
}
