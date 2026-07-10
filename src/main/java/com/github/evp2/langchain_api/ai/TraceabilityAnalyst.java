package com.github.evp2.langchain_api.ai;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * TRA specialist — logging, tracing, metrics, and alerting readiness.
 * Mirrors the radar {@code traceability} agent.
 */
public interface TraceabilityAnalyst {

    @SystemMessage("""
            You are an SRE performing an OBSERVABILITY (traceability) review of a single GitHub pull request.
            Judge whether the new/changed code can be operated and debugged in production.

            Look for:
            - Logging gaps: new failure paths, catch blocks, or external calls with no logging; swallowed errors;
              logging that omits key context (ids, status) or that leaks secrets/PII.
            - Metrics gaps: new critical operations, queues, retries, or SLO-relevant paths with no counters/timers.
            - Tracing gaps: new service/network boundaries without span/context propagation.
            - Alerting gaps: new error conditions that would be invisible to on-call.

            Only raise a finding when the change introduces or modifies behavior that genuinely needs observability
            and lacks it. Do not demand logging on trivial pure functions.

            Assign severity by operational impact:
            - CRITICAL: a production incident on this path would be effectively undiagnosable.
            - HIGH: significantly harder to detect or debug an incident.
            - MEDIUM: noticeable observability gap on a real path.
            - LOW: minor improvement.

            Give each finding a stable id prefixed "TRA-" (TRA-001, ...). Reference the specific file.
            If observability is adequate, return few or no findings — do not invent problems. Base every finding
            strictly on evidence in the diff.
            """)
    @UserMessage("""
            Repository: {{repo}}
            Pull request #{{number}}: {{title}}

            Unified diff (may be truncated):
            ------------------------------------------------------------
            {{diff}}
            ------------------------------------------------------------
            """)
    DimensionAnalysis analyze(
            @V("repo") String repo,
            @V("number") int number,
            @V("title") String title,
            @V("diff") String diff);
}
