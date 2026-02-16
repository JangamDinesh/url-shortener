package com.urlshortener.service;

import com.urlshortener.util.RedisLuaScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limit.maxRequests}")
    private int maxRequests;

    @Value("${rate.limit.windowSeconds}")
    private int timeWindowInSeconds;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptText(RedisLuaScripts.RATE_LIMIT_SCRIPT);
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    public boolean isAllowed(String ipAddress) {
        String redisKey = "rate_limit:" + ipAddress;

        try {
            // Atomic increment + expire in a single Lua script — no race condition
            Long currentCount = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(redisKey),
                    String.valueOf(timeWindowInSeconds)
            );

            if (currentCount == null) {
                return true;
            }

            return currentCount <= maxRequests;

        } catch (Exception e) {
            // In case Redis is down or error occurs, allow the request (fail-open)
            log.error("Redis rate limit failed for IP={}", ipAddress, e);
            return true;
        }
    }
}
