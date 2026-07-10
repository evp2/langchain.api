package com.github.evp2.langchain_api.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses model responses into typed objects, tolerating the markdown code fences LLMs sometimes
 * wrap JSON in (e.g. ```json ... ```). langchain4j's built-in auto-mapping does not strip these
 * reliably, so a stray fence would otherwise drop a whole specialist's analysis.
 */
final class JsonResponseParser {

    // A leading ```json / ``` fence and its closing ``` — captured so we can pull out the body.
    private static final Pattern FENCE = Pattern.compile(
            "(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonResponseParser() {
    }

    static JsonResponseParser create() {
        return new JsonResponseParser();
    }

    <T> T parse(String raw, Class<T> type) {
        try {
            return mapper.readValue(stripFence(raw), type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Model returned unparseable " + type.getSimpleName() + " JSON: " + e.getMessage(), e);
        }
    }

    /** Strip a surrounding markdown code fence if present; otherwise return the trimmed input. */
    static String stripFence(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = FENCE.matcher(s.trim());
        return m.matches() ? m.group(1).trim() : s.trim();
    }
}
