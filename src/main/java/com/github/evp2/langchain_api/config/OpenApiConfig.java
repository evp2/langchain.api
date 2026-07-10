package com.github.evp2.langchain_api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger UI metadata. UI is served at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI radarApiDefinition() {
        return new OpenAPI().info(new Info()
                .title("Bedrock API")
                .version("0.0.1")
                .description("""
                        A web-based multi-agent code review. Submit a GitHub pull request URL and
                        the API runs a resiliency-focused, multi-agent review — change-risk, configuration, and
                        observability specialists plus a critic — using models on AWS Bedrock,
                        then returns a GO / CONDITIONAL / NO_GO deployment verdict with prioritized findings.""")
                .license(new License().name("Apache-2.0")));
    }
}
