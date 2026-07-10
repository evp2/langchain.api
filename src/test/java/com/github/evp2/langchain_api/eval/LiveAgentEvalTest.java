package com.github.evp2.langchain_api.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.evp2.langchain_api.config.AgentRegistry;
import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.Finding;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.Severity;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

/**
 * Live prompt-quality evaluation against the real Bedrock backend. Excluded from the default
 * build (tag "eval"); run with:
 *
 * <pre>./gradlew test -PincludeEvals --tests '*LiveAgentEvalTest' [-Deval.model=NVIDIA_NEMOTRON]</pre>
 *
 * <p>Bedrock config (inference-profile ARNs, token budgets) comes from the "eval" Spring profile
 * in {@code src/test/resources/application-eval.yml}; edit that file to retune. AWS credentials
 * still come from the default credential chain.
 *
 * <p>For each golden diff in evals/fixtures/diffs/ the labeled specialist agents are invoked
 * {@link #REPEATS} times through the same @AiService interfaces production uses (real prompts,
 * real parsing), and each run is scored against the metric ladder: JSON validity, schema
 * conformance, finding-id prefix, and severity calibration vs evals/labels.yaml. A case passes
 * when at least {@link #PASS_THRESHOLD} of the repeats pass — Bedrock output varies run to run
 * (temperature is not even sent for Claude Sonnet), so single-shot assertions would flake.
 *
 * <p>A per-run summary lands in build/eval-results/&lt;git-sha&gt;-&lt;model&gt;.json so prompt
 * iterations can be compared commit over commit.
 */
@Tag("eval")
@SpringBootTest
@ActiveProfiles("eval")
class LiveAgentEvalTest {

    private static final Logger log = LoggerFactory.getLogger(LiveAgentEvalTest.class);

