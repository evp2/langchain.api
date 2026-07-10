package com.github.evp2.langchain_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bedrock configuration bound from the {@code bedrock.*} section of {@code application.yml}.
 *
 * <p>Each review sub-agent (risk, configuration, observability, synthesizer) has its own
 * {@link Agent} block, so its model ARN, temperature, and max output tokens can be tuned
 * independently — in YAML or via the per-agent environment variables the YAML references.
 */
@ConfigurationProperties(prefix = "bedrock")
public class BedrockProperties {

    /** AWS region for the Bedrock runtime client (shared by all agents). */
    private String region = "us-east-1";

    /**
     * Per-call timeout for a single Bedrock invocation. Must exceed the slowest model's latency:
     * the SDK default is 60s, but a large-diff Nemotron completion can run longer and would
     * otherwise be aborted mid-call and retried. Shared by all agents.
     */
    private int callTimeoutSeconds = 180;

    /**
     * Retries per Bedrock call. Kept low: a call that exceeds {@link #callTimeoutSeconds} is
     * expensive, and retrying it can blow the overall {@code review.timeout-seconds} budget. Shared
     * by all agents.
     */
    private int maxRetries = 1;

    private final Agent risk = new Agent();
    private final Agent configuration = new Agent();
    private final Agent observability = new Agent();
    private final Agent synthesizer = new Agent();
    private final Models models = new Models();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    public void setCallTimeoutSeconds(int callTimeoutSeconds) {
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Models getModels() {
        return models;
    }

    public Agent getRisk() {
        return risk;
    }

    public Agent getConfiguration() {
        return configuration;
    }

    public Agent getObservability() {
        return observability;
    }

    public Agent getSynthesizer() {
        return synthesizer;
    }

    /**
     * Selectable model backends. Each agent's {@code modelArn} is the default/Claude profile;
     * these ARNs let a request override which underlying model is invoked (see
     * {@link com.github.evp2.langchain_api.model.ModelChoice}).
     */
    public static class Models {

        /** Claude Sonnet inference-profile ARN (falls back to the shared INFERENCE_PROFILE_ARN). */
        private String claudeSonnet;

        /** NVIDIA Nemotron model id / ARN on Bedrock. */
        private String nvidiaNemotron;

        public String getClaudeSonnet() {
            return claudeSonnet;
        }

        public void setClaudeSonnet(String claudeSonnet) {
            this.claudeSonnet = claudeSonnet;
        }

        public String getNvidiaNemotron() {
            return nvidiaNemotron;
        }

        public void setNvidiaNemotron(String nvidiaNemotron) {
            this.nvidiaNemotron = nvidiaNemotron;
        }
    }

    /** Per-agent, independently customizable model settings. */
    public static class Agent {

        /** Bedrock model id / application-inference-profile ARN this agent invokes. */
        private String modelArn;

        /** Sampling temperature (0.0–1.0). */
        private Double temperature = 0.2;

        /** Maximum tokens the agent may generate in one response. */
        private Integer maxOutputTokens = 4096;

        public String getModelArn() {
            return modelArn;
        }

        public void setModelArn(String modelArn) {
            this.modelArn = modelArn;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }
}
