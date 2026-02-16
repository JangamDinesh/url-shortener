package com.urlshortener.util;

/**
 * Lua scripts executed atomically in Redis to reduce round-trips and prevent race conditions.
 */
public final class RedisLuaScripts {

    private RedisLuaScripts() {}

    /**
     * Rate limiter: atomic increment + expire.
     *
     * KEYS[1] = rate_limit:{ip}
     * ARGV[1] = window in seconds
     *
     * Returns: current count after increment
     */
    public static final String RATE_LIMIT_SCRIPT =
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then " +
            "    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) " +
            "end " +
            "return count";

    /**
     * Redirect: check expiry + increment clicks + mark dirty — all in one round-trip.
     *
     * KEYS[1] = url:{shortCode}:clicks
     * KEYS[2] = url:{shortCode}:expiry
     * KEYS[3] = dirty_urls (set of recently-modified shortCodes for sync)
     *
     * ARGV[1] = fallback click count from DB (used if Redis key missing)
     * ARGV[2] = fallback expiry from DB (used if Redis key missing)
     * ARGV[3] = current time as ISO string (e.g. "2026-02-12T10:30:00")
     * ARGV[4] = shortCode (to add to dirty set)
     *
     * Returns:
     *   -1  if expired
     *   new click count  if valid
     */
    public static final String REDIRECT_SCRIPT =
            // Ensure click key exists; populate from DB fallback if missing
            "if redis.call('EXISTS', KEYS[1]) == 0 then " +
            "    redis.call('SET', KEYS[1], ARGV[1]) " +
            "end " +
            // Ensure expiry key exists; populate from DB fallback if missing
            "if redis.call('EXISTS', KEYS[2]) == 0 then " +
            "    redis.call('SET', KEYS[2], ARGV[2]) " +
            "end " +
            // Check expiry: compare stored expiry with current time
            "local expiry = redis.call('GET', KEYS[2]) " +
            "if expiry < ARGV[3] then " +
            "    return -1 " +
            "end " +
            // Increment click count
            "local clicks = redis.call('INCR', KEYS[1]) " +
            // Mark shortCode as dirty for sync job
            "redis.call('SADD', KEYS[3], ARGV[4]) " +
            "return clicks";
}