    private static final int REPEATS = 3;
    private static final int PASS_THRESHOLD = 2;
    private static final Path FIXTURES = Path.of("evals/fixtures");
    private static final Path LABELS = Path.of("evals/labels.yaml");
    private static final Pattern FENCE =
            Pattern.compile("(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Model backend and Bedrock config come from the "eval" Spring profile / application-eval.yml.
    @Autowired
    private AgentRegistry registry;

    private static ModelChoice model;
    private static Map<String, Object> labels;
    private static final List<Map<String, Object>> results = new ArrayList<>();

    @BeforeAll
    static void setUp() throws IOException {
        model = ModelChoice.valueOf(System.getProperty("eval.model", "CLAUDE_SONNET"));
        labels = new Yaml().load(Files.readString(LABELS));
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> specialistEvals() {
        Map<String, Map<String, Object>> diffs = (Map<String, Map<String, Object>>) labels.get("diffs");
        List<DynamicTest> tests = new ArrayList<>();
        diffs.forEach((fixture, spec) -> {
            String title = (String) spec.get("title");
            for (String dimension : List.of("change-risk", "configuration", "observability")) {
                Map<String, Object> expect = (Map<String, Object>) spec.get(dimension);
                if (expect != null) {
                    tests.add(DynamicTest.dynamicTest(fixture + " / " + dimension,
                            () -> runSpecialistCase(fixture, title, dimension, expect)));
                }
            }
        });
        return tests;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> synthesizerEvals() {
        Map<String, Map<String, Object>> cases = (Map<String, Map<String, Object>>) labels.get("synthesis");
        return cases.entrySet().stream()
                .map(e -> DynamicTest.dynamicTest("synthesizer / " + e.getKey(),
                        () -> runSynthesizerCase(e.getKey(),
                                (List<String>) e.getValue().get("allowed-verdicts"))))
                .toList();
    }

    private void runSpecialistCase(String fixture, String title, String dimension,
            Map<String, Object> expect) throws IOException {
        String diff = Files.readString(FIXTURES.resolve("diffs").resolve(fixture + ".diff"));
        List<String> failures = new ArrayList<>();
        int passes = 0;
        for (int i = 0; i < REPEATS; i++) {
            String failure = scoreSpecialistRun(fixture, title, dimension, diff, expect);
            if (failure == null) {
                passes++;
            } else {
                failures.add("run " + (i + 1) + ": " + failure);
            }
        }
        record(fixture + "/" + dimension, passes, failures);
        assertThat(passes)
                .as("%s/%s passed %d/%d runs; failures: %s", fixture, dimension, passes, REPEATS, failures)
                .isGreaterThanOrEqualTo(PASS_THRESHOLD);
    }

    /** One agent invocation scored against the metric ladder. Returns null on pass, else the reason. */
    private String scoreSpecialistRun(String fixture, String title, String dimension, String diff,
            Map<String, Object> expect) {
        AgentRegistry.Agents agents = registry.forChoice(model);
        String raw;
        try {
            raw = switch (dimension) {
                case "change-risk" -> agents.risk().analyze("eval/" + fixture, 1, title, diff);
                case "configuration" -> agents.configuration().analyze("eval/" + fixture, 1, title, diff);
                case "observability" -> agents.observability().analyze("eval/" + fixture, 1, title, diff);
                default -> throw new IllegalArgumentException(dimension);
            };
        } catch (Exception e) {
            return "model call failed: " + e.getMessage();
        }

        // 1. JSON validity (after fence strip) + schema conformance via the production record types.
        DimensionAnalysis analysis;
        try {
            analysis = MAPPER.readValue(stripFence(raw), DimensionAnalysis.class);
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
        if (analysis.summary() == null || analysis.summary().isBlank()) {
            return "blank summary";
        }
        List<Finding> findings = analysis.findings() == null ? List.of() : analysis.findings();

        // 2. Field conformance + id prefix.
        String prefix = switch (dimension) {
            case "change-risk" -> "CHR-";
            case "configuration" -> "CFG-";
            default -> "ORA-";
        };
        for (Finding f : findings) {
            if (f.id() == null || !f.id().startsWith(prefix)) {
                return "finding id '" + f.id() + "' missing prefix " + prefix;
            }
            if (f.severity() == null) {
                return "finding " + f.id() + " has no severity";
            }
            if (isBlank(f.title()) || isBlank(f.description()) || isBlank(f.recommendation())) {
                return "finding " + f.id() + " has blank title/description/recommendation";
            }
        }

        // 3. Severity calibration vs labels.
        Integer minFindings = (Integer) expect.get("min-findings");
        if (minFindings != null && findings.size() < minFindings) {
            return "expected >= " + minFindings + " findings, got " + findings.size();
        }
        Integer maxFindings = (Integer) expect.get("max-findings");
        if (maxFindings != null && findings.size() > maxFindings) {
            return "expected <= " + maxFindings + " findings, got " + findings.size()
                    + " (over-flagging): " + findings.stream().map(Finding::id).toList();
        }
        String atLeast = (String) expect.get("severity-at-least");
        if (atLeast != null) {
            Severity bound = Severity.valueOf(atLeast);
            boolean met = findings.stream().anyMatch(f -> f.severity().ordinal() <= bound.ordinal());
            if (!met) {
                return "no finding at least " + bound + "; worst was "
                        + findings.stream().map(Finding::severity).sorted().findFirst().orElse(null);
            }
        }
        String atMost = (String) expect.get("severity-at-most");
        if (atMost != null) {
            Severity bound = Severity.valueOf(atMost);
            var tooSevere = findings.stream()
                    .filter(f -> f.severity().ordinal() < bound.ordinal())
                    .map(Finding::id)
                    .toList();
            if (!tooSevere.isEmpty()) {
                return "findings above " + bound + ": " + tooSevere;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void runSynthesizerCase(String fixture, List<String> allowedVerdicts) throws IOException {
        Map<String, Object> input = MAPPER.readValue(
                Files.readString(FIXTURES.resolve("synth-inputs").resolve(fixture + ".json")), Map.class);
        String chr = MAPPER.writeValueAsString(input.get("changeRisk"));
        String cfg = MAPPER.writeValueAsString(input.get("configuration"));
        String ora = MAPPER.writeValueAsString(input.get("observability"));
        List<Verdict> allowed = allowedVerdicts.stream().map(Verdict::valueOf).toList();

        List<String> failures = new ArrayList<>();
        int passes = 0;
        for (int i = 0; i < REPEATS; i++) {
            String failure;
            try {
                String raw = registry.forChoice(model).synthesizer().synthesize(
                        (String) input.get("repository"),
                        (Integer) input.get("number"),
                        (String) input.get("title"),
                        chr, cfg, ora);
                Synthesis s = MAPPER.readValue(stripFence(raw), Synthesis.class);
                if (s.verdict() == null) {
                    failure = "missing verdict";
                } else if (!allowed.contains(s.verdict())) {
                    failure = "verdict " + s.verdict() + " not in " + allowed;
                } else if (isBlank(s.executiveSummary())) {
                    failure = "blank executive summary";
                } else {
                    failure = null;
                }
            } catch (Exception e) {
                failure = "call/parse failed: " + e.getMessage();
            }
            if (failure == null) {
                passes++;
            } else {
                failures.add("run " + (i + 1) + ": " + failure);
            }
        }
        record("synthesizer/" + fixture, passes, failures);
        assertThat(passes)
                .as("synthesizer/%s passed %d/%d runs; failures: %s", fixture, passes, REPEATS, failures)
                .isGreaterThanOrEqualTo(PASS_THRESHOLD);
    }

    private static synchronized void record(String caseId, int passes, List<String> failures) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case", caseId);
        row.put("passes", passes);
        row.put("repeats", REPEATS);
        row.put("failures", failures);
        results.add(row);
        log.info("eval {} — {}/{} passed {}", caseId, passes, REPEATS, failures.isEmpty() ? "" : failures);
    }

    @AfterAll
    static void writeSummary() throws IOException, InterruptedException {
        if (results.isEmpty()) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gitSha", gitSha());
        summary.put("model", model.name());
        summary.put("timestamp", java.time.Instant.now().toString());
        summary.put("cases", results);
        Path out = Path.of("build/eval-results", gitSha() + "-" + model + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        log.info("Eval summary written to {}", out.toAbsolutePath());
    }

    private static String gitSha() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
        String sha = new String(p.getInputStream().readAllBytes()).trim();
        return (p.waitFor() == 0 && !sha.isBlank()) ? sha : "local";
    }

    private static String stripFence(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = FENCE.matcher(s.trim());
        return m.matches() ? m.group(1).trim() : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
