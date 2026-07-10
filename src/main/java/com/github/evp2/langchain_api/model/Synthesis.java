package com.github.evp2.langchain_api.model;

import java.util.List;

/**
 * Output of the synthesizer AI service: the consolidated deployment decision.
 *
 * @param verdict             overall GO / CONDITIONAL / NO_GO decision
 * @param executiveSummary    2-4 sentence summary a reviewer can act on
 * @param prioritizedFindings the merged, de-duplicated findings ordered by severity
 */
public record Synthesis(
        Verdict verdict,
        String executiveSummary,
        List<Finding> prioritizedFindings) {
}
