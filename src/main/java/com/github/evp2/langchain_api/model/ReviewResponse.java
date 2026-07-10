package com.github.evp2.langchain_api.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The full code-review report returned by the API.
 *
 * @param prUrl               the reviewed pull request URL
 * @param repository          "owner/repo"
 * @param prNumber            pull request number
 * @param prTitle             pull request title
 * @param verdict             overall deployment decision
 * @param executiveSummary    consolidated summary
 * @param totalFindings       count of prioritized findings
 * @param severityCounts      count of findings per severity level
 * @param dimensions          per-dimension specialist analyses
 * @param prioritizedFindings merged findings, highest severity first
 * @param meta                run metadata (models used, truncation, timing)
 * @param generatedAt         when the report was produced
 */
public record ReviewResponse(
        String prUrl,
        String repository,
        int prNumber,
        String prTitle,
        Verdict verdict,
        String executiveSummary,
        int totalFindings,
        Map<Severity, Integer> severityCounts,
        List<DimensionResult> dimensions,
        List<Finding> prioritizedFindings,
        ReviewMeta meta,
        Instant generatedAt) {

    /**
     * Metadata about how the review was run.
     *
     * @param agentModels    resolved Bedrock model id per sub-agent (risk, configuration,
     *                       observability, synthesizer) — each independently configurable
     * @param changedFiles   number of files changed in the PR
     * @param diffTruncated  whether the diff was truncated before analysis
     * @param durationMillis wall-clock duration of the review
     */
    public record ReviewMeta(
            Map<String, String> agentModels,
            int changedFiles,
            boolean diffTruncated,
            long durationMillis) {
    }
}
