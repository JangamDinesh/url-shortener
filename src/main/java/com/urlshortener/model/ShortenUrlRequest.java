package com.urlshortener.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ShortenUrlRequest {

    @NotBlank(message = "originalUrl cannot be blank")
    @Pattern(
            regexp = "^(http|https)://.*$",
            message = "originalUrl must be a valid URL starting with http or https"
    )
    private String originalUrl;
}
