package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.Finding;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.Verdict;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the parse/verdict pipeline against realistic model outputs recorded under
 * src/test/resources/recorded/. The current files are hand-seeded in the exact shape the
 * prompts demand; replace or extend them with real captured responses (e.g. from the
 * /api/v1/agents endpoints) as they become available — the contract asserted here stays the same.
 */
class RecordedModelOutputContractTest {

    private final JsonResponseParser parser = JsonResponseParser.create();

    private static String recorded(String name) {
        try (var in = RecordedModelOutputContractTest.class.getResourceAsStream("/recorded/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertFindingContract(Finding f, String idPrefix) {
        assertThat(f.id()).startsWith(idPrefix);
        assertThat(f.severity()).isNotNull();
        assertThat(f.title()).isNotBlank();
        assertThat(f.description()).isNotBlank();
        assertThat(f.file()).isNotBlank();
        assertThat(f.recommendation()).isNotBlank();
    }

    @Test
    void fencedChangeRiskResponseParses() {
        DimensionAnalysis a = parser.parse(recorded("chr-fenced.txt"), DimensionAnalysis.class);
        assertThat(a.summary()).isNotBlank();
        assertThat(a.findings()).isNotEmpty();
        a.findings().forEach(f -> assertFindingContract(f, "CHR-"));
    }

    @Test
    void plainConfigurationResponseParses() {
        DimensionAnalysis a = parser.parse(recorded("cfg-plain.txt"), DimensionAnalysis.class);
        assertThat(a.findings()).isNotEmpty();
        a.findings().forEach(f -> assertFindingContract(f, "CFG-"));
    }

    @Test
    void emptyFindingsResponseParses() {
        DimensionAnalysis a = parser.parse(recorded("ora-empty.txt"), DimensionAnalysis.class);
        assertThat(a.summary()).isNotBlank();
        assertThat(a.findings()).isEmpty();
    }

    @Test
    void synthesizerResponseParsesWithVerdict() {
        Synthesis s = parser.parse(recorded("synth-nogo.txt"), Synthesis.class);
        assertThat(s.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(s.executiveSummary()).isNotBlank();
        assertThat(s.prioritizedFindings()).isNotEmpty();
    }

    @Test
    void recordedAnalysesDriveTheFallbackVerdict() {
        DimensionAnalysis chr = parser.parse(recorded("chr-fenced.txt"), DimensionAnalysis.class);
        DimensionAnalysis cfg = parser.parse(recorded("cfg-plain.txt"), DimensionAnalysis.class);
        DimensionAnalysis ora = parser.parse(recorded("ora-empty.txt"), DimensionAnalysis.class);

        // cfg carries CRITICAL findings, so a synthesizer outage must still yield NO_GO.
        Synthesis fallback = CodeReviewService.fallbackSynthesis(chr, cfg, ora);
        assertThat(fallback.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(fallback.prioritizedFindings())
                .extracting(Finding::severity)
                .isSorted();
    }

    @Test
    void fallbackWithOnlyLowFindingsIsGo() {
        DimensionAnalysis ora = parser.parse(recorded("ora-empty.txt"), DimensionAnalysis.class);
        Synthesis fallback = CodeReviewService.fallbackSynthesis(
                new DimensionAnalysis("clean", List.of()), ora, ora);
        assertThat(fallback.verdict()).isEqualTo(Verdict.GO);
    }
}
