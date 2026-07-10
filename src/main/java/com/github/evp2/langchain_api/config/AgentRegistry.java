package com.github.evp2.langchain_api.config;

import com.github.evp2.langchain_api.ai.ConfigurationAnalyst;
import com.github.evp2.langchain_api.ai.ObservabilityAnalyst;
import com.github.evp2.langchain_api.ai.RiskAnalyst;
import com.github.evp2.langchain_api.ai.Synthesizer;
import com.github.evp2.langchain_api.model.ModelChoice;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Builds and caches the four review agents ({@link RiskAnalyst}, {@link ConfigurationAnalyst},
 * {@link ObservabilityAnalyst}, {@link Synthesizer}) per selectable {@link ModelChoice}. Each
 * agent keeps its own temperature / max-tokens from {@link BedrockProperties}; only the backing
 * model id changes with the choice. Bundles are built lazily on first use and reused thereafter.
 */
@Component
public class AgentRegistry {

    private final BedrockProperties props;
    private final Map<ModelChoice, Agents> cache = new ConcurrentHashMap<>();
    private final Map<ModelChoice, ChatModel> rawModels = new ConcurrentHashMap<>();
    private volatile BedrockRuntimeClient sharedClient;

    public AgentRegistry(BedrockProperties props) {
        this.props = props;
    }

    /** The set of agents bound to one model choice. */
    public record Agents(
            RiskAnalyst risk,
            ConfigurationAnalyst configuration,
            ObservabilityAnalyst observability,
            Synthesizer synthesizer) {
    }

    public Agents forChoice(ModelChoice choice) {
        return cache.computeIfAbsent(choice, this::build);
    }

    /** A raw chat model for the given choice, for direct (prompt-in, text-out) calls. */
    public ChatModel chatModel(ModelChoice choice) {
        return rawModels.computeIfAbsent(choice, c -> model(c, modelId(c), props.getRisk()));
    }

    /** Resolve the Bedrock model id/ARN backing a choice. */
    public String modelId(ModelChoice choice) {
        String id = switch (choice) {
            case CLAUDE_SONNET -> firstNonBlank(props.getModels().getClaudeSonnet(), props.getRisk().getModelArn());
            case NVIDIA_NEMOTRON -> props.getModels().getNvidiaNemotron();
        };
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("No model id configured for " + choice
                    + ". Set the corresponding bedrock.models.* property / environment variable.");
        }
        return id;
    }

    private Agents build(ModelChoice choice) {
        String modelId = modelId(choice);
        return new Agents(
                dev.langchain4j.service.AiServices.create(RiskAnalyst.class,
                        model(choice, modelId, props.getRisk())),
                dev.langchain4j.service.AiServices.create(ConfigurationAnalyst.class,
                        model(choice, modelId, props.getConfiguration())),
                dev.langchain4j.service.AiServices.create(ObservabilityAnalyst.class,
                        model(choice, modelId, props.getObservability())),
                dev.langchain4j.service.AiServices.create(Synthesizer.class,
                        model(choice, modelId, props.getSynthesizer())));
    }

    private ChatModel model(ModelChoice choice, String modelId, BedrockProperties.Agent agent) {
        var params = DefaultChatRequestParameters.builder()
                .maxOutputTokens(agent.getMaxOutputTokens());
        // Claude Sonnet 5 deprecates the `temperature` parameter and rejects requests that set it;
        // only send it for backends that still accept it.
        if (supportsTemperature(choice)) {
            params.temperature(agent.getTemperature());
        }
        return BedrockChatModel.builder()
                .client(bedrockClient())
                .modelId(modelId)
                .timeout(Duration.ofSeconds(props.getCallTimeoutSeconds()))
                .maxRetries(props.getMaxRetries())
                .defaultRequestParameters(params.build())
                .build();
    }

    /**
     * A shared Bedrock runtime client whose HTTP socket-read timeout matches the per-call budget.
     * The SDK's default read timeout (~30s) fires long before {@code callTimeoutSeconds} on slow,
     * bursty responses (e.g. large-diff Claude completions), so it must be widened here too. Built
     * once and reused across all agents/models.
     */
    private BedrockRuntimeClient bedrockClient() {
        BedrockRuntimeClient existing = sharedClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (sharedClient == null) {
                Duration callTimeout = Duration.ofSeconds(props.getCallTimeoutSeconds());
                sharedClient = BedrockRuntimeClient.builder()
                        .region(Region.of(props.getRegion()))
                        .httpClientBuilder(ApacheHttpClient.builder()
                                .socketTimeout(callTimeout)
                                .connectionTimeout(Duration.ofSeconds(15)))
                        .build();
            }
            return sharedClient;
        }
    }

    private static boolean supportsTemperature(ModelChoice choice) {
        return choice != ModelChoice.CLAUDE_SONNET;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
