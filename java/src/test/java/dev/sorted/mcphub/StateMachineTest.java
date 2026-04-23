package dev.sorted.mcphub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StateMachine.
 * Covers all legal transitions and guard conditions.
 * Spec: Chapter 3 §3.4, §3.7, §3.8
 */
class StateMachineTest {

    private StateMachine sm;

    @BeforeEach
    void setUp() {
        sm = new StateMachine();
    }

    @Test
    void initialStateIsClosed() {
        // REQ-3.3.2
        assertEquals(StateMachine.State.CLOSED, sm.getState());
    }

    @Test
    void closedToArmed() throws Exception {
        // REQ-3.4.1
        sm.transition(StateMachine.Trigger.ARM, "s1");
        assertEquals(StateMachine.State.ARMED, sm.getState());
    }

    @Test
    void armedToOpen() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        assertEquals(StateMachine.State.OPEN, sm.getState());
    }

    @Test
    void openToCoolingDown() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.CLOSE, "s1");
        assertEquals(StateMachine.State.COOLING_DOWN, sm.getState());
    }

    @Test
    void coolingDownToClosed() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.CLOSE, "s1");
        sm.transition(StateMachine.Trigger.DRAIN_COMPLETE, "s1");
        assertEquals(StateMachine.State.CLOSED, sm.getState());
    }

    @Test
    void armedToClosedOnArmTimeout() throws Exception {
        // REQ-3.7.8
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.ARM_TIMEOUT, "s1");
        assertEquals(StateMachine.State.CLOSED, sm.getState());
    }

    @Test
    void emergencyLockFromOpen() throws Exception {
        // REQ-3.8.2: lock from OPEN bypasses CoolingDown
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.LOCK, "s1");
        assertEquals(StateMachine.State.CLOSED, sm.getState());
        assertTrue(sm.isLockedUntilUnlock());
    }

    @Test
    void emergencyLockFromArmed() throws Exception {
        // REQ-3.8.4
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.LOCK, "s1");
        assertEquals(StateMachine.State.CLOSED, sm.getState());
        assertTrue(sm.isLockedUntilUnlock());
    }

    @Test
    void emergencyLockFromClosed() throws Exception {
        sm.transition(StateMachine.Trigger.LOCK, null);
        assertEquals(StateMachine.State.CLOSED, sm.getState());
        assertTrue(sm.isLockedUntilUnlock());
    }

    @Test
    void lockedUntilUnlockBlocksArm() throws Exception {
        // REQ-3.8.6
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.LOCK, "s1");
        // Now locked
        StateMachine.TransitionException ex = assertThrows(
            StateMachine.TransitionException.class,
            () -> sm.transition(StateMachine.Trigger.ARM, "s2")
        );
        assertEquals("HUB_LOCKED", ex.getErrorCode());
    }

    @Test
    void unlockClearsLock() throws Exception {
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.LOCK, "s1");
        sm.setLock(false); // unlock
        assertFalse(sm.isLockedUntilUnlock());
        // should now be able to arm
        sm.transition(StateMachine.Trigger.ARM, "s2");
        assertEquals(StateMachine.State.ARMED, sm.getState());
    }

    @Test
    void illegalTransitionThrows() {
        // REQ-3.4.1: illegal transition must throw
        assertThrows(StateMachine.TransitionException.class,
            () -> sm.transition(StateMachine.Trigger.CLOSE, null));
    }

    @Test
    void toolCallPermittedOnlyWhenOpen() throws Exception {
        // REQ-3.4.2
        assertFalse(sm.isToolCallPermitted());
        sm.transition(StateMachine.Trigger.ARM, "s1");
        assertFalse(sm.isToolCallPermitted());
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        assertTrue(sm.isToolCallPermitted());
        sm.transition(StateMachine.Trigger.CLOSE, "s1");
        assertFalse(sm.isToolCallPermitted());
    }

    @Test
    void idleTimeoutTriggersTransition() throws Exception {
        // REQ-3.7.5: OPEN + IDLE_TIMEOUT -> COOLING_DOWN
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.IDLE_TIMEOUT, "s1");
        assertEquals(StateMachine.State.COOLING_DOWN, sm.getState());
    }

    @Test
    void bridgeDetachTriggersTransition() throws Exception {
        // REQ-3.7.2: OPEN + BRIDGE_DETACH -> COOLING_DOWN
        sm.transition(StateMachine.Trigger.ARM, "s1");
        sm.transition(StateMachine.Trigger.OPEN, "s1");
        sm.transition(StateMachine.Trigger.BRIDGE_DETACH, "s1");
        assertEquals(StateMachine.State.COOLING_DOWN, sm.getState());
    }
}
