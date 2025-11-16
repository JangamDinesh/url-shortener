package com.urlshortener.service;

import com.urlshortener.model.UrlMapping;
import com.urlshortener.repo.UrlMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RedisSyncService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Value("${scheduler.sync.interval}")
    private long syncInterval;

    @PostConstruct
    public void logInterval() {
        System.out.println("Redis Sync Interval Configured: " + syncInterval + "ms");
    }

    @Scheduled(fixedDelayString = "${scheduler.sync.interval}")
    public void syncClicksAndExpiryToDatabase() {
        System.out.println("Syncing Redis data to DB...");

        List<UrlMapping> allMappings = urlMappingRepository.findAll();

        for (UrlMapping mapping : allMappings) {
            String shortCode = mapping.getShortCode();
            String clickKey = "url:" + shortCode + ":clicks";
            String expiryKey = "url:" + shortCode + ":expiry";

            try {
                String clickStr = redisTemplate.opsForValue().get(clickKey);
                String expiryStr = redisTemplate.opsForValue().get(expiryKey);

                if (clickStr != null) {
                    mapping.setClickCount(Long.parseLong(clickStr));
                }

                if (expiryStr != null) {
                    mapping.setExpiryDate(LocalDateTime.parse(expiryStr));
                }

                urlMappingRepository.save(mapping);
            } catch (Exception e) {
                System.out.println("Sync failed for " + shortCode + ": " + e.getMessage());
            }
        }

        System.out.println("Sync complete.");
    }
}
