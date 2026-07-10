package com.github.evp2.langchain_api.service;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** HTTP behavior of the GitHub fetch against a local WireMock server. */
class GitHubPrServiceHttpTest {

    private static final String PR_PATH = "/repos/o/r/pulls/1";
    private static final String PR_URL = "https://github.com/o/r/pull/1";

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().build();

    private GitHubPrService service(int maxDiffChars, String token) {
        return new GitHubPrService(
                maxDiffChars, wiremock.baseUrl(), HttpClient.newHttpClient(), token);
    }

    private void stubPr(String diff) {
        wiremock.stubFor(get(urlEqualTo(PR_PATH))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"title\":\"My PR\",\"changed_files\":3}")));
        wiremock.stubFor(get(urlEqualTo(PR_PATH))
                .withHeader("Accept", equalTo("application/vnd.github.v3.diff"))
                .willReturn(aResponse().withStatus(200).withBody(diff)));
    }

    @Test
    void fetchesMetadataAndDiff() {
        stubPr("diff --git a/A.java b/A.java");

        PullRequest pr = service(64000, "tok-123").fetch(PR_URL);

        assertThat(pr.repository()).isEqualTo("o/r");
        assertThat(pr.number()).isEqualTo(1);
        assertThat(pr.title()).isEqualTo("My PR");
        assertThat(pr.changedFiles()).isEqualTo(3);
        assertThat(pr.diff()).startsWith("diff --git");
        assertThat(pr.diffTruncated()).isFalse();

        wiremock.verify(2, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(PR_PATH))
                .withHeader("Authorization", equalTo("Bearer tok-123"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    void omitsAuthorizationHeaderWithoutToken() {
        stubPr("diff --git a/A.java b/A.java");

        service(64000, null).fetch(PR_URL);

        wiremock.verify(2, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(PR_PATH))
                .withHeader("Authorization", absent()));
    }

    @Test
    void truncatesOversizedDiffAndFlagsIt() {
        stubPr("x".repeat(150));

        PullRequest pr = service(100, null).fetch(PR_URL);

        assertThat(pr.diffTruncated()).isTrue();
        assertThat(pr.diff()).startsWith("x".repeat(100))
                .contains("[diff truncated at 100 chars]");
    }

    @Test
    void diffExactlyAtLimitIsNotTruncated() {
        stubPr("x".repeat(100));
        PullRequest pr = service(100, null).fetch(PR_URL);
        assertThat(pr.diffTruncated()).isFalse();
        assertThat(pr.diff()).hasSize(100);
    }

    @Test
    void notFoundMapsToPullRequestNotFound() {
        wiremock.stubFor(get(urlEqualTo(PR_PATH)).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> service(64000, null).fetch(PR_URL))
                .isInstanceOf(PullRequestNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void rateLimitMentionsToken() {
        wiremock.stubFor(get(urlEqualTo(PR_PATH)).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> service(64000, null).fetch(PR_URL))
                .isInstanceOf(PullRequestNotFoundException.class)
                .hasMessageContaining("GITHUB_TOKEN");
    }

    @Test
    void serverErrorMapsToPullRequestNotFound() {
        wiremock.stubFor(get(urlEqualTo(PR_PATH)).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> service(64000, null).fetch(PR_URL))
                .isInstanceOf(PullRequestNotFoundException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void connectionFailureMapsToPullRequestNotFound() {
        GitHubPrService unreachable = new GitHubPrService(
                64000, "http://127.0.0.1:1", HttpClient.newHttpClient(), null);
        assertThatThrownBy(() -> unreachable.fetch(PR_URL))
                .isInstanceOf(PullRequestNotFoundException.class)
                .hasMessageContaining("Network error");
    }
}
