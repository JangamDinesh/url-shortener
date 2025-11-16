package com.urlshortener.controller;

import com.urlshortener.exception.ExpiredException;
import com.urlshortener.exception.NotFoundException;
import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.model.UrlStatsResponse;
import com.urlshortener.service.RateLimiterService;
import com.urlshortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api")
public class UrlShortenerController {

    @Autowired
    private UrlShortenerService urlShortenerService;

    @Autowired
    private RateLimiterService rateLimiterService;

    @PostMapping("/shorten")
    public ResponseEntity<String> shortenUrl(@Valid @RequestBody ShortenUrlRequest request,
                                             HttpServletRequest servletRequest) {

        String clientIp = servletRequest.getRemoteAddr();

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

    // todo
    //  sliding expiry keys handling
    //  sync service optimise
    //  redis atomic counter
    //  github readme file

    // JOYFUL-SIMPLICITY


}
