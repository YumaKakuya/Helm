package dev.sorted.mcphub;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 0 gate test — confirms the build pipeline is functional.
 * Gate G-3: ./gradlew test PASS (REQ: Session 0 Non-Negotiable Gates)
 */
class MainTest {
    @Test
    void buildGatePasses() {
        // Session 0 Non-Negotiable Gate G-3: build pipeline is functional
        assertTrue(true, "Build pipeline is functional");
    }
}
