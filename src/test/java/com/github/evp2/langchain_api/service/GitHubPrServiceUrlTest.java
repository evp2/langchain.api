package com.github.evp2.langchain_api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** URL validation only — the happy fetch path (and truncation) is covered by GitHubPrServiceHttpTest. */
class GitHubPrServiceUrlTest {

    private final GitHubPrService service =
            new GitHubPrService(1000, "http://localhost:0", null, null);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "not a url",
            "https://github.com/owner/repo",                 // no /pull segment
            "https://github.com/owner/repo/issues/12",       // issue, not PR
            "https://github.com/owner/repo/pull/",           // missing number
            "https://gitlab.com/owner/repo/pull/12"          // wrong host
    })
    void rejectsInvalidPrUrls(String url) {
        assertThatThrownBy(() -> service.fetch(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a valid GitHub PR URL");
    }
}
