package com.github.evp2.langchain_api.config;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

/**
 * Wires one LangChain4j {@link ChatModel} per review sub-agent, each configured independently
 * from its own {@link BedrockProperties.Agent} block. All target the account's Anthropic models
 * on AWS Bedrock (Converse API); AWS credentials come from the default provider chain.
 */
@Configuration
@EnableConfigurationProperties(BedrockProperties.class)
public class BedrockConfig {

    private final BedrockProperties props;

    public BedrockConfig(BedrockProperties props) {
        this.props = props;
    }

    private ChatModel build(BedrockProperties.Agent agent) {
        return BedrockChatModel.builder()
                .modelId(agent.getModelArn())
                .region(Region.of(props.getRegion()))
                .maxRetries(2)
                .defaultRequestParameters(DefaultChatRequestParameters.builder()
                        .temperature(agent.getTemperature())
                        .maxOutputTokens(agent.getMaxOutputTokens())
                        .build())
                .build();
    }

    /** Change-risk (CHR) specialist model. */
    @Bean
    public ChatModel riskModel() {
        return build(props.getRisk());
    }

    /** Configuration (CFG) specialist model. */
    @Bean
    public ChatModel configurationModel() {
        return build(props.getConfiguration());
    }

    /** Observability/traceability (TRA) specialist model. */
    @Bean
    public ChatModel traceabilityModel() {
        return build(props.getTraceability());
    }

    /** Synthesizer / quality-gate model. */
    @Bean
    public ChatModel synthesizerModel() {
        return build(props.getSynthesizer());
    }
}
