package dev.sorted.mcphub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages session lifecycle: UUID generation, idle timer, arm timeout.
 * Spec: Chapter 3 §3.6, §3.7
 */
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** REQ-3.7.4: default idle timeout 300 seconds. */
    public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 300;
    /** REQ-3.7.8: default arm timeout 60 seconds. */
    public static final long DEFAULT_ARM_TIMEOUT_SECONDS = 60;

    public interface TimeoutCallback {
        void onIdleTimeout(String sessionId);
        void onArmTimeout(String sessionId);
    }

    private final ScheduledExecutorService scheduler;
    private volatile String currentSessionId;
    private volatile Instant sessionStart;
    private volatile Instant lastActivity;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    private final long idleTimeoutSeconds;
    private final long armTimeoutSeconds;
    private TimeoutCallback timeoutCallback;
    private ScheduledFuture<?> idleTimerFuture;
    private ScheduledFuture<?> armTimerFuture;

    public SessionManager() {
        this(DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_ARM_TIMEOUT_SECONDS);
    }

    public SessionManager(long idleTimeoutSeconds, long armTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.armTimeoutSeconds = armTimeoutSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcphub-session-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public void setTimeoutCallback(TimeoutCallback callback) {
        this.timeoutCallback = callback;
    }

    /** REQ-3.6.1: generate session ID on Closed -> Armed transition. */
    public String startSession() {
        currentSessionId = UUID.randomUUID().toString();
        sessionStart = Instant.now();
        lastActivity = sessionStart;
        log.info("Session started: {}", currentSessionId);
        startArmTimer();
        return currentSessionId;
    }

    /** Cancel arm timer, start idle timer when entering OPEN. */
    public void onOpen() {
        cancelArmTimer();
        resetIdleTimer();
    }

    /** REQ-3.7.3: reset idle timer on tool call or control message. */
    public void resetActivity() {
        lastActivity = Instant.now();
        resetIdleTimer();
    }

    /** Increment in-flight call count. */
    public int incrementInFlight() { return inFlightCount.incrementAndGet(); }

    /** Decrement in-flight call count. */
    public int decrementInFlight() { return inFlightCount.decrementAndGet(); }

    /** Get current in-flight count. */
    public int getInFlightCount() { return inFlightCount.get(); }

    /** End session: clear ID, timers. REQ-3.9.4 */
    public void endSession() {
        cancelIdleTimer();
        cancelArmTimer();
        String sid = currentSessionId;
        currentSessionId = null;
        sessionStart = null;
        lastActivity = null;
        inFlightCount.set(0);
        log.info("Session ended: {}", sid);
    }

    public String getCurrentSessionId() { return currentSessionId; }

    public Instant getLastActivity() { return lastActivity; }

    public long secondsSinceLastTransition(Instant lastTransition) {
        return Instant.now().getEpochSecond() - lastTransition.getEpochSecond();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void startArmTimer() {
        cancelArmTimer();
        armTimerFuture = scheduler.schedule(() -> {
            String sid = currentSessionId;
            log.warn("Arm timeout for session {}", sid);
            if (timeoutCallback != null && sid != null) {
                timeoutCallback.onArmTimeout(sid);
            }
        }, armTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void cancelArmTimer() {
        if (armTimerFuture != null) { armTimerFuture.cancel(false); armTimerFuture = null; }
    }

    private void resetIdleTimer() {
        cancelIdleTimer();
        idleTimerFuture = scheduler.schedule(() -> {
            String sid = currentSessionId;
            log.warn("Idle timeout for session {}", sid);
            if (timeoutCallback != null && sid != null) {
                timeoutCallback.onIdleTimeout(sid);
            }
        }, idleTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void cancelIdleTimer() {
        if (idleTimerFuture != null) { idleTimerFuture.cancel(false); idleTimerFuture = null; }
    }
}
