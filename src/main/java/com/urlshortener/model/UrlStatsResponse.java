package com.urlshortener.model;

import lombok.Data;

@Data
public class UrlStatsResponse {
    private String originalUrl;
    private String shortCode;
    private long clickCount;
    private String createdAt;
    private String expiryDate;
}
