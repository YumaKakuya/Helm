package dev.sorted.mcphub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MCPHUB execution state machine.
 * Spec: Chapter 3 (CONSTRAINT-C / FB-08)
 * States: CLOSED, ARMED, OPEN, COOLING_DOWN
 */
public class StateMachine {
    private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

    public enum State {
        CLOSED, ARMED, OPEN, COOLING_DOWN
    }

    public static class TransitionException extends Exception {
        private final String errorCode;
        public TransitionException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }
        public String getErrorCode() { return errorCode; }
    }

    public enum Trigger {
        ARM, OPEN, CLOSE, LOCK, UNLOCK, ARM_TIMEOUT, IDLE_TIMEOUT, BRIDGE_DETACH, DRAIN_COMPLETE
    }

    // Listener for state change events (for logging to DB)
    public interface StateChangeListener {
        void onStateChange(State from, State to, Trigger trigger, String sessionId);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile State state = State.CLOSED;
    private volatile Instant lastTransition = Instant.now();
    private volatile boolean lockedUntilUnlock = false;
    private StateChangeListener listener;
    private DatabaseManager db; // optional, for REQ-3.8.7 lock persistence

    public StateMachine() {}

    public void setStateChangeListener(StateChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Wire DatabaseManager so the lock guard persists across daemon restarts.
     * REQ-3.8.7. Must be called ONCE during startup, and ONCE the DB is open.
     * On wire, this method ALSO restores lockedUntilUnlock from the DB, so the
     * daemon resumes in the correct guard state after restart.
     */
    public void setDatabaseManager(DatabaseManager db) {
        this.db = db;
        if (db != null && db.isSessionLockGuardSet()) {
            this.lockedUntilUnlock = true;
            log.info("locked_until_unlock restored from DB: true");
        }
    }

    /** REQ-3.3.2: daemon starts in CLOSED. */
    public State getState() { return state; }

    public Instant getLastTransition() { return lastTransition; }

    public boolean isLockedUntilUnlock() { return lockedUntilUnlock; }

    /**
     * Perform a state transition.
     * REQ-3.4.1: enforces legal transitions; rejects all others.
     * @param trigger the event triggering the transition
     * @param sessionId current session ID (may be null)
     * @return new state
     */
    public State transition(Trigger trigger, String sessionId) throws TransitionException {
        lock.lock();
        try {
            State from = state;
            State to = computeTransition(from, trigger);
            state = to;
            lastTransition = Instant.now();
            log.info("State transition: {} -> {} (trigger={}, session={})", from, to, trigger, sessionId);
            if (listener != null) {
                listener.onStateChange(from, to, trigger, sessionId);
            }
            return to;
        } finally {
            lock.unlock();
        }
    }

    private State computeTransition(State from, Trigger trigger) throws TransitionException {
        switch (from) {
            case CLOSED:
                if (trigger == Trigger.ARM) {
                    // REQ-3.8.6: reject if locked_until_unlock
                    if (lockedUntilUnlock) {
                        throw new TransitionException(
                            "Cannot arm: hub is locked. Run 'mcphub unlock' to clear.",
                            "HUB_LOCKED");
                    }
                    return State.ARMED;
                }
                if (trigger == Trigger.LOCK) {
                    setLock(true);
                    return State.CLOSED;
                }
                break;
            case ARMED:
                if (trigger == Trigger.OPEN)  return State.OPEN;
                if (trigger == Trigger.CLOSE) return State.CLOSED;
                if (trigger == Trigger.ARM_TIMEOUT) return State.CLOSED;
                if (trigger == Trigger.LOCK) {
                    setLock(true);
                    return State.CLOSED;
                }
                break;
            case OPEN:
                if (trigger == Trigger.CLOSE)         return State.COOLING_DOWN;
                if (trigger == Trigger.IDLE_TIMEOUT)  return State.COOLING_DOWN;
                if (trigger == Trigger.BRIDGE_DETACH) return State.COOLING_DOWN;
                if (trigger == Trigger.LOCK) {
                    // REQ-3.8.2: emergency lock from OPEN bypasses CoolingDown
                    setLock(true);
                    return State.CLOSED;
                }
                break;
            case COOLING_DOWN:
                if (trigger == Trigger.DRAIN_COMPLETE) return State.CLOSED;
                if (trigger == Trigger.LOCK) {
                    // REQ-3.8.3: emergency lock from COOLING_DOWN aborts drain
                    setLock(true);
                    return State.CLOSED;
                }
                break;
        }
        throw new TransitionException(
            String.format("Illegal transition: %s -> trigger %s (state=%s)", from, trigger, from),
            "INVALID_TRANSITION");
    }

    /** REQ-3.8.6 + REQ-3.8.7: set or clear the persistent lock guard; persists to DB. */
    public void setLock(boolean locked) {
        this.lockedUntilUnlock = locked;
        if (db != null) {
            db.setSessionLockGuard(locked);
        }
        log.info("locked_until_unlock = {} (persisted={})", locked, db != null);
    }

    /** REQ-3.4.2: check if tools/call is permitted. */
    public boolean isToolCallPermitted() {
        return state == State.OPEN;
    }

    /** REQ-3.4.3: check if tools/list should return non-empty. */
    public boolean isToolListOpen() {
        return state == State.OPEN;
    }
}
