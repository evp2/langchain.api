package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.evp2.langchain_api.ai.ConfigurationAnalyst;
import com.github.evp2.langchain_api.ai.ObservabilityAnalyst;
import com.github.evp2.langchain_api.ai.RiskAnalyst;
import com.github.evp2.langchain_api.ai.Synthesizer;
import com.github.evp2.langchain_api.config.AgentRegistry;
import com.github.evp2.langchain_api.config.BedrockProperties;
import com.github.evp2.langchain_api.model.Dimension;
import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.Finding;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.model.Severity;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.SynthesisRequest;
import com.github.evp2.langchain_api.model.Verdict;
import com.github.evp2.langchain_api.support.DirectExecutorService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orchestration tests for the full review pipeline with the model backends mocked.
 * These pin down the app's core degradation promises: one specialist failing degrades,
 * all failing aborts, and a synthesizer failure falls back to a deterministic verdict.
 */
class CodeReviewServiceTest {

    private static final String PR_URL = "https://github.com/o/r/pull/1";

    private static final String CHR_JSON = """
            {"summary":"chr summary","findings":[
              {"id":"CHR-001","severity":"CRITICAL","title":"boom","description":"d","file":"A.java","recommendation":"fix"}
            ]}""";
    private static final String CFG_JSON = """
            {"summary":"cfg summary","findings":[
              {"id":"CFG-001","severity":"LOW","title":"nit","description":"d","file":"app.yml","recommendation":"fix"}
            ]}""";
    private static final String ORA_JSON = "{\"summary\":\"ora summary\",\"findings\":[]}";
    private static final String SYNTH_JSON = """
            {"verdict":"NO_GO","executiveSummary":"critical found","prioritizedFindings":[
              {"id":"CFG-001","severity":"LOW","title":"nit","description":"d","file":"app.yml","recommendation":"fix"},
              {"id":"CHR-001","severity":"CRITICAL","title":"boom","description":"d","file":"A.java","recommendation":"fix"}
            ]}""";

    private GitHubPrService gitHub;
    private AgentRegistry registry;
    private RiskAnalyst risk;
    private ConfigurationAnalyst configuration;
    private ObservabilityAnalyst observability;
    private Synthesizer synthesizer;
    private CodeReviewService service;

    @BeforeEach
    void setUp() {
        gitHub = mock(GitHubPrService.class);
        registry = mock(AgentRegistry.class);
        risk = mock(RiskAnalyst.class);
        configuration = mock(ConfigurationAnalyst.class);
        observability = mock(ObservabilityAnalyst.class);
        synthesizer = mock(Synthesizer.class);
        when(registry.forChoice(any())).thenReturn(
                new AgentRegistry.Agents(risk, configuration, observability, synthesizer));
        when(registry.modelId(any())).thenReturn("test-model");
        when(gitHub.fetch(PR_URL)).thenReturn(
                new PullRequest("o", "r", 1, "My PR", "diff --git a/A.java", 2, false));
        service = new CodeReviewService(
                gitHub, registry, new DirectExecutorService(), new BedrockProperties(), 5);
    }

    private void allSpecialistsSucceed() {
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(CHR_JSON);
        when(configuration.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(CFG_JSON);
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(ORA_JSON);
    }

    @Test
    void happyPathAssemblesFullReport() {
        allSpecialistsSucceed();
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SYNTH_JSON);

        ReviewResponse r = service.review(PR_URL, ModelChoice.CLAUDE_SONNET);

        assertThat(r.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(r.executiveSummary()).isEqualTo("critical found");
        assertThat(r.repository()).isEqualTo("o/r");
        assertThat(r.totalFindings()).isEqualTo(2);
        // Synthesizer output is re-prioritized: CRITICAL first even though the model listed it second.
        assertThat(r.prioritizedFindings()).extracting(Finding::id).containsExactly("CHR-001", "CFG-001");
        assertThat(r.severityCounts().get(Severity.CRITICAL)).isEqualTo(1);
        assertThat(r.severityCounts().get(Severity.LOW)).isEqualTo(1);
        assertThat(r.dimensions()).extracting(d -> d.dimension()).containsExactly(
                Dimension.CHANGE_RISK, Dimension.CONFIGURATION, Dimension.OBSERVABILITY);
        assertThat(r.meta().agentModels()).containsEntry("risk", "test-model");
    }

    @Test
    void fencedSpecialistJsonIsStillParsed() {
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("```json\n" + CHR_JSON + "\n```");
        when(configuration.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(CFG_JSON);
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(ORA_JSON);
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SYNTH_JSON);

        ReviewResponse r = service.review(PR_URL, ModelChoice.CLAUDE_SONNET);
        assertThat(r.dimensions().getFirst().findings()).hasSize(1);
    }

    @Test
    void oneSpecialistFailingDegradesThatDimensionOnly() {
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("bedrock exploded"));
        when(configuration.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(CFG_JSON);
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(ORA_JSON);
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SYNTH_JSON);

        ReviewResponse r = service.review(PR_URL, ModelChoice.CLAUDE_SONNET);

        var chr = r.dimensions().getFirst();
        assertThat(chr.summary()).contains("failed").contains("bedrock exploded");
        assertThat(chr.findings()).isEmpty();
        assertThat(r.dimensions().get(1).findings()).hasSize(1);
    }

