package com.github.evp2.langchain_api.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.service.ReviewJobService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CodeReviewController.class)
class CodeReviewControllerTest {

    private static final String PR_URL = "https://github.com/o/r/pull/1";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReviewJobService jobs;

    @Test
    void submitReturns202WithJobAndLocation() throws Exception {
        ReviewJob job = ReviewJob.pending("job-1", PR_URL, ModelChoice.CLAUDE_SONNET, Instant.now());
        when(jobs.submit(PR_URL, ModelChoice.CLAUDE_SONNET)).thenReturn(job);

        mvc.perform(post("/api/v1/analyze-pr").param("prUrl", PR_URL))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/analyze-pr/job-1"))
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitPassesExplicitModelChoice() throws Exception {
        ReviewJob job = ReviewJob.pending("job-2", PR_URL, ModelChoice.NVIDIA_NEMOTRON, Instant.now());
        when(jobs.submit(PR_URL, ModelChoice.NVIDIA_NEMOTRON)).thenReturn(job);

        mvc.perform(post("/api/v1/analyze-pr")
                        .param("prUrl", PR_URL)
                        .param("model", "NVIDIA_NEMOTRON"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.model").value("NVIDIA_NEMOTRON"));
    }

    @Test
    void statusReturnsJobWhenFound() throws Exception {
        ReviewJob job = ReviewJob.pending("job-1", PR_URL, ModelChoice.CLAUDE_SONNET, Instant.now())
                .running();
        when(jobs.get("job-1")).thenReturn(Optional.of(job));

        mvc.perform(get("/api/v1/analyze-pr/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void statusReturns404ForUnknownJob() throws Exception {
        when(jobs.get(eq("missing"))).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/analyze-pr/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedJobExposesError() throws Exception {
        ReviewJob job = ReviewJob.pending("job-1", PR_URL, ModelChoice.CLAUDE_SONNET, Instant.now())
                .failed("backend down", Instant.now());
        when(jobs.get("job-1")).thenReturn(Optional.of(job));

        mvc.perform(get("/api/v1/analyze-pr/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("backend down"));
    }
}
