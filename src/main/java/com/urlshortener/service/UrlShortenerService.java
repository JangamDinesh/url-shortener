package com.urlshortener.service;

import com.urlshortener.exception.ExpiredException;
import com.urlshortener.exception.NotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.model.UrlStatsResponse;
import com.urlshortener.repo.UrlMappingRepository;
import com.urlshortener.util.Base62Encoder;
import com.urlshortener.util.RedisLuaScripts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenerService {

    private final UrlMappingRepository urlMappingRepository;
    private final CounterService counterService;
    private final RedisTemplate<String, String> redisTemplate;
    private final UrlShortenerCacheService urlShortenerCacheService;

    private static final String DIRTY_SET_KEY = "dirty_urls";

    private static final DefaultRedisScript<Long> REDIRECT_SCRIPT;

    static {
        REDIRECT_SCRIPT = new DefaultRedisScript<>();
        REDIRECT_SCRIPT.setScriptText(RedisLuaScripts.REDIRECT_SCRIPT);
        REDIRECT_SCRIPT.setResultType(Long.class);
    }

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

        // @Cacheable layer: caches immutable fields (originalUrl, shortCode, createdAt) to avoid MongoDB hits
        UrlMapping mapping = urlShortenerCacheService.getByShortCode(shortCode);

        if (mapping == null) {
            throw new NotFoundException("Short code not found");
        }

        String clickKey = "url:" + shortCode + ":clicks";
        String expiryKey = "url:" + shortCode + ":expiry";

        try {
            // Single Lua script: check expiry + increment clicks + mark dirty — one round-trip
            Long result = redisTemplate.execute(
                    REDIRECT_SCRIPT,
                    Arrays.asList(clickKey, expiryKey, DIRTY_SET_KEY),
                    String.valueOf(mapping.getClickCount()),       // fallback clicks from DB
                    mapping.getExpiryDate().toString(),            // fallback expiry from DB
                    LocalDateTime.now().toString(),                // current time
                    shortCode                                     // shortCode for dirty set
            );

            if (result != null && result == -1) {
                throw new ExpiredException("Short code expired");
            }

        } catch (ExpiredException | NotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis failed, falling back to DB for shortCode={}", shortCode, e);

            if (mapping.getExpiryDate().isBefore(LocalDateTime.now())) {
                throw new ExpiredException("Short code expired");
            }

            mapping.setClickCount(mapping.getClickCount() + 1);
            urlMappingRepository.save(mapping);  // fallback DB update
        }

        return mapping;
    }

    // Mutable state (clicks, expiry) is always read from Redis manual keys, not from the @Cacheable object.
    public UrlStatsResponse getStats(String shortCode) {
        UrlMapping mapping = urlShortenerCacheService.getByShortCode(shortCode);

        if (mapping == null) {
            throw new NotFoundException("Short code not found");
        }

        String clickKey = "url:" + shortCode + ":clicks";
        String expiryKey = "url:" + shortCode + ":expiry";

        Long clicks = 0L;
        String expiryStr = null;

        try {
            String clickStr = redisTemplate.opsForValue().get(clickKey);
            clicks = (clickStr != null) ? Long.parseLong(clickStr) : mapping.getClickCount();

            expiryStr = redisTemplate.opsForValue().get(expiryKey);
        } catch (Exception e) {
            log.warn("Redis failed reading stats for shortCode={}, falling back to DB", shortCode, e);
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
