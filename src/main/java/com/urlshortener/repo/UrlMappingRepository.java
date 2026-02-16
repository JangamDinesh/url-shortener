package com.urlshortener.repo;

import org.springframework.data.mongodb.repository.MongoRepository;
import com.urlshortener.model.UrlMapping;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UrlMappingRepository extends MongoRepository<UrlMapping, String> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    Optional<UrlMapping> findByOriginalUrl(String originalUrl);

    long deleteByExpiryDateBefore(LocalDateTime dateTime);
}
