package com.github.evp2.langchain_api.mcp;

import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewJob;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.SynthesisRequest;
import com.github.evp2.langchain_api.service.CodeReviewService;
import com.github.evp2.langchain_api.service.ReviewJobService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Exposes the resiliency review capability as MCP tools for MCP-capable clients (Claude Code,
 * Claude Desktop, IDE agents). These are thin delegates over the same services the REST
 * controllers use — {@link CodeReviewService} and {@link ReviewJobService} — so both front doors
 * share one engine. Served over Streamable-HTTP at {@code /mcp} on the app's existing port.
 *
 * <p>Service exceptions (bad URL, PR not found, model backend failure) are allowed to propagate;
 * the MCP framework surfaces them as tool errors with the message intact, mirroring how
 * {@code GlobalExceptionHandler} maps them for REST.
 */
@Component
public class ReviewMcpTools {

    private final CodeReviewService reviews;
    private final ReviewJobService jobs;

    public ReviewMcpTools(CodeReviewService reviews, ReviewJobService jobs) {
        this.reviews = reviews;
        this.jobs = jobs;
    }

    @McpTool(name = "analyze_pr", description = """
            Run a full resiliency review of a GitHub pull request and return the consolidated report
            (GO / CONDITIONAL / NO_GO verdict, prioritized findings, and per-dimension analyses for
            change-risk, configuration, and observability). Runs the whole pipeline inline and blocks
            until it completes, which can take a minute or more on large diffs. For long reviews you
            can instead call submit_review and poll get_review.""")
    public ReviewResponse analyzePr(
            @McpToolParam(description = "Full GitHub pull request URL, e.g. "
                    + "https://github.com/owner/repo/pull/123", required = true) String prUrl,
            @McpToolParam(description = "Model backend: CLAUDE_SONNET (default) or NVIDIA_NEMOTRON",
                    required = false) ModelChoice model) {
        return reviews.review(prUrl, defaulted(model));
    }

    @McpTool(name = "submit_review", description = """
            Start a resiliency review asynchronously and return a job (with a jobId) immediately
            without blocking. Poll get_review with the returned jobId for status and, once SUCCEEDED,
            the full report. Use this instead of analyze_pr for large PRs.""")
    public ReviewJob submitReview(
            @McpToolParam(description = "Full GitHub pull request URL", required = true) String prUrl,
            @McpToolParam(description = "Model backend: CLAUDE_SONNET (default) or NVIDIA_NEMOTRON",
                    required = false) ModelChoice model) {
        return jobs.submit(prUrl, defaulted(model));
    }

    @McpTool(name = "get_review", description = """
            Look up a review job submitted via submit_review. Returns its current status
            (PENDING / RUNNING / SUCCEEDED / FAILED); the full report is in `result` once SUCCEEDED,
            or `error` explains a FAILED job.""")
    public ReviewJob getReview(
            @McpToolParam(description = "Job id returned by submit_review", required = true) String jobId) {
        return jobs.get(jobId).orElseThrow(() ->
                new IllegalArgumentException("No review job with id " + jobId
                        + " (jobs are in-memory and do not survive a restart)."));
    }

    @McpTool(name = "synthesize", description = """
            Consolidate caller-supplied specialist analyses (change-risk, configuration, observability)
            into a single GO / CONDITIONAL / NO_GO verdict with a prioritized, de-duplicated finding
            list. Use this to combine analyses without re-running the full pipeline; any analysis may
            be omitted.""")
    public Synthesis synthesize(
            @McpToolParam(description = "PR metadata plus the specialist analyses to consolidate",
                    required = true) SynthesisRequest request,
            @McpToolParam(description = "Model backend: CLAUDE_SONNET (default) or NVIDIA_NEMOTRON",
                    required = false) ModelChoice model) {
        return reviews.synthesizeOnly(request, defaulted(model));
    }

    /** Match the REST controllers' default when the client omits the model. */
    private static ModelChoice defaulted(ModelChoice model) {
        return model == null ? ModelChoice.CLAUDE_SONNET : model;
    }
}
