package com.github.evp2.langchain_api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewJobTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:05:00Z");

    private static ReviewResponse response() {
        return new ReviewResponse("url", "o/r", 1, "t", Verdict.GO, "ok", 0,
                Map.of(), List.of(), List.of(),
                new ReviewResponse.ReviewMeta(Map.of(), 0, false, 0), T1);
    }

    @Test
    void pendingJobHasNoResultOrError() {
        ReviewJob job = ReviewJob.pending("id", "url", ModelChoice.CLAUDE_SONNET, T0);
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.result()).isNull();
        assertThat(job.error()).isNull();
        assertThat(job.submittedAt()).isEqualTo(T0);
        assertThat(job.completedAt()).isNull();
    }

    @Test
    void runningKeepsIdentityAndClearsOutcome() {
        ReviewJob job = ReviewJob.pending("id", "url", ModelChoice.NVIDIA_NEMOTRON, T0).running();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.jobId()).isEqualTo("id");
        assertThat(job.prUrl()).isEqualTo("url");
        assertThat(job.model()).isEqualTo(ModelChoice.NVIDIA_NEMOTRON);
        assertThat(job.result()).isNull();
        assertThat(job.completedAt()).isNull();
    }

    @Test
    void succeededCarriesResultAndCompletionTime() {
        ReviewResponse result = response();
        ReviewJob job = ReviewJob.pending("id", "url", ModelChoice.CLAUDE_SONNET, T0)
                .running()
                .succeeded(result, T1);
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.result()).isSameAs(result);
        assertThat(job.error()).isNull();
        assertThat(job.submittedAt()).isEqualTo(T0);
        assertThat(job.completedAt()).isEqualTo(T1);
    }

    @Test
    void failedCarriesErrorOnly() {
        ReviewJob job = ReviewJob.pending("id", "url", ModelChoice.CLAUDE_SONNET, T0)
                .running()
                .failed("boom", T1);
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).isEqualTo("boom");
        assertThat(job.result()).isNull();
        assertThat(job.completedAt()).isEqualTo(T1);
    }

    @Test
    void transitionsReturnNewInstances() {
        ReviewJob pending = ReviewJob.pending("id", "url", ModelChoice.CLAUDE_SONNET, T0);
        ReviewJob running = pending.running();
        assertThat(running).isNotSameAs(pending);
        assertThat(pending.status()).isEqualTo(JobStatus.PENDING);
    }
}
