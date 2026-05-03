package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for SessionManager.
 * Spec: Chapter 3 §3.6, §3.7
 */
class SessionManagerTest {

    @Test
    void startSessionGeneratesUniqueId() {
        // REQ-3.6.1
        SessionManager sm = new SessionManager();
        String id1 = sm.startSession();
        sm.endSession();
        String id2 = sm.startSession();
        sm.endSession();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        sm.shutdown();
    }

    @Test
    void endSessionClearsId() {
        SessionManager sm = new SessionManager();
        sm.startSession();
        sm.endSession();
        assertNull(sm.getCurrentSessionId());
        sm.shutdown();
    }

    @Test
    void inFlightCountTracking() {
        SessionManager sm = new SessionManager();
        sm.startSession();
        assertEquals(1, sm.incrementInFlight());
        assertEquals(2, sm.incrementInFlight());
        assertEquals(1, sm.decrementInFlight());
        sm.endSession();
        sm.shutdown();
    }

    @Test
    void armTimeoutFires() throws InterruptedException {
        // Use very short arm timeout for test
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean(false);

        SessionManager sm = new SessionManager(300, 1); // 1s arm timeout
        sm.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            public void onIdleTimeout(String sid) {}
            public void onArmTimeout(String sid) {
                fired.set(true);
                latch.countDown();
            }
        });
        sm.startSession();
        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertTrue(triggered, "Arm timeout should fire within 3 seconds");
        assertTrue(fired.get());
        sm.shutdown();
    }

    @Test
    void idleTimerFires() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean(false);

        SessionManager sm = new SessionManager(1, 300); // 1s idle timeout
        sm.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            public void onIdleTimeout(String sid) {
                fired.set(true);
                latch.countDown();
            }
            public void onArmTimeout(String sid) {}
        });
        sm.startSession();
        sm.onOpen();
        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertTrue(triggered, "Idle timeout should fire within 3 seconds");
        assertTrue(fired.get());
        sm.shutdown();
    }

    @Test
    void idleTimerSuspendDoesNotFire() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean(false);

        SessionManager sm = new SessionManager(1, 300); // 1s idle timeout
        sm.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            public void onIdleTimeout(String sid) {
                fired.set(true);
                latch.countDown();
            }
            public void onArmTimeout(String sid) {}
        });
        sm.startSession();
        sm.onOpen();
        sm.suspendIdleTimer();
        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertFalse(triggered, "Idle timeout should NOT fire while suspended");
        assertFalse(fired.get());
        sm.shutdown();
    }

    @Test
    void idleTimerResumeReFires() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean(false);

        SessionManager sm = new SessionManager(1, 300); // 1s idle timeout
        sm.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            public void onIdleTimeout(String sid) {
                fired.set(true);
                latch.countDown();
            }
            public void onArmTimeout(String sid) {}
        });
        sm.startSession();
        sm.onOpen();
        sm.suspendIdleTimer();
        Thread.sleep(500);
        sm.resumeIdleTimer();
        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertTrue(triggered, "Idle timeout should fire after resume");
        assertTrue(fired.get());
        sm.shutdown();
    }

    @Test
    void flagClearedAcrossSessions() throws InterruptedException {
        SessionManager sm = new SessionManager(1, 300);
        sm.startSession();
        sm.onOpen();
        sm.suspendIdleTimer();
        sm.endSession();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean(false);
        sm.setTimeoutCallback(new SessionManager.TimeoutCallback() {
            public void onIdleTimeout(String sid) {
                fired.set(true);
                latch.countDown();
            }
            public void onArmTimeout(String sid) {}
        });

        sm.startSession();
        sm.onOpen();
        boolean triggered = latch.await(3, TimeUnit.SECONDS);
        assertTrue(triggered, "Idle timeout should fire after new session start");
        assertTrue(fired.get());
        sm.shutdown();
    }
}
