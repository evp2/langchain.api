package com.github.evp2.langchain_api.model;

/** The three resiliency dimensions analyzed by the radar code-review skill. */
public enum Dimension {
    /** CHR: code changes, failure modes, breaking changes. */
    CHANGE_RISK,
    /** CFG: config issues, environment mismatches. */
    CONFIGURATION,
    /** TRA: logging, tracing, monitoring gaps. */
    OBSERVABILITY
}
