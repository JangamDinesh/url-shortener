package com.urlshortener.service;

import com.urlshortener.exception.NotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.repo.UrlMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UrlShortenerCacheService {

    @Autowired
    UrlMappingRepository urlMappingRepository;

    @Cacheable(value = "urlMappingsByShortCode", key = "#shortCode")
    public UrlMapping getByShortCode(String shortCode) {
        return urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Short code not found"));
    }

    @Cacheable(value = "urlMappingsByOriginalUrl", key = "#originalUrl")
    public UrlMapping getShortCodeByOriginalURL(String originalUrl) {
        Optional<UrlMapping> existing = urlMappingRepository.findByOriginalUrl(originalUrl);
        return existing.orElse(null);
    }
}
