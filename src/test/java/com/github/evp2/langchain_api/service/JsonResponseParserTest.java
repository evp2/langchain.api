package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.Severity;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.Verdict;
import org.junit.jupiter.api.Test;

class JsonResponseParserTest {

    private final JsonResponseParser parser = JsonResponseParser.create();

    @Test
    void stripsJsonFence() {
        assertThat(JsonResponseParser.stripFence("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsPlainFence() {
        assertThat(JsonResponseParser.stripFence("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsFenceCaseInsensitivelyAndTrims() {
        assertThat(JsonResponseParser.stripFence("  ```JSON\n {\"a\":1} \n```  ")).isEqualTo("{\"a\":1}");
    }

    @Test
    void leavesUnfencedInputTrimmed() {
        assertThat(JsonResponseParser.stripFence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
    }

    @Test
    void nullBecomesEmptyString() {
        assertThat(JsonResponseParser.stripFence(null)).isEmpty();
    }

    @Test
    void parsesDimensionAnalysis() {
        String raw = """
                {"summary":"ok","findings":[
                  {"id":"CHR-001","severity":"HIGH","title":"t","description":"d","file":"f","recommendation":"r"}
                ]}""";
        DimensionAnalysis a = parser.parse(raw, DimensionAnalysis.class);
        assertThat(a.summary()).isEqualTo("ok");
        assertThat(a.findings()).hasSize(1);
        assertThat(a.findings().getFirst().severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void parsesFencedSynthesis() {
        String raw = """
                ```json
                {"verdict":"NO_GO","executiveSummary":"bad","prioritizedFindings":[]}
                ```""";
        Synthesis s = parser.parse(raw, Synthesis.class);
        assertThat(s.verdict()).isEqualTo(Verdict.NO_GO);
        assertThat(s.prioritizedFindings()).isEmpty();
    }

    @Test
    void ignoresUnknownProperties() {
        String raw = "{\"summary\":\"ok\",\"findings\":[],\"confidence\":0.9}";
        assertThat(parser.parse(raw, DimensionAnalysis.class).summary()).isEqualTo("ok");
    }

    @Test
    void nullFindingsListStaysNull() {
        DimensionAnalysis a = parser.parse("{\"summary\":\"ok\"}", DimensionAnalysis.class);
        assertThat(a.findings()).isNull();
    }

    @Test
    void malformedJsonThrowsIllegalState() {
        assertThatThrownBy(() -> parser.parse("not json at all", DimensionAnalysis.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DimensionAnalysis");
    }

    @Test
    void unknownSeverityThrows() {
        // Jackson rejects enum values outside the vocabulary rather than nulling them out —
        // an off-vocabulary severity fails the whole parse (surfaced as a degraded dimension).
        String raw = """
                {"summary":"s","findings":[
                  {"id":"CHR-001","severity":"BANANAS","title":"t","description":"d","file":"f","recommendation":"r"}
                ]}""";
        assertThatThrownBy(() -> parser.parse(raw, DimensionAnalysis.class))
                .isInstanceOf(IllegalStateException.class);
    }
}
