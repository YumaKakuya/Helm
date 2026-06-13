package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task-context-linked filtering.
 * Proposal §6.5 / OS-14 → Alpha scope (CEO 2026-04-26).
 *
 * Reduces visible tools according to current task context. In alpha,
 * this operates via explicit context signals rather than AXIS auto-detection:
 *
 *   1. MCP tool: mcphub_set_task_context — AI sets the current task type
 *   2. Context-to-tool mapping: defined in config or programmatically
 *   3. Effect: session-scoped HIDE rules for tools not relevant to current task
 *
 * Task contexts (alpha):
 *   - "coding"     → code tools visible, web tools secondary
 *   - "research"   → web tools visible, edit tools secondary
 *   - "planning"   → session/planning tools visible
 *   - "all"        → no filtering (default)
 *
 * Future: AXIS integration can auto-set task context based on conversation signals.
 */
public class TaskContextFilter {
    private static final Logger log = LoggerFactory.getLogger(TaskContextFilter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Predefined task context profiles with tool relevance mappings. */
    public enum TaskContext {
        ALL,        // No filtering
        CODING,     // Code editing, search, LSP
        RESEARCH,   // Web fetch, web search
        PLANNING    // Plan enter/exit, todo, skill
    }

    /** Tools considered primary for each context. Tools not in the primary set are hidden. */
    private static final Set<String> CODING_TOOLS = Set.of(
            "apply_patch", "codesearch", "lsp", "list", "todowrite");
    private static final Set<String> RESEARCH_TOOLS = Set.of(
            "webfetch", "websearch");
    private static final Set<String> PLANNING_TOOLS = Set.of(
            "plan_enter", "plan_exit", "skill", "batch", "todowrite");

    private volatile TaskContext currentContext = TaskContext.ALL;
    private final List<PolicyRule> contextRules = new ArrayList<>();

    /** Get the current task context. */
    public TaskContext getCurrentContext() {
        return currentContext;
    }

    /**
     * Set the task context and generate session-scoped HIDE rules for irrelevant tools.
     *
     * @param contextName task context name ("coding", "research", "planning", "all")
     * @param policy      policy engine to inject session rules
     * @param registry    capability registry to enumerate tools
     * @return the applied context name
     */
    public String setContext(String contextName, PolicyEngine policy, CapabilityRegistry registry) {
        TaskContext ctx;
        try {
            ctx = TaskContext.valueOf(contextName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown task context '{}', defaulting to ALL", contextName);
            ctx = TaskContext.ALL;
        }

        // Clear previous context rules
        clearContextRules(policy);
        currentContext = ctx;

        if (ctx == TaskContext.ALL) {
            log.info("Task context set to ALL — no tool filtering");
            return "all";
        }

        Set<String> primaryTools = switch (ctx) {
            case CODING -> CODING_TOOLS;
            case RESEARCH -> RESEARCH_TOOLS;
            case PLANNING -> PLANNING_TOOLS;
            default -> Set.of();
        };

        // Generate HIDE rules for tools NOT in the primary set
        List<CapabilityEntry> allTools = registry.getAll();
        int ruleCount = 0;
        for (CapabilityEntry entry : allTools) {
            if (!primaryTools.contains(entry.displayName) && entry.enabled) {
                PolicyRule hideRule = new PolicyRule();
                hideRule.ruleId = "task-ctx-hide-" + entry.displayName;
                hideRule.toolPattern = entry.displayName;
                hideRule.action = "hide";
                hideRule.priority = 100; // Session boost will make this override global rules
                hideRule.scope = "session";
                policy.addSessionRule(hideRule);
                contextRules.add(hideRule);
                ruleCount++;
            }
        }

        log.info("Task context set to {} — {} tools hidden, {} primary tools visible",
                ctx, ruleCount, primaryTools.size());
        return ctx.name().toLowerCase();
    }

    /** Clear all context-generated session rules. */
    public void clearContextRules(PolicyEngine policy) {
        if (!contextRules.isEmpty()) {
            // Session rules are cleared as a batch; we can't selectively remove.
            // Context rules are a subset of session rules. On context change,
            // we rely on clearSessionRules() being called on session close,
            // or we track and remove individually.
            // For alpha: context change clears all session rules and re-applies.
            // This is acceptable because session rules are lightweight.
            policy.clearSessionRules();
            contextRules.clear();
            log.info("Task context rules cleared");
        }
    }

    /** Reset to ALL context. Called on session close. */
    public void reset() {
        currentContext = TaskContext.ALL;
        contextRules.clear();
    }

    /**
     * Build the MCP tool response for mcphub_set_task_context.
     */
    public static ObjectNode buildResponse(String appliedContext, int hiddenCount, int visibleCount) {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode textItem = mapper.createObjectNode();
        textItem.put("type", "text");

        ObjectNode result = mapper.createObjectNode();
        result.put("task_context", appliedContext);
        result.put("hidden_tools", hiddenCount);
        result.put("visible_tools", visibleCount);
        result.put("note", "Task context applied. tools/list will now return only tools relevant to '" +
                appliedContext + "' context. Set context to 'all' to restore full tool list.");

        textItem.put("text", result.toString());
        content.add(textItem);
        r.set("content", content);
        return r;
    }
}
