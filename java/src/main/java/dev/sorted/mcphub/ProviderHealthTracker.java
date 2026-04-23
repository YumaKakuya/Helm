package dev.sorted.mcphub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks current health of provider groups. Updated by Go daemon via
 * mcphub.internal.provider_health_update. Read by ControlHandler.handleCapabilities.
 * Spec: REQ-4.7.2
 */
public class ProviderHealthTracker {
    private static final String RUNNING = "running";
    private static final String STOPPED = "stopped";
    private static final String UNAVAILABLE = "unavailable";

    /** Key: group_id (e.g., "web"). Value: "running" | "stopped" | "unavailable". */
    private final Map<String, String> groupHealth = new ConcurrentHashMap<>();

    public void updateGroup(String groupId, String status) {
        if (groupId == null || groupId.isBlank()) return;
        groupHealth.put(groupId, normalize(status));
    }

    public String getGroupHealth(String groupId) {
        if (groupId == null || groupId.isBlank()) return UNAVAILABLE;
        return groupHealth.getOrDefault(groupId, UNAVAILABLE);
    }

    /** Map a tool's display_name to its current health via group mapping. */
    public String healthForTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return UNAVAILABLE;
        String group = CapabilityRegistry.groupForTool(toolName);
        if (group == null) return UNAVAILABLE;
        return getGroupHealth(group);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(groupHealth);
    }

    public void clear() {
        groupHealth.clear();
    }

    private String normalize(String status) {
        if (RUNNING.equals(status) || STOPPED.equals(status) || UNAVAILABLE.equals(status)) {
            return status;
        }
        return UNAVAILABLE;
    }
}
