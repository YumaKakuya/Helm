package dev.sorted.mcphub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and records body-budget snapshots. Spec: Chapter 8 §8.5 (IS-09)
 *
 * REQ-8.5.1: Body-budget = total inline builtin schema bytes.
 * REQ-8.5.2: Also tracks inline builtin tool count.
 * REQ-8.5.3: Evaluate both thresholds; effective tier = worst.
 * REQ-8.5.5: Thresholds configurable.
 */
public class BodyBudgetService {
    private static final Logger log = LoggerFactory.getLogger(BodyBudgetService.class);

    // Configurable thresholds (REQ-8.5.5)
    private int totalRegisteredHatchTools = 8;    // Probe E safe ceiling
    private int baselineSchemaKbPerTool = 2048;   // bytes per tool schema estimate
    private int warningToolCount = 9;
    private int criticalToolCount = 11;
    private int warningByteSize = 30 * 1024;      // 30 KB
    private int criticalByteSize = 50 * 1024;     // 50 KB

    private final DatabaseManager db;
    private int mcphubHostedToolCount = 0;
    private String lastEffectiveTier = "nominal";

    public BodyBudgetService(DatabaseManager db) {
        this.db = db;
    }

    /** Update the count of tools MCPHUB is hosting. Call when registry is loaded. */
    public void setMcphubHostedToolCount(int count) {
        this.mcphubHostedToolCount = count;
    }

    /** Compute snapshot and write to DB. Returns effective tier string. REQ-8.5.6 */
    public String recordSnapshot(String sessionId, String trigger) {
        int inlineBuiltinCount = Math.max(0, totalRegisteredHatchTools - mcphubHostedToolCount);
        // REQ-8.5.1a: inline builtin schema bytes = inline tools × baseline bytes per tool
        int schemaBytes = inlineBuiltinCount * baselineSchemaKbPerTool;

        String tierToolCount = computeToolCountTier(inlineBuiltinCount);
        String tierByteSize = computeByteSizeTier(schemaBytes);
        String effectiveTier = worstTier(tierToolCount, tierByteSize);

        db.logBodyBudgetSnapshot(sessionId, inlineBuiltinCount, schemaBytes,
                mcphubHostedToolCount, tierToolCount, tierByteSize, effectiveTier, trigger);

        // Alert on tier transitions (REQ-8.5.8, REQ-8.5.9)
        if (!effectiveTier.equals(lastEffectiveTier)) {
            if ("warning".equals(effectiveTier)) {
                log.warn("Body-budget tier transition: {} -> warning (inline={}, bytes={})",
                        lastEffectiveTier, inlineBuiltinCount, schemaBytes);
                db.logFailure(sessionId, null, null,
                        "body_budget_warning",
                        "Body-budget warning: inline_count=" + inlineBuiltinCount + " bytes=" + schemaBytes,
                        "none", null);
            } else if ("critical".equals(effectiveTier)) {
                log.error("Body-budget CRITICAL: inline={}, bytes={}", inlineBuiltinCount, schemaBytes);
                db.logFailure(sessionId, null, null,
                        "body_budget_critical",
                        "Body-budget critical: inline_count=" + inlineBuiltinCount + " bytes=" + schemaBytes,
                        "none", null);
            }
            lastEffectiveTier = effectiveTier;
        }

        return effectiveTier;
    }

    /** Compute tool-count tier. REQ-8.5.2, thresholds per §8.5.2 */
    public String computeToolCountTier(int inlineBuiltinCount) {
        if (inlineBuiltinCount >= criticalToolCount) return "critical";
        if (inlineBuiltinCount >= warningToolCount) return "warning";
        return "nominal";
    }

    /** Compute byte-size tier. §8.5.2 */
    public String computeByteSizeTier(int bytes) {
        if (bytes > criticalByteSize) return "critical";
        if (bytes > warningByteSize) return "warning";
        return "nominal";
    }

    /** Worst of two tiers. REQ-8.5.3 */
    public String worstTier(String a, String b) {
        int ra = tierRank(a), rb = tierRank(b);
        return ra >= rb ? a : b;
    }

    private int tierRank(String tier) {
        return switch (tier) {
            case "critical" -> 2;
            case "warning"  -> 1;
            default         -> 0;
        };
    }

    // Setters for config override (REQ-8.5.5)
    public void setTotalRegisteredHatchTools(int n) { this.totalRegisteredHatchTools = n; }
    public void setBaselineSchemaKbPerTool(int bytes) { this.baselineSchemaKbPerTool = bytes; }
    public void setWarningToolCount(int n) { this.warningToolCount = n; }
    public void setCriticalToolCount(int n) { this.criticalToolCount = n; }
    public void setWarningByteSize(int bytes) { this.warningByteSize = bytes; }
    public void setCriticalByteSize(int bytes) { this.criticalByteSize = bytes; }

    public int getMcphubHostedToolCount() { return mcphubHostedToolCount; }
    public String getLastEffectiveTier() { return lastEffectiveTier; }
}
