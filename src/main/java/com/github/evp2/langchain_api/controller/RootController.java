package com.github.evp2.langchain_api.controller;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/** Convenience endpoints: health check and a redirect from root to Swagger UI. */
@RestController
public class RootController {

    @Operation(summary = "Liveness/health check")
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "langchain-api-radar-review");
    }

    @Operation(summary = "Redirect to Swagger UI")
    @GetMapping("/")
    public RedirectView root() {
        return new RedirectView("/swagger-ui.html");
    }
}
