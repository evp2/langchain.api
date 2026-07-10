package com.github.evp2.langchain_api.model;

/** Lifecycle state of an asynchronous review job. */
public enum JobStatus {
    /** Accepted, not yet started. */
    PENDING,
    /** Currently executing. */
    RUNNING,
    /** Completed; {@code result} is populated. */
    SUCCEEDED,
    /** Failed; {@code error} explains why. */
    FAILED
}
