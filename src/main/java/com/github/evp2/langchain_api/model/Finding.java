package com.github.evp2.langchain_api.model;

/**
 * A single production-risk finding. Produced by the specialist AI services and
 * carried through, unchanged, into the final report.
 *
 * @param id             short stable identifier, e.g. "CHR-001"
 * @param severity       impact level
 * @param title          one-line summary of the issue
 * @param description    what the issue is and why it matters in production
 * @param file           the primary file the finding relates to (may be null/blank)
 * @param recommendation concrete remediation
 */
public record Finding(
        String id,
        Severity severity,
        String title,
        String description,
        String file,
        String recommendation) {
}
