package com.urlshortener.service;

import com.urlshortener.exception.ExpiredException;
import com.urlshortener.exception.NotFoundException;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.model.UrlStatsResponse;
import com.urlshortener.repo.UrlMappingRepository;
import com.urlshortener.util.Base62Encoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UrlShortenerService {

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private CounterService counterService;

    public String shortenUrl(String originalUrl) {
        // check if already exists
        Optional<UrlMapping> existing = urlMappingRepository.findByOriginalUrl(originalUrl);
        if (existing.isPresent()) {
            return existing.get().getShortCode();
        }

        // get next sequence
        long id = counterService.getNextSequence("url_sequence");

        // base62 encode
        String shortCode = Base62Encoder.encode(id);

        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setOriginalUrl(originalUrl);
        urlMapping.setShortCode(shortCode);
        urlMapping.setClickCount(0L);

        urlMapping.setExpiryDate(LocalDateTime.now().plusDays(30)); // 30 days expiry
        urlMappingRepository.save(urlMapping);

        return shortCode;
    }

    public UrlMapping getUrlMappingByShortCode(String shortCode) throws ExpiredException, NotFoundException {
        Optional<UrlMapping> optional = urlMappingRepository.findByShortCode(shortCode);
        if (optional.isEmpty()) {
            throw new NotFoundException("Short code not found");
        }

        UrlMapping mapping = optional.get();

        if (mapping.getExpiryDate() != null && mapping.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new ExpiredException("Short code expired");
        }

        // update click count + sliding expiry
        mapping.setClickCount(mapping.getClickCount() + 1);
        mapping.setExpiryDate(LocalDateTime.now().plusDays(30));
        urlMappingRepository.save(mapping);

        return mapping;
    }

    public UrlStatsResponse getStats(String shortCode) {
        Optional<UrlMapping> optional = urlMappingRepository.findByShortCode(shortCode);
        if (optional.isPresent()) {
            UrlMapping mapping = optional.get();
            UrlStatsResponse stats = new UrlStatsResponse();
            stats.setOriginalUrl(mapping.getOriginalUrl());
            stats.setShortCode(mapping.getShortCode());
            stats.setClickCount(mapping.getClickCount());
            stats.setCreatedAt(mapping.getCreatedAt().toString());
            stats.setExpiryDate(mapping.getExpiryDate().toString());
            return stats;
        }
        return null;
    }

}
