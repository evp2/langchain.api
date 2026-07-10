package com.github.evp2.langchain_api.model;

/** Overall deployment decision for the PR, mirroring the radar code-review skill. */
public enum Verdict {
    /** Safe to merge / deploy. */
    GO,
    /** Merge only after addressing the noted conditions. */
    CONDITIONAL,
    /** Do not merge as-is. */
    NO_GO
}
