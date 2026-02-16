package com.urlshortener.service;

import com.urlshortener.repo.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSyncService {

    private final RedisTemplate<String, String> redisTemplate;
    private final UrlMappingRepository urlMappingRepository;

    private static final String DIRTY_SET_KEY = "dirty_urls";

    @Value("${scheduler.sync.interval}")
    private long syncInterval;

    @PostConstruct
    public void logInterval() {
        log.info("Redis Sync Interval Configured: {}ms", syncInterval);
    }

    /**
     * Syncs only recently-modified URLs from Redis to MongoDB.
     * The redirect Lua script adds shortCodes to the "dirty_urls" Redis Set on each click.
     * This job atomically swaps the dirty set (RENAME), then syncs only those entries — O(K active) not O(N total).
     */
    @Scheduled(fixedDelayString = "${scheduler.sync.interval}")
    public void syncClicksToDatabase() {
        log.info("Starting Redis-to-MongoDB sync...");

        // Atomically swap dirty set to a processing set to avoid losing entries added during sync
        String processingKey = DIRTY_SET_KEY + ":processing";
        try {
            redisTemplate.rename(DIRTY_SET_KEY, processingKey);
        } catch (Exception e) {
            // RENAME fails if the key doesn't exist (no dirty URLs since last sync)
            log.info("No dirty URLs to sync.");
            return;
        }

        Set<String> dirtyShortCodes;
        try {
            dirtyShortCodes = redisTemplate.opsForSet().members(processingKey);
        } catch (Exception e) {
            log.error("Failed to read processing set from Redis", e);
            return;
        }

        if (dirtyShortCodes == null || dirtyShortCodes.isEmpty()) {
            redisTemplate.delete(processingKey);
            log.info("No dirty URLs to sync.");
            return;
        }

        log.info("Syncing {} dirty URL(s) to MongoDB", dirtyShortCodes.size());

        for (String shortCode : dirtyShortCodes) {
            String clickKey = "url:" + shortCode + ":clicks";

            try {
                String clickStr = redisTemplate.opsForValue().get(clickKey);

                if (clickStr != null) {
                    urlMappingRepository.findByShortCode(shortCode).ifPresent(mapping -> {
                        mapping.setClickCount(Long.parseLong(clickStr));
                        urlMappingRepository.save(mapping);
                    });
                }
            } catch (Exception e) {
                log.warn("Sync failed for {}: {}", shortCode, e.getMessage());
            }
        }

        // Clean up the processing set
        redisTemplate.delete(processingKey);

        // Clean up expired URLs from MongoDB (fixed expiry — set once at creation, never extended)
        try {
            long deleted = urlMappingRepository.deleteByExpiryDateBefore(LocalDateTime.now());
            if (deleted > 0) {
                log.info("Cleaned up {} expired URL(s) from MongoDB", deleted);
            }
        } catch (Exception e) {
            log.warn("Expired URL cleanup failed: {}", e.getMessage());
        }

        log.info("Sync complete.");
    }
}
