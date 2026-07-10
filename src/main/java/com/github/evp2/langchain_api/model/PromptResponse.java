package com.github.evp2.langchain_api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** The model's raw text response to a direct prompt. */
public record PromptResponse(
        @Schema(description = "Model backend that produced the response") ModelChoice model,
        @Schema(description = "Model's text completion") String completion) {
}
