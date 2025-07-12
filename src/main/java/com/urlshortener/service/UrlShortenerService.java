package com.urlshortener.service;

import com.urlshortener.exception.ExpiredException;
import com.urlshortener.exception.NotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.model.UrlStatsResponse;
import com.urlshortener.repo.UrlMappingRepository;
import com.urlshortener.util.Base62Encoder;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UrlShortenerService {

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private CounterService counterService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    UrlShortenerCacheService urlShortenerCacheService;

    public String shortenUrl(String originalUrl) {
        UrlMapping existing = urlShortenerCacheService.getShortCodeByOriginalURL(originalUrl);
        if (existing != null) {
            return existing.getShortCode();
        }

        long id = counterService.getNextSequence("url_sequence");
        String shortCode = Base62Encoder.encode(id);

        LocalDateTime expiryDate = LocalDateTime.now().plusDays(30);

        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setOriginalUrl(originalUrl);
        urlMapping.setShortCode(shortCode);
        urlMapping.setClickCount(0L);
        urlMapping.setExpiryDate(expiryDate);
        urlMappingRepository.save(urlMapping);

        redisTemplate.opsForValue().set("url:" + shortCode + ":clicks", "0", Duration.ofDays(30));
        redisTemplate.opsForValue().set("url:" + shortCode + ":expiry", expiryDate.toString(), Duration.ofDays(30));

        return shortCode;
    }


    public UrlMapping getUrlMappingByShortCode(String shortCode) throws ExpiredException, NotFoundException {
        UrlMapping mapping = urlShortenerCacheService.getByShortCode(shortCode);  // fetch from cache

        String clickKey = "url:" + shortCode + ":clicks";
        String expiryKey = "url:" + shortCode + ":expiry";

        try {
            // If Redis doesn't have keys, populate from DB value
            if (!redisTemplate.hasKey(clickKey)) {
                redisTemplate.opsForValue().set(clickKey, String.valueOf(mapping.getClickCount()));
            }

            if (!redisTemplate.hasKey(expiryKey)) {
                redisTemplate.opsForValue().set(expiryKey, mapping.getExpiryDate().toString());
            }

            // Validate expiry from Redis
            String expiryStr = redisTemplate.opsForValue().get(expiryKey);
            if (expiryStr == null || LocalDateTime.parse(expiryStr).isBefore(LocalDateTime.now())) {
                throw new ExpiredException("Short code expired");
            }

            // Increment click count
            redisTemplate.opsForValue().increment(clickKey);

            // Sliding expiry
            redisTemplate.opsForValue().set(expiryKey, LocalDateTime.now().plusDays(30).toString());

        } catch (Exception e) {
            System.out.println("Redis failed, fallback to DB");

            if (mapping.getExpiryDate().isBefore(LocalDateTime.now())) {
                throw new ExpiredException("Short code expired");
            }

            mapping.setClickCount(mapping.getClickCount() + 1);
            mapping.setExpiryDate(LocalDateTime.now().plusDays(30));
            urlMappingRepository.save(mapping);  // fallback DB update
        }

        return mapping;
    }

    public UrlStatsResponse getStats(String shortCode) {
        UrlMapping mapping = urlShortenerCacheService.getByShortCode(shortCode);  // from cache

        String clickKey = "url:" + shortCode + ":clicks";
        String expiryKey = "url:" + shortCode + ":expiry";

        Long clicks = 0L;
        String expiryStr = null;

        try {
            String clickStr = redisTemplate.opsForValue().get(clickKey);
            clicks = (clickStr != null) ? Long.parseLong(clickStr) : mapping.getClickCount();

            expiryStr = redisTemplate.opsForValue().get(expiryKey);
        } catch (Exception e) {
            // Fallback to DB values
            clicks = mapping.getClickCount();
            expiryStr = mapping.getExpiryDate().toString();
        }

        UrlStatsResponse stats = new UrlStatsResponse();
        stats.setOriginalUrl(mapping.getOriginalUrl());
        stats.setShortCode(mapping.getShortCode());
        stats.setClickCount(clicks);
        stats.setCreatedAt(mapping.getCreatedAt().toString());
        stats.setExpiryDate(expiryStr);

        return stats;
    }


}
