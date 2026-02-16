package com.urlshortener.controller;

import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.model.UrlStatsResponse;
import com.urlshortener.service.RateLimiterService;
import com.urlshortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;
    private final RateLimiterService rateLimiterService;

    @PostMapping("/shorten")
    public ResponseEntity<String> shortenUrl(@Valid @RequestBody ShortenUrlRequest request,
                                             HttpServletRequest servletRequest) {

        String clientIp = extractClientIp(servletRequest);

        if (!rateLimiterService.isAllowed(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Too many requests. Please try again after a while.");
        }

        String shortCode = urlShortenerService.shortenUrl(request.getOriginalUrl());
        return ResponseEntity.ok(shortCode);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        UrlMapping mapping = urlShortenerService.getUrlMappingByShortCode(shortCode);
        return ResponseEntity.status(302)
                .location(URI.create(mapping.getOriginalUrl()))
                .build();
    }

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<?> getStats(@PathVariable String shortCode) {
        UrlStatsResponse stats = urlShortenerService.getStats(shortCode);
        if (stats != null) {
            return ResponseEntity.ok(stats);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
