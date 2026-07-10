package com.github.evp2.langchain_api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** A free-form prompt to send directly to the selected model. */
public record PromptRequest(
        @Schema(example = "Summarize the tradeoffs of blue-green vs canary deployments in two sentences.",
                description = "Prompt text passed verbatim to the model")
        @NotBlank String prompt) {
}
