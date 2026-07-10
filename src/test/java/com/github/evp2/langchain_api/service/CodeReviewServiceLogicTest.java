package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.Finding;
import com.github.evp2.langchain_api.model.Severity;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure-logic coverage of CodeReviewService's prioritization, counting, and fallback-verdict helpers. */
class CodeReviewServiceLogicTest {

    private static Finding finding(String id, Severity severity) {
        return new Finding(id, severity, "t", "d", "f", "r");
    }

    @Test
    void prioritizeOrdersBySeverityWithNullLast() {
        List<Finding> sorted = CodeReviewService.prioritize(List.of(
                finding("a", Severity.LOW),
                finding("b", null),
                finding("c", Severity.CRITICAL),
                finding("d", Severity.MEDIUM),
                finding("e", Severity.HIGH)));
        assertThat(sorted).extracting(Finding::id).containsExactly("c", "e", "d", "a", "b");
    }

    @Test
    void prioritizeToleratesNullList() {
        assertThat(CodeReviewService.prioritize(null)).isEmpty();
    }

    @Test
    void severityRankPutsNullAfterEverything() {
        assertThat(CodeReviewService.severityRank(null)).isGreaterThan(
                CodeReviewService.severityRank(Severity.LOW));
    }

    @Test
    void severityCountsSeedsEverySeverityWithZero() {
        Map<Severity, Integer> counts = CodeReviewService.severityCounts(List.of());
        assertThat(counts).containsOnlyKeys(Severity.values());
        assertThat(counts.values()).allMatch(v -> v == 0);
    }

    @Test
    void severityCountsTalliesAndSkipsNullSeverity() {
        Map<Severity, Integer> counts = CodeReviewService.severityCounts(List.of(
                finding("a", Severity.HIGH),
                finding("b", Severity.HIGH),
                finding("c", null)));
        assertThat(counts.get(Severity.HIGH)).isEqualTo(2);
        assertThat(counts.get(Severity.CRITICAL)).isZero();
    }

    @Test
    void fallbackSynthesisIsNoGoOnAnyCritical() {
        Synthesis s = CodeReviewService.fallbackSynthesis(
                new DimensionAnalysis("chr", List.of(finding("a", Severity.CRITICAL))),
                new DimensionAnalysis("cfg", List.of()),
                new DimensionAnalysis("ora", List.of(finding("b", Severity.LOW))));
        assertThat(s.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(s.executiveSummary()).contains("Synthesizer unavailable");
        assertThat(s.prioritizedFindings()).extracting(Finding::id).containsExactly("a", "b");
    }

    @Test
    void fallbackSynthesisIsConditionalOnHighOrMedium() {
        Synthesis high = CodeReviewService.fallbackSynthesis(
                new DimensionAnalysis("chr", List.of(finding("a", Severity.HIGH))),
                new DimensionAnalysis("cfg", List.of()),
                new DimensionAnalysis("ora", List.of()));
        Synthesis medium = CodeReviewService.fallbackSynthesis(
                new DimensionAnalysis("chr", List.of()),
                new DimensionAnalysis("cfg", List.of(finding("b", Severity.MEDIUM))),
                new DimensionAnalysis("ora", List.of()));
        assertThat(high.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(medium.verdict()).isEqualTo(Verdict.CONDITIONAL);
    }

    @Test
    void fallbackSynthesisIsGoOnLowOrNoFindings() {
        Synthesis low = CodeReviewService.fallbackSynthesis(
                new DimensionAnalysis("chr", List.of(finding("a", Severity.LOW))),
                new DimensionAnalysis("cfg", null),
                new DimensionAnalysis("ora", List.of()));
        assertThat(low.verdict()).isEqualTo(Verdict.GO);
    }

    @Test
    void rootMessageUnwrapsCauseChain() {
        Exception e = new RuntimeException("outer",
                new IllegalStateException("middle", new java.io.IOException("root cause")));
        assertThat(CodeReviewService.rootMessage(e)).isEqualTo("root cause");
    }

    @Test
    void rootMessageFallsBackToClassName() {
        assertThat(CodeReviewService.rootMessage(new NullPointerException()))
                .isEqualTo("NullPointerException");
    }
}
