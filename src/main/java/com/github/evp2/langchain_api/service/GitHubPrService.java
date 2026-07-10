package com.github.evp2.langchain_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fetches a pull request's metadata and unified diff from the GitHub REST API.
 * A {@code GITHUB_TOKEN} / {@code GH_TOKEN} environment variable is used when present
 * (required for private repos and to lift rate limits).
 */
@Service
public class GitHubPrService {

    private static final Logger log = LoggerFactory.getLogger(GitHubPrService.class);

    // https://github.com/{owner}/{repo}/pull/{number}
    private static final Pattern PR_URL = Pattern.compile(
            "github\\.com/([^/\\s]+)/([^/\\s]+)/pull/(\\d+)");

    private final HttpClient http = buildClient();
    // Jackson 2 (bundled via langchain4j) — Spring Boot 4's managed ObjectMapper is Jackson 3.
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxDiffChars;
    private final String token;

    public GitHubPrService(@Value("${review.max-diff-chars}") int maxDiffChars) {
        this.maxDiffChars = maxDiffChars;
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.isBlank()) {
            t = System.getenv("GH_TOKEN");
        }
        this.token = (t == null || t.isBlank()) ? null : t.trim();
    }

    /**
     * GitHub is external, so on a corporate network it is only reachable through the egress proxy.
     * Bedrock (*.amazonaws.com) is in NO_PROXY and must stay direct, so the proxy is scoped to this
     * client rather than set as a JVM-wide default. Honors the standard HTTPS_PROXY / https_proxy env var.
     */
    private static HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = System.getenv("HTTPS_PROXY");
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("https_proxy");
        }
        if (proxy != null && !proxy.isBlank()) {
            URI u = URI.create(proxy.trim());
            int port = u.getPort() != -1 ? u.getPort() : 443;
            b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), port)));
            log.info("GitHub client using HTTPS proxy {}:{}", u.getHost(), port);
        }
        return b.build();
    }

    /** Parse the PR URL, then fetch its metadata and diff. */
    public PullRequest fetch(String prUrl) {
        Matcher m = PR_URL.matcher(prUrl == null ? "" : prUrl);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Not a valid GitHub PR URL. Expected https://github.com/{owner}/{repo}/pull/{number}");
        }
        String owner = m.group(1);
        String repo = m.group(2);
        int number = Integer.parseInt(m.group(3));

        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + number;

        JsonNode meta = getJson(apiUrl, owner, repo, number);
        String title = meta.path("title").asText("(untitled)");
        int changedFiles = meta.path("changed_files").asInt(0);

        String rawDiff = getDiff(apiUrl, owner, repo, number);
        boolean truncated = rawDiff.length() > maxDiffChars;
        String diff = truncated
                ? rawDiff.substring(0, maxDiffChars) + "\n\n... [diff truncated at " + maxDiffChars + " chars] ..."
                : rawDiff;

        log.info("Fetched {}/{}#{} '{}' — {} changed files, {} diff chars{}",
                owner, repo, number, title, changedFiles, diff.length(), truncated ? " (truncated)" : "");

        return new PullRequest(owner, repo, number, title, diff, changedFiles, truncated);
    }

    private JsonNode getJson(String apiUrl, String owner, String repo, int number) {
        HttpResponse<String> resp = send(apiUrl, "application/vnd.github+json", owner, repo, number);
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new PullRequestNotFoundException(
                    "Could not parse GitHub response for " + owner + "/" + repo + "#" + number);
        }
    }

    private String getDiff(String apiUrl, String owner, String repo, int number) {
        return send(apiUrl, "application/vnd.github.v3.diff", owner, repo, number).body();
    }

    private HttpResponse<String> send(String url, String accept, String owner, String repo, int number) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "langchain-api-radar-review")
                .GET();
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PullRequestNotFoundException("Network error contacting GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PullRequestNotFoundException("Interrupted while contacting GitHub");
        }

        int sc = resp.statusCode();
        if (sc == 404) {
            throw new PullRequestNotFoundException(
                    "GitHub returned 404 for " + owner + "/" + repo + "#" + number
                            + " (private repo without a token, or the PR does not exist).");
        }
        if (sc == 403 || sc == 429) {
            throw new PullRequestNotFoundException(
                    "GitHub rate limit or access denied (HTTP " + sc + "). Set GITHUB_TOKEN to raise limits.");
        }
        if (sc < 200 || sc >= 300) {
            throw new PullRequestNotFoundException(
                    "GitHub request failed with HTTP " + sc + " for " + owner + "/" + repo + "#" + number);
        }
        return resp;
    }
}
