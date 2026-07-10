package com.github.evp2.langchain_api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * An asynchronous review job. Returned (without {@code result}) when a review is submitted, and
 * again — populated — when polled. {@code result} is set only once {@code status} is
 * {@link JobStatus#SUCCEEDED}; {@code error} only when {@link JobStatus#FAILED}.
 */
public record ReviewJob(
        @Schema(description = "Opaque job identifier", example = "3f2a9c1e-...") String jobId,
        @Schema(description = "Current lifecycle state") JobStatus status,
        @Schema(description = "Reviewed pull request URL") String prUrl,
        @Schema(description = "Model backend used for the review") ModelChoice model,
        @Schema(description = "Full review report; present only when status is SUCCEEDED")
        ReviewResponse result,
        @Schema(description = "Failure message; present only when status is FAILED") String error,
        @Schema(description = "When the job was submitted") Instant submittedAt,
        @Schema(description = "When the job reached a terminal state (SUCCEEDED/FAILED)") Instant completedAt) {

    /** A freshly-submitted, not-yet-started job. */
    public static ReviewJob pending(String jobId, String prUrl, ModelChoice model, Instant now) {
        return new ReviewJob(jobId, JobStatus.PENDING, prUrl, model, null, null, now, null);
    }

    public ReviewJob running() {
        return new ReviewJob(jobId, JobStatus.RUNNING, prUrl, model, null, null, submittedAt, null);
    }

    public ReviewJob succeeded(ReviewResponse result, Instant now) {
        return new ReviewJob(jobId, JobStatus.SUCCEEDED, prUrl, model, result, null, submittedAt, now);
    }

    public ReviewJob failed(String error, Instant now) {
        return new ReviewJob(jobId, JobStatus.FAILED, prUrl, model, null, error, submittedAt, now);
    }
}
