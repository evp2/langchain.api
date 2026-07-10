package com.github.evp2.langchain_api.model;

import java.util.List;

/**
 * Per-dimension section of the final API response.
 *
 * @param dimension which resiliency dimension this covers
 * @param summary   the specialist's narrative assessment
 * @param findings  findings for this dimension
 */
public record DimensionResult(
        Dimension dimension,
        String summary,
        List<Finding> findings) {
}
