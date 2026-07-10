package com.github.evp2.langchain_api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.evp2.langchain_api.model.ModelChoice;
import org.junit.jupiter.api.Test;

/** Model-id resolution only — building the actual Bedrock-backed agents needs AWS and stays untested here. */
class AgentRegistryTest {

    private final BedrockProperties props = new BedrockProperties();
    private final AgentRegistry registry = new AgentRegistry(props);

    @Test
    void claudeSonnetResolvesFromModelsBlock() {
        props.getModels().setClaudeSonnet("arn:claude");
        props.getRisk().setModelArn("arn:risk-fallback");
        assertThat(registry.modelId(ModelChoice.CLAUDE_SONNET)).isEqualTo("arn:claude");
    }

    @Test
    void claudeSonnetFallsBackToRiskModelArn() {
        props.getRisk().setModelArn("arn:risk-fallback");
        assertThat(registry.modelId(ModelChoice.CLAUDE_SONNET)).isEqualTo("arn:risk-fallback");
    }

    @Test
    void blankClaudeSonnetFallsBackToRiskModelArn() {
        props.getModels().setClaudeSonnet("  ");
        props.getRisk().setModelArn("arn:risk-fallback");
        assertThat(registry.modelId(ModelChoice.CLAUDE_SONNET)).isEqualTo("arn:risk-fallback");
    }

    @Test
    void nemotronResolvesFromModelsBlockOnly() {
        props.getModels().setNvidiaNemotron("nemotron-id");
        assertThat(registry.modelId(ModelChoice.NVIDIA_NEMOTRON)).isEqualTo("nemotron-id");
    }

    @Test
    void unconfiguredChoiceThrowsWithGuidance() {
        assertThatThrownBy(() -> registry.modelId(ModelChoice.NVIDIA_NEMOTRON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NVIDIA_NEMOTRON")
                .hasMessageContaining("bedrock.models");
    }

    @Test
    void temperatureIsDroppedForClaudeSonnetOnly() {
        assertThat(AgentRegistry.supportsTemperature(ModelChoice.CLAUDE_SONNET)).isFalse();
        assertThat(AgentRegistry.supportsTemperature(ModelChoice.NVIDIA_NEMOTRON)).isTrue();
    }
}
