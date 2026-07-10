package com.github.evp2.langchain_api.service;

/** Thrown when the model/inference backend (AWS Bedrock) cannot complete the review. */
public class ModelBackendException extends RuntimeException {
    public ModelBackendException(String message) {
        super(message);
    }
}
