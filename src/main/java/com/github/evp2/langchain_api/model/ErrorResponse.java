package com.github.evp2.langchain_api.model;

import java.time.Instant;

/** Standard error payload returned for failed requests. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message) {
}
