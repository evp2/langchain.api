package com.github.evp2.langchain_api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full app with the MCP server auto-configuration active and asserts that the four
 * @McpTool methods on {@link ReviewMcpTools} are registered as MCP tools. Uses the "test" profile
 * (dummy ARN, STS health check disabled) so no AWS is contacted — tool registration is metadata
 * only and never invokes Bedrock.
 *
 * <p>Several beans of type {@code List<SyncToolSpecification>} exist (the annotation scanner's and
 * the tool-callback converter's), so we inject an {@link ObjectProvider} and flatten across all of
 * them rather than a single ambiguous list.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpServerIntegrationTest {

    @Autowired
    private ObjectProvider<List<SyncToolSpecification>> toolSpecs;

    private List<Tool> tools() {
        return toolSpecs.stream()
                .flatMap(List::stream)
                .map(SyncToolSpecification::tool)
                .toList();
    }

    @Test
    void registersTheFourReviewTools() {
        assertThat(tools()).extracting(Tool::name)
                .contains("analyze_pr", "submit_review", "get_review", "synthesize");
    }

    @Test
    void toolsCarryDescriptions() {
        assertThat(tools())
                .filteredOn(t -> t.name().equals("analyze_pr"))
                .singleElement()
                .satisfies(t -> assertThat(t.description()).contains("resiliency review"));
    }
}
