package com.github.evp2.langchain_api.model;

import java.util.List;

/**
 * Output of a single specialist AI service for one {@link Dimension}.
 *
 * @param summary  a short narrative assessment of this dimension
 * @param findings the concrete findings discovered in this dimension
 */
public record DimensionAnalysis(
        String summary,
        List<Finding> findings) {
}
