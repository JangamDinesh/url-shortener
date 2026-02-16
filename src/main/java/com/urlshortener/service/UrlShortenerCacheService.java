package com.urlshortener.service;

import com.urlshortener.model.UrlMapping;
import com.urlshortener.repo.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Caching layer for URL mapping lookups.
 * Caches immutable fields (originalUrl, shortCode, createdAt) to avoid MongoDB hits on every redirect.
 * Mutable state (clickCount, expiryDate) should always be read from manual Redis keys,
 * not from the cached UrlMapping object.
 */
@Service
@RequiredArgsConstructor
public class UrlShortenerCacheService {

    private final UrlMappingRepository urlMappingRepository;

    @Cacheable(
            value = "urlMappingsByShortCode",
            key = "#shortCode",
            unless = "#result == null"
    )
    public UrlMapping getByShortCode(String shortCode) {
        return urlMappingRepository.findByShortCode(shortCode).orElse(null);
    }

    @Cacheable(
            value = "urlMappingsByOriginalUrl",
            key = "#originalUrl",
            unless = "#result == null"
    )
    public UrlMapping getShortCodeByOriginalURL(String originalUrl) {
        return urlMappingRepository.findByOriginalUrl(originalUrl).orElse(null);
    }
}
