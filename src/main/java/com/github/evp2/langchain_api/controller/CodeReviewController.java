package com.github.evp2.langchain_api.controller;

import com.github.evp2.langchain_api.model.ErrorResponse;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.service.ReviewJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asynchronous resiliency code review for a GitHub pull request. A full review runs three
 * specialists plus a synthesizer and can take minutes, so the work runs off the request thread:
 * submit returns a job immediately and the caller polls for the result.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Code Review", description = "Resiliency-focused, multi-agent review of a GitHub pull request.")
public class CodeReviewController {

    private final ReviewJobService jobs;

    public CodeReviewController(ReviewJobService jobs) {
        this.jobs = jobs;
    }

    @Operation(
            summary = "Submit a GitHub pull request for review",
            description = """
                    Starts an asynchronous review (change-risk, configuration, and observability specialists plus a
                    synthesizer) over the PR diff using Claude models on AWS Bedrock. Returns 202 immediately with a
                    job id; poll GET /api/v1/analyze-pr/{jobId} for status and, once SUCCEEDED, the full report.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Review accepted; poll the returned job"),
            @ApiResponse(responseCode = "400", description = "Malformed PR URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/analyze-pr")
    public ResponseEntity<ReviewJob> submit(
            @Parameter(
                    description = "Full GitHub pull request URL",
                    example = "https://github.com/Vanguard/resiliency-agent.ecs/pull/228",
                    required = true)
            @RequestParam("prUrl") @NotBlank String prUrl,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        ReviewJob job = jobs.submit(prUrl, model);
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/analyze-pr/" + job.jobId()))
                .body(job);
    }

    @Operation(
            summary = "Get the status/result of a review job",
            description = """
                    Returns the job's current state. While PENDING/RUNNING, result is null; once SUCCEEDED the full
                    review report is in `result`; if FAILED, `error` explains why.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job found"),
            @ApiResponse(responseCode = "404", description = "No such job",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/analyze-pr/{jobId}")
    public ResponseEntity<ReviewJob> status(
            @Parameter(description = "Job id returned by the submit call", required = true)
            @PathVariable("jobId") String jobId) {
        return jobs.get(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
