package com.urlshortener.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limit.maxRequests}")
    private int maxRequests;

    @Value("${rate.limit.windowSeconds}")
    private int timeWindowInSeconds;

    public boolean isAllowed(String ipAddress) {
        String redisKey = "rate_limit:" + ipAddress;

        try {
            // Increment the counter
            Long currentCount = redisTemplate.opsForValue().increment(redisKey);

            if (currentCount == null) {
                // Fallback in case Redis failed
                return true;
            }

            if (currentCount == 1) {
                // Set expiration on first access
                redisTemplate.expire(redisKey, Duration.ofSeconds(timeWindowInSeconds));
            }

            return currentCount <= maxRequests;

        } catch (Exception e) {
            // In case Redis is down or error occurs, allow the request (fail-open)
            System.err.println("Redis rate limit failed: " + e.getMessage());
            return true;
        }
    }
}