    @Test
    void unparseableSpecialistOutputDegradesLikeAFailure() {
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn("sorry, no JSON today");
        when(configuration.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(CFG_JSON);
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(ORA_JSON);
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SYNTH_JSON);

        ReviewResponse r = service.review(PR_URL, ModelChoice.CLAUDE_SONNET);
        // The degradation note carries the parse failure's root cause (Jackson's message).
        assertThat(r.dimensions().getFirst().summary()).contains("CHANGE_RISK analysis failed");
        assertThat(r.dimensions().getFirst().findings()).isEmpty();
    }

    @Test
    void allSpecialistsFailingThrowsBackendException() {
        RuntimeException down = new RuntimeException("backend down");
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString())).thenThrow(down);
        when(configuration.analyze(anyString(), anyInt(), anyString(), anyString())).thenThrow(down);
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenThrow(down);

        assertThatThrownBy(() -> service.review(PR_URL, ModelChoice.CLAUDE_SONNET))
                .isInstanceOf(ModelBackendException.class)
                .hasMessageContaining("All specialist analyses failed")
                .hasMessageContaining("backend down");
    }

    @Test
    void synthesizerFailureFallsBackToDerivedVerdict() {
        allSpecialistsSucceed();
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("synth down"));

        ReviewResponse r = service.review(PR_URL, ModelChoice.CLAUDE_SONNET);

        // CHR produced a CRITICAL finding, so the deterministic fallback must say NO_GO.
        assertThat(r.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(r.executiveSummary()).contains("Synthesizer unavailable");
        assertThat(r.prioritizedFindings()).extracting(Finding::id).containsExactly("CHR-001", "CFG-001");
    }

    @Test
    void emptyDiffAbortsBeforeCallingModels() {
        when(gitHub.fetch(PR_URL)).thenReturn(new PullRequest("o", "r", 1, "t", "  ", 0, false));
        assertThatThrownBy(() -> service.review(PR_URL, ModelChoice.CLAUDE_SONNET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("diff is empty");
    }

    @Test
    void synthesizeOnlyTreatsMissingAnalysesAsEmpty() {
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("{\"verdict\":\"GO\",\"executiveSummary\":\"fine\",\"prioritizedFindings\":[]}");

        Synthesis s = service.synthesizeOnly(
                new SynthesisRequest("o/r", 1, "t",
                        new DimensionAnalysis("chr", List.of()), null, null),
                ModelChoice.CLAUDE_SONNET);
        assertThat(s.verdict()).isEqualTo(Verdict.GO);
    }

    @Test
    void synthesizeOnlyWrapsFailuresAsBackendException() {
        when(synthesizer.synthesize(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("nope"));
        assertThatThrownBy(() -> service.synthesizeOnly(
                new SynthesisRequest("o/r", 1, "t", null, null, null), ModelChoice.CLAUDE_SONNET))
                .isInstanceOf(ModelBackendException.class)
                .hasMessageContaining("Synthesizer failed");
    }

    @Test
    void singleAgentAnalysisWrapsFailuresAsBackendException() {
        when(risk.analyze(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("nope"));
        assertThatThrownBy(() -> service.analyzeChangeRisk(PR_URL, ModelChoice.CLAUDE_SONNET))
                .isInstanceOf(ModelBackendException.class)
                .hasMessageContaining("CHANGE_RISK analysis failed");
    }

    @Test
    void singleAgentAnalysisReturnsParsedFindings() {
        when(observability.analyze(anyString(), anyInt(), anyString(), anyString())).thenReturn(ORA_JSON);
        DimensionAnalysis a = service.analyzeObservability(PR_URL, ModelChoice.CLAUDE_SONNET);
        assertThat(a.summary()).isEqualTo("ora summary");
        assertThat(a.findings()).isEmpty();
    }
}
