package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dispatches JSON-RPC control methods to the state machine.
 * Spec: Chapter 2 §2.6 (control surface), Chapter 3 §3.10 (observability)
 *
 * Supported methods:
 *   mcphub.control.status    — REQ-3.10.3
 *   mcphub.control.arm       — REQ-3.4.1 (Closed → Armed)
 *   mcphub.control.open      — REQ-3.4.1 (Armed → Open)
 *   mcphub.control.close     — REQ-3.4.1 (Open → CoolingDown or Armed → Closed)
 *   mcphub.control.lock      — REQ-3.8.1 (emergency lock)
 *   mcphub.control.unlock    — REQ-3.8.8 (clear locked_until_unlock)
 *   mcphub.control.health    — REQ-2.6.1 (liveness)
 *   mcphub.control.capabilities — REQ-4.7.1
 */
public class ControlHandler implements JsonRpcServer.MethodHandler {
    private static final Logger log = LoggerFactory.getLogger(ControlHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final StateMachine stateMachine;
    private final SessionManager sessionManager;
    private final DatabaseManager db;
    private final Instant startTime = Instant.now();

    // These are set by the extended constructor; null in backward-compat mode
    private CapabilityRegistry registry;
    private PolicyEngine policy;
    private BodyBudgetService bodyBudget;
    private ProviderHealthTracker healthTracker;
    private final AtomicInteger activeBridgeCount = new AtomicInteger(0);
    private final Set<Long> bridgePids = ConcurrentHashMap.newKeySet();
    private final Map<Long, Instant> bridgeLastPing = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitorExecutor;

    /** Backward-compatible constructor (Session 1 tests). */
    public ControlHandler(StateMachine stateMachine, SessionManager sessionManager,
                          DatabaseManager db) {
        this.stateMachine = stateMachine;
        this.sessionManager = sessionManager;
        this.db = db;
        this.registry = null;
        this.policy = null;
        this.bodyBudget = null;
        this.healthTracker = null;
        this.janitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcphub-bridge-janitor");
            t.setDaemon(true);
            return t;
        });
        wireCallbacks();
        janitorExecutor.scheduleWithFixedDelay(this::janitorTask, 60, 60, TimeUnit.SECONDS);
    }

    /** Full constructor for Session 2+. */
    public ControlHandler(StateMachine stateMachine, SessionManager sessionManager,
                          DatabaseManager db, CapabilityRegistry registry,
                          PolicyEngine policy, BodyBudgetService bodyBudget) {
        this.stateMachine = stateMachine;
        this.sessionManager = sessionManager;
        this.db = db;
        this.registry = registry;
        this.policy = policy;
        this.bodyBudget = bodyBudget;
        this.healthTracker = null;
        this.janitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcphub-bridge-janitor");
            t.setDaemon(true);
            return t;
        });
        wireCallbacks();
        janitorExecutor.scheduleWithFixedDelay(this::janitorTask, 60, 60, TimeUnit.SECONDS);
    }

    /** Shutdown the bridge janitor executor. Call on daemon shutdown or test teardown. */
    public void shutdown() {
        janitorExecutor.shutdownNow();
    }

    /** Optional wiring for REQ-4.7.2 runtime provider health visibility. */
    public void setHealthTracker(ProviderHealthTracker healthTracker) {
        this.healthTracker = healthTracker;
    }

    private void wireCallbacks() {
        // Wire state-change listener to DB logging (REQ-3.10.2)
        stateMachine.setStateChangeListener((from, to, trigger, sessionId) -> {
            if (db != null) {
                String fromStr = from != null ? from.name() : null;
                db.logSessionEvent(
                    sessionId != null ? sessionId : "no-session",
                    "state_change", fromStr, to.name(), trigger.name().toLowerCase()
                );
            }
            // Body budget snapshot on session open/close (REQ-8.5.6)
            if (bodyBudget != null) {
                if (to == StateMachine.State.OPEN) {
                    bodyBudget.recordSnapshot(sessionId, "session_open");
                } else if (to == StateMachine.State.COOLING_DOWN) {
                    bodyBudget.recordSnapshot(sessionId, "session_close");
                }
            }
            // Clear session-scoped policy rules on session end (REQ-7.3.11)
            if (policy != null && to == StateMachine.State.COOLING_DOWN) {
                policy.clearSessionRules();
            }
            // Reset adapter confirmation runtime state on session close.
            if (registry != null && to == StateMachine.State.COOLING_DOWN) {
                registry.resetRuntimeStates();
            }
            // Reset runtime provider health cache on session close.
            if (healthTracker != null && to == StateMachine.State.COOLING_DOWN) {
                healthTracker.clear();
            }
            // Defensive cleanup when session fully closes
            if (to == StateMachine.State.CLOSED) {
                activeBridgeCount.set(0);
                bridgePids.clear();
                bridgeLastPing.clear();
                log.info("Session closed, active bridges reset to 0");
            }
        });

        // Wire session timeout callbacks
        sessionManager.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            @Override
            public void onIdleTimeout(String sessionId) {
                if (activeBridgeCount.get() > 0) {
                    log.debug("Idle timeout suppressed: {} bridges attached", activeBridgeCount.get());
                    return;
                }
                log.info("Idle timeout for session {}", sessionId);
                try {
                    stateMachine.transition(StateMachine.Trigger.IDLE_TIMEOUT, sessionId);
                    doCoolingDownAndClose(sessionId, "idle_timeout");
                } catch (StateMachine.TransitionException e) {
                    log.warn("Idle timeout transition failed: {}", e.getMessage());
                }
            }
            @Override
            public void onArmTimeout(String sessionId) {
                log.info("Arm timeout for session {}", sessionId);
                try {
                    stateMachine.transition(StateMachine.Trigger.ARM_TIMEOUT, sessionId);
                    sessionManager.endSession();
                } catch (StateMachine.TransitionException e) {
                    log.warn("Arm timeout transition failed: {}", e.getMessage());
                }
            }
        });
    }

    @Override
    public JsonNode handle(String method, JsonNode params)
            throws JsonRpcServer.JsonRpcException {
        // REQ-3.7.3: every control message for the active session resets the idle timer.
        // Status/health/capabilities are read-only queries that MUST keep the session alive
        // while a bridge is actively querying.
        if (stateMachine.getState() == StateMachine.State.OPEN
                && (method.equals("mcphub.control.status")
                    || method.equals("mcphub.control.health")
                    || method.equals("mcphub.control.capabilities"))) {
            sessionManager.resetActivity();
        }
        return switch (method) {
            case "mcphub.control.status"           -> handleStatus();
            case "mcphub.control.arm"              -> handleArm();
            case "mcphub.control.open"             -> handleOpen();
            case "mcphub.control.close"            -> handleClose();
            case "mcphub.control.bridge_attach"-> handleBridgeAttach(params);
            case "mcphub.control.bridge_ping"  -> handleBridgePing(params);
            case "mcphub.control.bridge_detach"-> handleBridgeDetach(params);
            case "mcphub.control.lock"             -> handleLock(params);
            case "mcphub.control.unlock"           -> handleUnlock();
            case "mcphub.control.health"           -> handleHealth();
            case "mcphub.control.capabilities"     -> handleCapabilities();
            case "mcphub.control.add_session_rule" -> handleAddSessionRule(params);
            default -> throw new JsonRpcServer.JsonRpcException(
                JsonRpcServer.ERR_NOT_FOUND, "Unknown method: " + method);
        };
    }

    // -------------------------------------------------------------------------
    // Method handlers
    // -------------------------------------------------------------------------

    /**
     * mcphub.control.status — REQ-3.10.3
     * Returns: state, session_id, seconds_since_transition, in_flight_count,
     *          locked_until_unlock, uptime_seconds
     */
    private JsonNode handleStatus() {
        ObjectNode r = mapper.createObjectNode();
        r.put("state", stateMachine.getState().name());
        String sid = sessionManager.getCurrentSessionId();
        if (sid != null) r.put("session_id", sid);
        else r.putNull("session_id");
        r.put("seconds_since_transition",
            sessionManager.secondsSinceLastTransition(stateMachine.getLastTransition()));
        r.put("in_flight_count", sessionManager.getInFlightCount());
        r.put("locked_until_unlock", stateMachine.isLockedUntilUnlock());
        r.put("uptime_seconds", Instant.now().getEpochSecond() - startTime.getEpochSecond());
        return r;
    }

    /** mcphub.control.arm — Closed → Armed */
    private JsonNode handleArm() throws JsonRpcServer.JsonRpcException {
        try {
            String sessionId = sessionManager.startSession();
            StateMachine.State newState =
                stateMachine.transition(StateMachine.Trigger.ARM, sessionId);
            ObjectNode r = mapper.createObjectNode();
            r.put("state", newState.name());
            r.put("session_id", sessionId);
            return r;
        } catch (StateMachine.TransitionException e) {
            throw new JsonRpcServer.JsonRpcException(-32001, e.getMessage());
        }
    }

    /** mcphub.control.open — Armed → Open */
    private JsonNode handleOpen() throws JsonRpcServer.JsonRpcException {
        try {
            String sessionId = sessionManager.getCurrentSessionId();
            StateMachine.State newState =
                stateMachine.transition(StateMachine.Trigger.OPEN, sessionId);
            sessionManager.onOpen();
            ObjectNode r = mapper.createObjectNode();
            r.put("state", newState.name());
            r.put("session_id", sessionId);
            // REQ-6.2.3: Java decides → Go executes. Directive to Go to start provider groups.
            r.put("mcphub_providers", "start");
            return r;
        } catch (StateMachine.TransitionException e) {
            throw new JsonRpcServer.JsonRpcException(-32001, e.getMessage());
        }
    }

    /** mcphub.control.close — Open → CoolingDown or Armed → Closed */
    private JsonNode handleClose() throws JsonRpcServer.JsonRpcException {
        try {
            String sessionId = sessionManager.getCurrentSessionId();
            StateMachine.State current = stateMachine.getState();
            StateMachine.State newState;

            if (current == StateMachine.State.ARMED) {
                newState = stateMachine.transition(StateMachine.Trigger.CLOSE, sessionId);
                sessionManager.endSession();
            } else {
                newState = stateMachine.transition(StateMachine.Trigger.CLOSE, sessionId);
                // Simulate drain completion (Session 1: immediate)
                doCoolingDownAndClose(sessionId, "cli_close");
                newState = stateMachine.getState();
            }
            ObjectNode r = mapper.createObjectNode();
            r.put("state", newState.name());
            // REQ-6.2.3: Java decides → Go executes. Directive to Go to stop provider groups.
            r.put("mcphub_providers", "stop");
            return r;
        } catch (StateMachine.TransitionException e) {
            throw new JsonRpcServer.JsonRpcException(-32001, e.getMessage());
        }
    }

    /** mcphub.control.lock — emergency lock (REQ-3.8.1–3.8.5) */
    private JsonNode handleLock(JsonNode params) throws JsonRpcServer.JsonRpcException {
        String reason = "manual";
        if (params != null && params.has("lock_reason")) {
            reason = params.get("lock_reason").asText("manual");
        }
        try {
            String sessionId = sessionManager.getCurrentSessionId();
            stateMachine.transition(StateMachine.Trigger.LOCK, sessionId);
            sessionManager.endSession();
            log.warn("Emergency lock activated. Reason: {}", reason);
            ObjectNode r = mapper.createObjectNode();
            r.put("state", stateMachine.getState().name());
            r.put("locked_until_unlock", true);
            r.put("lock_reason", reason);
            return r;
        } catch (StateMachine.TransitionException e) {
            throw new JsonRpcServer.JsonRpcException(-32001, e.getMessage());
        }
    }

    /** mcphub.control.unlock — clear locked_until_unlock (REQ-3.8.8) */
    private JsonNode handleUnlock() {
        stateMachine.setLock(false);
        log.info("locked_until_unlock cleared");
        ObjectNode r = mapper.createObjectNode();
        r.put("state", stateMachine.getState().name());
        r.put("locked_until_unlock", false);
        return r;
    }

    /** mcphub.control.health — liveness check (REQ-2.6.1) */
    private JsonNode handleHealth() {
        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        r.put("state", stateMachine.getState().name());
        return r;
    }

    /** mcphub.control.capabilities — REQ-4.7.1 full contract + health */
    private JsonNode handleCapabilities() {
        ObjectNode r = mapper.createObjectNode();
        if (registry == null) {
            r.put("note", "Capability registry not yet loaded");
            r.putArray("capabilities");
            return r;
        }
        ArrayNode caps = mapper.createArrayNode();
        List<CapabilityEntry> entries = policy != null
                ? policy.filterForOperator(registry.getAll())
                : registry.getAll();
        for (CapabilityEntry e : entries) {
            ObjectNode cap = mapper.createObjectNode();
            cap.put("capability_id", e.capabilityId);
            cap.put("display_name", e.displayName);
            cap.put("provider_id", e.providerId);
            cap.put("access_class", e.accessClass != null ? e.accessClass : "");
            cap.put("rw_boundary", e.rwBoundary != null ? e.rwBoundary : "");
            cap.put("enabled", e.enabled);
            // Provider health from runtime updates sent by Go daemon (REQ-4.7.2)
            String providerHealth = "unavailable";
            if (healthTracker != null) {
                providerHealth = healthTracker.healthForTool(e.displayName);
            }
            cap.put("provider_health", providerHealth);
            // Policy decision
            if (policy != null) {
                PolicyEngine.PolicyResult pr = policy.evaluate(e.displayName);
                cap.put("policy_decision", pr.decision().name().toLowerCase());
                if (pr.matchedRuleId() != null) cap.put("policy_rule_id", pr.matchedRuleId());
            }
            // Full contract (REQ-4.7.1 — operator needs full visibility)
            if (e.contract != null) {
                ObjectNode contract = mapper.createObjectNode();
                contract.put("purpose", e.contract.purpose != null ? e.contract.purpose : "");
                contract.put("side_effect_class", e.contract.sideEffectClass != null ? e.contract.sideEffectClass : "");
                if (e.contract.mayDo != null) {
                    ArrayNode mayDo = mapper.createArrayNode();
                    e.contract.mayDo.forEach(mayDo::add);
                    contract.set("may_do", mayDo);
                }
                if (e.contract.mustNotDo != null) {
                    ArrayNode mustNotDo = mapper.createArrayNode();
                    e.contract.mustNotDo.forEach(mustNotDo::add);
                    contract.set("must_not_do", mustNotDo);
                }
                if (e.contract.whenToCall != null) {
                    ArrayNode whenToCall = mapper.createArrayNode();
                    e.contract.whenToCall.forEach(whenToCall::add);
                    contract.set("when_to_call", whenToCall);
                }
                if (e.contract.whenNotToCall != null) {
                    ArrayNode whenNotToCall = mapper.createArrayNode();
                    e.contract.whenNotToCall.forEach(whenNotToCall::add);
                    contract.set("when_not_to_call", whenNotToCall);
                }
                if (e.contract.disambiguatesFrom != null) {
                    ArrayNode disambig = mapper.createArrayNode();
                    e.contract.disambiguatesFrom.forEach(df -> {
                        ObjectNode d = mapper.createObjectNode();
                        d.put("capability_id", df.capabilityId);
                        d.put("distinction", df.distinction);
                        disambig.add(d);
                    });
                    contract.set("disambiguates_from", disambig);
                }
                cap.set("contract", contract);
            }
            caps.add(cap);
        }
        r.set("capabilities", caps);
        r.put("loaded_count", registry.getLoadedCount());
        r.put("rejected_count", registry.getRejectedCount());
        return r;
    }

    /** mcphub.control.bridge_attach — increment bridge count; suspend idle timer on first attach. */
    private JsonNode handleBridgeAttach(JsonNode params) {
        long pid = params != null && params.has("pid") ? params.get("pid").asLong(-1) : -1;
        // Deduplicate: same PID re-attaching is idempotent
        boolean isNew = pid >= 0 && bridgePids.add(pid);
        if (isNew) {
            int count = activeBridgeCount.incrementAndGet();
            bridgeLastPing.put(pid, Instant.now());
            if (count == 1) {
                sessionManager.suspendIdleTimer();
            }
            log.info("Bridge attached (pid={}). Active bridges: {}", pid, count);
        } else if (pid >= 0) {
            // Re-attach from same PID: update ping timestamp only
            bridgeLastPing.put(pid, Instant.now());
            log.debug("Bridge re-attached (pid={}, idempotent). Active bridges: {}", pid, activeBridgeCount.get());
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        r.put("active_bridges", activeBridgeCount.get());
        return r;
    }

    /** mcphub.control.bridge_ping — update last-ping timestamp for a known bridge. */
    private JsonNode handleBridgePing(JsonNode params) {
        long pid = params != null && params.has("pid") ? params.get("pid").asLong(-1) : -1;
        if (pid >= 0 && bridgePids.contains(pid)) {
            bridgeLastPing.put(pid, Instant.now());
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        return r;
    }

    /** mcphub.control.bridge_detach — decrement bridge count; resume idle timer when last bridge leaves. */
    private JsonNode handleBridgeDetach(JsonNode params) {
        long pid = params != null && params.has("pid") ? params.get("pid").asLong(-1) : -1;
        // Only decrement if this PID was a known attached bridge
        boolean wasKnown = pid >= 0 && bridgePids.remove(pid);
        if (wasKnown) {
            bridgeLastPing.remove(pid);
            int count = activeBridgeCount.updateAndGet(c -> c > 0 ? c - 1 : 0);
            log.info("Bridge detached (pid={}). Active bridges: {}", pid, count);
            if (count == 0) {
                sessionManager.resumeIdleTimer();
                bridgePids.clear();
                bridgeLastPing.clear();
            }
        } else {
            log.debug("Bridge detach for unknown pid={}, ignoring", pid);
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        r.put("active_bridges", activeBridgeCount.get());
        return r;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** mcphub.control.add_session_rule — REQ-7.5.1 */
    private JsonNode handleAddSessionRule(JsonNode params) throws JsonRpcServer.JsonRpcException {
        StateMachine.State current = stateMachine.getState();
        if (current != StateMachine.State.ARMED && current != StateMachine.State.OPEN) {
            throw new JsonRpcServer.JsonRpcException(-32001,
                "add_session_rule requires Armed or Open state");
        }
        if (params == null || !params.has("tool_pattern") || !params.has("action")) {
            throw new JsonRpcServer.JsonRpcException(-32602,
                "Missing required params: tool_pattern and action");
        }
        String toolPattern = params.get("tool_pattern").asText();
        String action = params.get("action").asText().toLowerCase();
        if (!"allow".equals(action) && !"deny".equals(action) && !"hide".equals(action)) {
            throw new JsonRpcServer.JsonRpcException(-32602,
                "Invalid action: " + action + ". Must be allow, deny, or hide.");
        }

        PolicyRule rule = new PolicyRule();
        rule.ruleId = params.has("rule_id") ? params.get("rule_id").asText()
                : ("session-" + toolPattern + "-" + action);
        rule.toolPattern = toolPattern;
        rule.action = action;
        rule.priority = params.has("priority") ? params.get("priority").asInt(100) : 100;
        rule.scope = "session";

        policy.addSessionRule(rule);
        log.info("Added session rule: id={}, pattern={}, action={}", rule.ruleId, toolPattern, action);

        ObjectNode r = mapper.createObjectNode();
        r.put("status", "ok");
        r.put("rule_id", rule.ruleId);
        r.put("scope", "session");
        r.put("state", current.name());
        return r;
    }

    /** Simulate drain and transition to CLOSED. (Session 1: immediate drain) */
    private void doCoolingDownAndClose(String sessionId, String trigger) {
        try {
            // Wait for in-flight = 0 (immediate in Session 1 since no real providers)
            stateMachine.transition(StateMachine.Trigger.DRAIN_COMPLETE, sessionId);
            sessionManager.endSession();
        } catch (StateMachine.TransitionException e) {
            log.warn("CoolingDown→Closed transition failed: {}", e.getMessage());
        }
    }

    /** Janitor: detect crashed bridges and clean up counts. */
    private void janitorTask() {
        try {
            Instant now = Instant.now();
            for (Long pid : new ArrayList<>(bridgePids)) {
                Instant lastPing = bridgeLastPing.get(pid);
                if (lastPing == null) {
                    lastPing = Instant.now();
                }
                if (now.getEpochSecond() - lastPing.getEpochSecond() > 180) {
                    boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                    if (!alive) {
                        log.warn("Bridge pid {} appears dead, removing", pid);
                        bridgePids.remove(pid);
                        bridgeLastPing.remove(pid);
                        int count = activeBridgeCount.updateAndGet(c -> c > 0 ? c - 1 : 0);
                        if (count == 0) {
                            sessionManager.resumeIdleTimer();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Bridge janitor task failed: {}", e.getMessage());
        }
    }
}
