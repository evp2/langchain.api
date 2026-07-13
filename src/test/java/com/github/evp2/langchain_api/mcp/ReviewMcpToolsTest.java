package com.github.evp2.langchain_api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.SynthesisRequest;
import com.github.evp2.langchain_api.model.Verdict;
import com.github.evp2.langchain_api.service.CodeReviewService;
import com.github.evp2.langchain_api.service.ReviewJobService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The MCP tools are thin delegates; verify argument passing, model defaulting, and error paths. */
class ReviewMcpToolsTest {

    private static final String PR_URL = "https://github.com/o/r/pull/1";

    private CodeReviewService reviews;
    private ReviewJobService jobs;
    private ReviewMcpTools tools;

    @BeforeEach
    void setUp() {
        reviews = mock(CodeReviewService.class);
        jobs = mock(ReviewJobService.class);
        tools = new ReviewMcpTools(reviews, jobs);
    }

    private static ReviewResponse response() {
        return new ReviewResponse(PR_URL, "o/r", 1, "t", Verdict.GO, "ok", 0,
                Map.of(), List.of(), List.of(),
                new ReviewResponse.ReviewMeta(Map.of(), 0, false, 0), Instant.now());
    }

    @Test
    void analyzePrDelegatesWithGivenModel() {
        ReviewResponse r = response();
        when(reviews.review(PR_URL, ModelChoice.NVIDIA_NEMOTRON)).thenReturn(r);
        assertThat(tools.analyzePr(PR_URL, ModelChoice.NVIDIA_NEMOTRON)).isSameAs(r);
    }

    @Test
    void analyzePrDefaultsNullModelToClaudeSonnet() {
        ReviewResponse r = response();
        when(reviews.review(PR_URL, ModelChoice.CLAUDE_SONNET)).thenReturn(r);
        assertThat(tools.analyzePr(PR_URL, null)).isSameAs(r);
    }

    @Test
    void submitReviewDelegatesAndDefaultsModel() {
        ReviewJob job = ReviewJob.pending("job-1", PR_URL, ModelChoice.CLAUDE_SONNET, Instant.now());
        when(jobs.submit(PR_URL, ModelChoice.CLAUDE_SONNET)).thenReturn(job);
        assertThat(tools.submitReview(PR_URL, null)).isSameAs(job);
    }

    @Test
    void getReviewReturnsJobWhenPresent() {
        ReviewJob job = ReviewJob.pending("job-1", PR_URL, ModelChoice.CLAUDE_SONNET, Instant.now());
        when(jobs.get("job-1")).thenReturn(Optional.of(job));
        assertThat(tools.getReview("job-1")).isSameAs(job);
    }

    @Test
    void getReviewThrowsClearErrorWhenMissing() {
        when(jobs.get("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.getReview("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No review job with id missing");
    }

    @Test
    void synthesizeDelegatesAndDefaultsModel() {
        SynthesisRequest req = new SynthesisRequest("o/r", 1, "t",
                new DimensionAnalysis("chr", List.of()), null, null);
        Synthesis s = new Synthesis(Verdict.CONDITIONAL, "fix first", List.of());
        when(reviews.synthesizeOnly(req, ModelChoice.CLAUDE_SONNET)).thenReturn(s);
        assertThat(tools.synthesize(req, null)).isSameAs(s);
    }
}
