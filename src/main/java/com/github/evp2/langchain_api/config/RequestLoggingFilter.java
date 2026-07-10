package com.github.evp2.langchain_api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits one structured log event per HTTP request with method, path, status, duration, and a
 * correlation id. The correlation id is placed in the MDC so every log line produced while the
 * request is handled (service, exception handler, etc.) carries the same {@code requestId},
 * making a single API call traceable across threads' log output.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("api.access");
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.atInfo()
                    .addKeyValue("event", "http.request")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("query", request.getQueryString())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .addKeyValue("clientIp", request.getRemoteAddr())
                    .log("{} {} -> {} ({}ms)",
                            request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static String requestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
    }
}
