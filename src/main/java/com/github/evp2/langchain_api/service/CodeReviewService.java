package com.github.evp2.langchain_api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.evp2.langchain_api.ai.Synthesizer;
import com.github.evp2.langchain_api.config.AgentRegistry;
import com.github.evp2.langchain_api.config.BedrockProperties;
import com.github.evp2.langchain_api.model.Dimension;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.DimensionResult;
import com.github.evp2.langchain_api.model.Finding;
import com.github.evp2.langchain_api.model.ReviewResponse;
import com.github.evp2.langchain_api.model.Severity;
import com.github.evp2.langchain_api.model.Synthesis;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a full code review: fetch the PR, run the three specialist reviewers
 * concurrently, synthesize a verdict, and assemble the API response. This is the
 * web-native counterpart to the radar {@code /code-review} skill's agent pipeline.
 */
@Service
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);

    private final GitHubPrService gitHub;
    private final AgentRegistry agents;
    private final ExecutorService reviewExecutor;
    // Jackson 2 (bundled via langchain4j) — Spring Boot 4's managed ObjectMapper is Jackson 3.
    private final ObjectMapper mapper = new ObjectMapper();
    // Parses model text into typed objects, tolerating markdown code fences the models may add.
    private final JsonResponseParser parser = JsonResponseParser.create();
    private final int timeoutSeconds;
    private final BedrockProperties bedrockProperties;

    public CodeReviewService(
            GitHubPrService gitHub,
            AgentRegistry agents,
            ExecutorService reviewExecutor,
            BedrockProperties bedrockProperties,
            @Value("${review.timeout-seconds}") int timeoutSeconds) {
        this.gitHub = gitHub;
        this.agents = agents;
        this.reviewExecutor = reviewExecutor;
        this.bedrockProperties = bedrockProperties;
        this.timeoutSeconds = timeoutSeconds;
    }

    public ReviewResponse review(String prUrl, ModelChoice model) {
        long start = System.currentTimeMillis();
        AgentRegistry.Agents a = agents.forChoice(model);
        PullRequest pr = gitHub.fetch(prUrl);

        if (pr.diff() == null || pr.diff().isBlank()) {
            throw new IllegalStateException("Pull request diff is empty — nothing to review.");
        }

        // Run the three specialists concurrently. A single specialist failing must not
        // sink the whole review — it degrades to an empty analysis with a note.
        CompletableFuture<DimensionAnalysis> chrF = runSpecialist(
                Dimension.CHANGE_RISK,
                () -> analysis(a.risk().analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));
        CompletableFuture<DimensionAnalysis> cfgF = runSpecialist(
                Dimension.CONFIGURATION,
                () -> analysis(a.configuration().analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));
        CompletableFuture<DimensionAnalysis> traF = runSpecialist(
                Dimension.OBSERVABILITY,
                () -> analysis(a.observability().analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));

        Outcome chrO = join(chrF, Dimension.CHANGE_RISK);
        Outcome cfgO = join(cfgF, Dimension.CONFIGURATION);
        Outcome traO = join(traF, Dimension.OBSERVABILITY);

        // If every dimension failed at the backend, don't fabricate a "GO" from zero findings —
        // report the failure honestly (mapped to HTTP 502).
        if (chrO.failed() && cfgO.failed() && traO.failed()) {
            throw new ModelBackendException(
                    "All specialist analyses failed to reach the model backend. First error: " + chrO.failure());
        }

        DimensionAnalysis chr = chrO.analysis();
        DimensionAnalysis cfg = cfgO.analysis();
        DimensionAnalysis tra = traO.analysis();

        Synthesis synthesis = synthesize(a.synthesizer(), pr, chr, cfg, tra);

        long duration = System.currentTimeMillis() - start;
        List<Finding> prioritized = prioritize(synthesis.prioritizedFindings());

        List<DimensionResult> dimensions = List.of(
                new DimensionResult(Dimension.CHANGE_RISK, chr.summary(), safe(chr.findings())),
                new DimensionResult(Dimension.CONFIGURATION, cfg.summary(), safe(cfg.findings())),
                new DimensionResult(Dimension.OBSERVABILITY, tra.summary(), safe(tra.findings())));

        String modelId = agents.modelId(model);
        Map<String, String> agentModels = Map.of(
                "risk", modelId, "configuration", modelId, "observability", modelId, "synthesizer", modelId);
        var meta = new ReviewResponse.ReviewMeta(
                agentModels, pr.changedFiles(), pr.diffTruncated(), duration);

        return new ReviewResponse(
                prUrl,
                pr.repository(),
                pr.number(),
                pr.title(),
                synthesis.verdict(),
                synthesis.executiveSummary(),
                prioritized.size(),
                severityCounts(prioritized),
                dimensions,
                prioritized,
                meta,
                Instant.now());
    }

    /** Fetch the PR and run a single change-risk (CHR) specialist. */
    public DimensionAnalysis analyzeChangeRisk(String prUrl, ModelChoice model) {
        PullRequest pr = fetchForAnalysis(prUrl);
        return callSpecialist(Dimension.CHANGE_RISK,
                () -> analysis(agents.forChoice(model).risk()
                        .analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));
    }

    /** Fetch the PR and run a single configuration (CFG) specialist. */
    public DimensionAnalysis analyzeConfiguration(String prUrl, ModelChoice model) {
        PullRequest pr = fetchForAnalysis(prUrl);
        return callSpecialist(Dimension.CONFIGURATION,
                () -> analysis(agents.forChoice(model).configuration()
                        .analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));
    }

    /** Fetch the PR and run a single observability (ORA) specialist. */
    public DimensionAnalysis analyzeObservability(String prUrl, ModelChoice model) {
        PullRequest pr = fetchForAnalysis(prUrl);
        return callSpecialist(Dimension.OBSERVABILITY,
                () -> analysis(agents.forChoice(model).observability()
                        .analyze(pr.repository(), pr.number(), pr.title(), pr.diff())));
    }

    /** Run the synthesizer on its own over caller-supplied specialist analyses. */
    public Synthesis synthesizeOnly(com.github.evp2.langchain_api.model.SynthesisRequest request, ModelChoice model) {
        DimensionAnalysis chr = orEmpty(request.changeRisk());
        DimensionAnalysis cfg = orEmpty(request.configuration());
        DimensionAnalysis tra = orEmpty(request.observability());
        try {
            return parser.parse(agents.forChoice(model).synthesizer().synthesize(
                    request.repository(), request.number(), request.title(),
                    toJson(chr), toJson(cfg), toJson(tra)), Synthesis.class);
        } catch (Exception e) {
            throw new ModelBackendException("Synthesizer failed: " + rootMessage(e));
        }
    }

    /** Parse a specialist's raw JSON text into a normalized analysis (never-null findings). */
    private DimensionAnalysis analysis(String rawJson) {
        DimensionAnalysis parsed = parser.parse(rawJson, DimensionAnalysis.class);
        return new DimensionAnalysis(parsed.summary(), safe(parsed.findings()));
    }

    /** Send a raw prompt straight to the selected model and return its text response. */
    public String prompt(String prompt, ModelChoice model) {
        try {
            return agents.chatModel(model).chat(prompt);
        } catch (Exception e) {
            throw new ModelBackendException("Model call failed: " + rootMessage(e));
        }
    }

    private PullRequest fetchForAnalysis(String prUrl) {
        PullRequest pr = gitHub.fetch(prUrl);
        if (pr.diff() == null || pr.diff().isBlank()) {
            throw new IllegalStateException("Pull request diff is empty — nothing to review.");
        }
        return pr;
    }

    private DimensionAnalysis callSpecialist(Dimension dim, Supplier<DimensionAnalysis> call) {
        log.info("Running {} specialist (single-agent endpoint)", dim);
        try {
            DimensionAnalysis result = call.get();
            log.info("{} specialist produced {} findings", dim,
                    result.findings() == null ? 0 : result.findings().size());
            return new DimensionAnalysis(result.summary(), safe(result.findings()));
        } catch (Exception e) {
            throw new ModelBackendException(dim + " analysis failed: " + rootMessage(e));
        }
    }

    private static DimensionAnalysis orEmpty(DimensionAnalysis d) {
        if (d == null) {
            return new DimensionAnalysis("(not provided)", List.of());
        }
        return new DimensionAnalysis(d.summary(), safe(d.findings()));
    }

    /** A specialist result plus whether it failed at the backend (failure == null means success). */
    private record Outcome(DimensionAnalysis analysis, String failure) {
        boolean failed() {
            return failure != null;
        }
    }

    private CompletableFuture<DimensionAnalysis> runSpecialist(Dimension dim, Supplier<DimensionAnalysis> call) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Running {} specialist", dim);
            DimensionAnalysis result = call.get();
            log.info("{} specialist produced {} findings", dim,
                    result.findings() == null ? 0 : result.findings().size());
            return result;
        }, reviewExecutor);
    }

    private Outcome join(CompletableFuture<DimensionAnalysis> f, Dimension dim) {
        try {
            return new Outcome(f.get(timeoutSeconds, TimeUnit.SECONDS), null);
        } catch (TimeoutException e) {
            log.warn("{} specialist timed out after {}s", dim, timeoutSeconds);
            String msg = "did not complete within " + timeoutSeconds + "s";
            return new Outcome(new DimensionAnalysis(dim + " analysis " + msg + ".", List.of()), msg);
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("{} specialist failed: {}", dim, msg);
            return new Outcome(new DimensionAnalysis(dim + " analysis failed: " + msg + ".", List.of()), msg);
        }
    }

    private Synthesis synthesize(Synthesizer synthesizer, PullRequest pr,
            DimensionAnalysis chr, DimensionAnalysis cfg, DimensionAnalysis tra) {
        try {
            return parser.parse(synthesizer.synthesize(
                    pr.repository(), pr.number(), pr.title(),
                    toJson(chr), toJson(cfg), toJson(tra)), Synthesis.class);
        } catch (Exception e) {
            log.warn("Synthesizer failed, falling back to a merged verdict: {}", e.getMessage());
            return fallbackSynthesis(chr, cfg, tra);
        }
    }

    /** If the synthesizer model call fails, derive a defensible verdict from raw findings. */
    private Synthesis fallbackSynthesis(DimensionAnalysis chr, DimensionAnalysis cfg, DimensionAnalysis tra) {
        List<Finding> all = prioritize(java.util.stream.Stream.of(chr, cfg, tra)
                .flatMap(d -> safe(d.findings()).stream())
                .toList());
        var counts = severityCounts(all);
        com.github.evp2.langchain_api.model.Verdict verdict;
        if (counts.getOrDefault(Severity.CRITICAL, 0) > 0) {
            verdict = com.github.evp2.langchain_api.model.Verdict.NO_GO;
        } else if (counts.getOrDefault(Severity.HIGH, 0) > 0 || counts.getOrDefault(Severity.MEDIUM, 0) > 0) {
            verdict = com.github.evp2.langchain_api.model.Verdict.CONDITIONAL;
        } else {
            verdict = com.github.evp2.langchain_api.model.Verdict.GO;
        }
        return new Synthesis(verdict,
                "Synthesizer unavailable; verdict derived directly from specialist findings ("
                        + all.size() + " total).",
                all);
    }

    private String toJson(DimensionAnalysis d) {
        try {
            return mapper.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            return "{\"summary\":\"(serialization failed)\",\"findings\":[]}";
        }
    }

    private static List<Finding> prioritize(List<Finding> findings) {
        return safe(findings).stream()
                .sorted(Comparator.comparingInt(f -> severityRank(f.severity())))
                .toList();
    }

    private static int severityRank(Severity s) {
        return s == null ? Integer.MAX_VALUE : s.ordinal();
    }

    private static Map<Severity, Integer> severityCounts(List<Finding> findings) {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            counts.put(s, 0);
        }
        for (Finding f : safe(findings)) {
            if (f.severity() != null) {
                counts.merge(f.severity(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static List<Finding> safe(List<Finding> findings) {
        return findings == null ? List.of() : findings;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
