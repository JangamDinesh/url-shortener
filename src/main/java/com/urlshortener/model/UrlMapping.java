package com.urlshortener.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Document(collection = "url_mapping")
public class UrlMapping implements Serializable {

    @Id
    private String id;

    @Indexed
    private String originalUrl;

    @Indexed(unique = true)
    private String shortCode;

    private Long clickCount = 0L;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime expiryDate;
}
