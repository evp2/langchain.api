package com.github.evp2.langchain_api.service;

/**
 * A fetched pull request: metadata plus its unified diff.
 *
 * @param owner        repository owner
 * @param repo         repository name
 * @param number       PR number
 * @param title        PR title
 * @param diff         unified diff text (already size-capped)
 * @param changedFiles number of changed files reported by GitHub
 * @param diffTruncated whether {@link #diff} was truncated to the configured cap
 */
public record PullRequest(
        String owner,
        String repo,
        int number,
        String title,
        String diff,
        int changedFiles,
        boolean diffTruncated) {

    /** "owner/repo". */
    public String repository() {
        return owner + "/" + repo;
    }
}
