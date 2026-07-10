package com.github.evp2.langchain_api.controller;

import com.github.evp2.langchain_api.model.ErrorResponse;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.service.CodeReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint that runs a resiliency code review for a GitHub pull request. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Code Review", description = "Resiliency-focused, multi-agent review of a GitHub pull request.")
public class CodeReviewController {

    private final CodeReviewService service;

    public CodeReviewController(CodeReviewService service) {
        this.service = service;
    }

    @Operation(
            summary = "Review a GitHub pull request",
            description = """
                    Runs the change-risk, configuration, and observability specialists (plus a synthesizer) over
                    the PR diff using Claude models on AWS Bedrock, and returns a GO / CONDITIONAL / NO_GO verdict
                    with prioritized findings. This call is synchronous and may take up to a couple of minutes.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review completed"),
            @ApiResponse(responseCode = "400", description = "Malformed PR URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PR not found or not accessible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/reviews")
    public ReviewResponse review(
            @Parameter(
                    description = "Full GitHub pull request URL",
                    example = "https://github.com/octocat/Hello-World/pull/1",
                    required = true)
            @RequestParam("prUrl") @NotBlank String prUrl) {
        return service.review(prUrl);
    }
}
