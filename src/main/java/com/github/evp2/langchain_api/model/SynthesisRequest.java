package com.github.evp2.langchain_api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Input for running the synthesizer agent on its own: PR metadata plus the three specialist
 * analyses to consolidate into a single deployment verdict. Any analysis may be omitted
 * (treated as empty) so the synthesizer can run with a subset of specialists.
 */
public record SynthesisRequest(
        @Schema(example = "octocat/Hello-World", description = "owner/repo")
        @NotBlank String repository,
        @Schema(example = "1") int number,
        @Schema(example = "Add retry logic to payment client") String title,
        @Schema(description = "Change-risk (CHR) specialist analysis") DimensionAnalysis changeRisk,
        @Schema(description = "Configuration (CFG) specialist analysis") DimensionAnalysis configuration,
        @Schema(description = "Observability (ORA) specialist analysis") DimensionAnalysis observability) {
}
