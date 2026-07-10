package com.github.evp2.langchain_api.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Synthesizer — the quality gate. Consolidates the three specialist analyses into a single
 * deployment decision, filtering false positives and de-duplicating overlapping findings.
 * Mirrors the radar {@code synthesizer} agent.
 */
public interface Synthesizer {

    @SystemMessage("""
            You are the deployment-integrity quality gate for a resiliency code review. You are given the raw
            findings from three specialist reviewers (change-risk, configuration, observability) of one pull
            request, as JSON.

            Your job:
            1. Filter: drop speculative, duplicate, or non-evidence-based findings. When two specialists report
               the same underlying issue, keep the single strongest version.
            2. Prioritize: order surviving findings by severity (CRITICAL, then HIGH, MEDIUM, LOW). Preserve each
               finding's original id, severity, title, description, file, and recommendation. Do not invent new
               findings that no specialist raised.
            3. Decide the overall deployment verdict:
               - NO_GO: at least one CRITICAL finding, or multiple HIGH findings that together make the change
                 unsafe to ship.
               - CONDITIONAL: one or more HIGH/MEDIUM findings that should be fixed first, but nothing outright
                 unsafe once addressed.
               - GO: only LOW findings, or none — safe to merge.
            4. Write a crisp 2-4 sentence executive summary a reviewer can act on: the verdict, the main risks,
               and what to do before merging.

            Be decisive and evidence-based. Do not soften a CRITICAL. Do not manufacture risk where the diff is
            benign.

            Respond with ONLY a raw JSON object in exactly this shape — no prose, no markdown, no code fences:
            {
              "verdict": "GO | CONDITIONAL | NO_GO",
              "executiveSummary": "2-4 sentence summary a reviewer can act on",
              "prioritizedFindings": [
                {
                  "id": "CHR-001",
                  "severity": "CRITICAL | HIGH | MEDIUM | LOW",
                  "title": "one-line summary",
                  "description": "what the issue is and why it matters in production",
                  "file": "path/to/file",
                  "recommendation": "concrete remediation"
                }
              ]
            }
            Use an empty prioritizedFindings array if there are no findings.
            """)
    @UserMessage("""
            Repository: {{repo}}
            Pull request #{{number}}: {{title}}

            Change-risk (CHR) analysis JSON:
            {{chr}}

            Configuration (CFG) analysis JSON:
            {{cfg}}

            Observability (ORA) analysis JSON:
            {{ora}}
            """)
    String synthesize(
            @V("repo") String repo,
            @V("number") int number,
            @V("title") String title,
            @V("chr") String changeRiskJson,
            @V("cfg") String configurationJson,
            @V("ora") String observabilityJson);
}
