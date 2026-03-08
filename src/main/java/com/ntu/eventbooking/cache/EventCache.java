package com.ntu.eventbooking.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple in-memory cache with Time-To-Live (TTL) expiry.
 *
 * Design decision: Uses static HashMaps rather than a library like Caffeine or Guava,
 * keeping dependencies minimal and making the caching logic fully visible for the report.
 *
 * Used by EventResource to cache the GET /events response so repeated reads within
 * the TTL window skip the Firestore network call entirely.
 *
 * Cache is invalidated (key removed) whenever an event is created or a student registers,
 * ensuring stale data is never served after a write operation.
 *
 * Thread safety note: For a production system ConcurrentHashMap would be used.
 * For this coursework the single-threaded request pattern is acceptable.
 */
public class EventCache {

    // TTL of 60 seconds — balances freshness vs performance (tunable for JMeter tests)
    private static final long TTL_MS = 60_000;

    // Stores serialised JSON strings keyed by a cache key (e.g. "all_events")
    private static final Map<String, String> cache = new HashMap<>();

    // Records the epoch millisecond when each entry was last written
    private static final Map<String, Long> timestamps = new HashMap<>();

    /** Store a JSON string in the cache under the given key. */
    public static void put(String key, String json) {
        cache.put(key, json);
        timestamps.put(key, System.currentTimeMillis());
    }

    /**
     * Retrieve a cached value.
     * Returns null if the key is not present OR if the entry has expired (older than TTL).
     */
    public static String get(String key) {
        if (!cache.containsKey(key)) return null;
        long age = System.currentTimeMillis() - timestamps.get(key);
        if (age > TTL_MS) {
            // Entry has expired — remove it and signal a cache miss
            cache.remove(key);
            timestamps.remove(key);
            return null;
        }
        return cache.get(key);
    }

    /** Remove a specific key from the cache (called after write operations). */
    public static void invalidate(String key) {
        cache.remove(key);
        timestamps.remove(key);
    }

    /** Remove all entries (useful for testing or forced refresh). */
    public static void invalidateAll() {
        cache.clear();
        timestamps.clear();
    }

    /** Returns the age of a cache entry in milliseconds (-1 if not present). */
    public static long getAgeMs(String key) {
        if (!timestamps.containsKey(key)) return -1;
        return System.currentTimeMillis() - timestamps.get(key);
    }
}
