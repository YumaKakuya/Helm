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
}
