package com.github.evp2.langchain_api.model;

/** Selectable model backend for a review/agent request. */
public enum ModelChoice {
    /** Anthropic Claude Sonnet on Bedrock (default). */
    CLAUDE_SONNET,
    /** NVIDIA Nemotron on Bedrock. */
    NVIDIA_NEMOTRON
}
