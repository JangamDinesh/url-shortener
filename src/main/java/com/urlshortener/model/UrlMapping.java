package com.urlshortener.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "url_mapping")
public class UrlMapping {

    @Id
    private String id;

    private String originalUrl;
    private String shortCode;
    private Long clickCount = 0L;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime expiryDate;
}
