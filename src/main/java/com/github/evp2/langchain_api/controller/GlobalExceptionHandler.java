package com.github.evp2.langchain_api.controller;

import com.github.evp2.langchain_api.model.ErrorResponse;
import com.github.evp2.langchain_api.service.ModelBackendException;
import com.github.evp2.langchain_api.service.PullRequestNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps service/domain exceptions to clean JSON error responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PullRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PullRequestNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ModelBackendException.class)
    public ResponseEntity<ErrorResponse> handleBackend(ModelBackendException e) {
        log.warn("Model backend failure: {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e, HttpServletRequest req) {
        log.error("Unhandled error processing {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.BAD_GATEWAY,
                "Review failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message);
        return ResponseEntity.status(status).body(body);
    }
}
