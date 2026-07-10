package com.github.evp2.langchain_api.service;

/** Thrown when the requested pull request cannot be found or accessed on GitHub. */
public class PullRequestNotFoundException extends RuntimeException {
    public PullRequestNotFoundException(String message) {
        super(message);
    }
}
