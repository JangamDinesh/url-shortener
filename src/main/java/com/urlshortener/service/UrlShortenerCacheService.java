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
