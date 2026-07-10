package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.evp2.langchain_api.model.JobStatus;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.model.Verdict;
import com.github.evp2.langchain_api.support.DirectExecutorService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Job lifecycle with a same-thread executor: submit() returns only after the job already ran. */
class ReviewJobServiceTest {

    private static final String PR_URL = "https://github.com/o/r/pull/1";

    private CodeReviewService reviews;
    private ReviewJobService service;

    @BeforeEach
    void setUp() {
        reviews = mock(CodeReviewService.class);
        service = new ReviewJobService(reviews, new DirectExecutorService());
    }

    private static ReviewResponse response() {
        return new ReviewResponse(PR_URL, "o/r", 1, "t", Verdict.GO, "ok", 0,
                Map.of(), List.of(), List.of(),
                new ReviewResponse.ReviewMeta(Map.of(), 0, false, 0), Instant.now());
    }

    @Test
    void successfulJobEndsSucceededWithResult() {
        ReviewResponse result = response();
        when(reviews.review(anyString(), any())).thenReturn(result);

        ReviewJob submitted = service.submit(PR_URL, ModelChoice.CLAUDE_SONNET);
        ReviewJob polled = service.get(submitted.jobId()).orElseThrow();

        assertThat(polled.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(polled.result()).isSameAs(result);
        assertThat(polled.error()).isNull();
        assertThat(polled.completedAt()).isNotNull();
    }

    @Test
    void failingJobEndsFailedWithMessage() {
        when(reviews.review(anyString(), any())).thenThrow(new ModelBackendException("backend down"));

        ReviewJob submitted = service.submit(PR_URL, ModelChoice.CLAUDE_SONNET);
        ReviewJob polled = service.get(submitted.jobId()).orElseThrow();

        assertThat(polled.status()).isEqualTo(JobStatus.FAILED);
        assertThat(polled.error()).isEqualTo("backend down");
        assertThat(polled.result()).isNull();
    }

    @Test
    void messagelessExceptionFallsBackToClassName() {
        when(reviews.review(anyString(), any())).thenThrow(new NullPointerException());

        ReviewJob submitted = service.submit(PR_URL, ModelChoice.CLAUDE_SONNET);
        assertThat(service.get(submitted.jobId()).orElseThrow().error())
                .isEqualTo("NullPointerException");
    }

    @Test
    void submitReturnsPendingSnapshot() {
        when(reviews.review(anyString(), any())).thenReturn(response());
        ReviewJob submitted = service.submit(PR_URL, ModelChoice.NVIDIA_NEMOTRON);
        // The returned snapshot is the PENDING record even though the direct executor already finished.
        assertThat(submitted.status()).isEqualTo(JobStatus.PENDING);
        assertThat(submitted.model()).isEqualTo(ModelChoice.NVIDIA_NEMOTRON);
        assertThat(submitted.jobId()).isNotBlank();
    }

    @Test
    void unknownJobIdIsEmpty() {
        assertThat(service.get("nope")).isEmpty();
    }
}
