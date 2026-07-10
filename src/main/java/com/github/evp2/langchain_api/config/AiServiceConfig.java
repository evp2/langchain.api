package com.github.evp2.langchain_api.config;

import com.github.evp2.langchain_api.ai.ConfigurationAnalyst;
import com.github.evp2.langchain_api.ai.RiskAnalyst;
import com.github.evp2.langchain_api.ai.Synthesizer;
import com.github.evp2.langchain_api.ai.TraceabilityAnalyst;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the LangChain4j {@link AiServices} proxies. Each sub-agent is bound to its own
 * independently-configured model bean (see {@link BedrockConfig}).
 */
@Configuration
public class AiServiceConfig {

    @Bean
    public RiskAnalyst riskAnalyst(@Qualifier("riskModel") ChatModel model) {
        return AiServices.create(RiskAnalyst.class, model);
    }

    @Bean
    public ConfigurationAnalyst configurationAnalyst(@Qualifier("configurationModel") ChatModel model) {
        return AiServices.create(ConfigurationAnalyst.class, model);
    }

    @Bean
    public TraceabilityAnalyst traceabilityAnalyst(@Qualifier("traceabilityModel") ChatModel model) {
        return AiServices.create(TraceabilityAnalyst.class, model);
    }

    @Bean
    public Synthesizer synthesizer(@Qualifier("synthesizerModel") ChatModel model) {
        return AiServices.create(Synthesizer.class, model);
    }
}
