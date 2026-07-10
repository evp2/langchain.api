package com.github.evp2.langchain_api.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bedrock configuration bound from the {@code bedrock.*} section of {@code application.yml}.
 *
 * <p>Each review sub-agent (risk, configuration, traceability, synthesizer) has its own
 * {@link Agent} block, so its model ARN, temperature, and max output tokens can be tuned
 * independently — in YAML or via the per-agent environment variables the YAML references.
 */
@ConfigurationProperties(prefix = "bedrock")
public class BedrockProperties {

    /** AWS region for the Bedrock runtime client (shared by all agents). */
    private String region = "us-east-1";

    private final Agent risk = new Agent();
    private final Agent configuration = new Agent();
    private final Agent traceability = new Agent();
    private final Agent synthesizer = new Agent();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Agent getRisk() {
        return risk;
    }

    public Agent getConfiguration() {
        return configuration;
    }

    public Agent getTraceability() {
        return traceability;
    }

    public Agent getSynthesizer() {
        return synthesizer;
    }

    /** Resolved model ARN per agent, in a stable order — surfaced in the review response metadata. */
    public Map<String, String> agentModelArns() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("risk", risk.getModelArn());
        models.put("configuration", configuration.getModelArn());
        models.put("traceability", traceability.getModelArn());
        models.put("synthesizer", synthesizer.getModelArn());
        return models;
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
