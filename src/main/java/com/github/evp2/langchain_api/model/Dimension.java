package com.github.evp2.langchain_api.model;

/** The three resiliency dimensions analyzed during a review. */
public enum Dimension {
    /** CHR: code changes, failure modes, breaking changes. */
    CHANGE_RISK,
    /** CFG: config issues, environment mismatches. */
    CONFIGURATION,
    /** ORA: logging, tracing, monitoring gaps. */
    OBSERVABILITY
}
