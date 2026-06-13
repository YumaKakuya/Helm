package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped read-only result cache.
 * Proposal §6.5 / OS-12 → Alpha scope (CEO 2026-04-26).
 *
 * Rules:
 *   - Only tools with rw_boundary="read" are cache-eligible (REQ: read-only labels stable)
 *   - Cache key = SHA-256(tool_name + canonical_arguments_json)
 *   - Cache is session-scoped: cleared on session close/lock
 *   - TTL per entry: configurable, default 60 seconds
 *   - Cache does NOT apply to: write/execute tools, disambiguation, control surface
 */
public class ResultCache {
    private static final Logger log = LoggerFactory.getLogger(ResultCache.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_TTL_MS = 60_000;

    private record CacheEntry(JsonNode result, Instant createdAt, long ttlMs) {
        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusMillis(ttlMs));
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private long ttlMs = DEFAULT_TTL_MS;
    private int hits = 0;
    private int misses = 0;

    /** Set TTL in milliseconds. */
    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Check if a cached result exists for the given tool + arguments.
     * Returns null on miss or expiry.
     */
    public JsonNode get(String toolName, JsonNode arguments) {
        String key = cacheKey(toolName, arguments);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key);
            misses++;
            log.debug("Cache expired for tool={} key={}", toolName, key.substring(0, 8));
            return null;
        }
        hits++;
        log.debug("Cache hit for tool={} key={}", toolName, key.substring(0, 8));
        return entry.result;
    }

    /**
     * Store a result in the cache.
     */
    public void put(String toolName, JsonNode arguments, JsonNode result) {
        String key = cacheKey(toolName, arguments);
        cache.put(key, new CacheEntry(result, Instant.now(), ttlMs));
        log.debug("Cache put for tool={} key={} size={}", toolName, key.substring(0, 8), cache.size());
    }

    /**
     * Check if a tool is cache-eligible based on its capability entry.
     * Only read-only tools are eligible.
     */
    public static boolean isCacheEligible(CapabilityEntry entry) {
        if (entry == null) return false;
        return "read".equals(entry.rwBoundary);
    }

    /** Clear all cached entries. Called on session close. */
    public void clear() {
        int size = cache.size();
        cache.clear();
        log.info("ResultCache cleared ({} entries, {} hits, {} misses)", size, hits, misses);
        hits = 0;
        misses = 0;
    }

    /** Current cache size. */
    public int size() {
        return cache.size();
    }

    /** Cache hit count since last clear. */
    public int getHits() { return hits; }

    /** Cache miss count since last clear. */
    public int getMisses() { return misses; }

    /**
     * Generate a deterministic cache key from tool name + arguments.
     * Uses SHA-256 of the canonical JSON string.
     */
    static String cacheKey(String toolName, JsonNode arguments) {
        try {
            String canonical = toolName + "|" +
                    (arguments != null ? mapper.writeValueAsString(arguments) : "{}");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            // Fallback: use raw string hash
            return toolName + "|" + (arguments != null ? arguments.hashCode() : 0);
        }
    }
}
