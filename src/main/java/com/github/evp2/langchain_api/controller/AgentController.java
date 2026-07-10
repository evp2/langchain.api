package com.github.evp2.langchain_api.controller;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.ErrorResponse;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.SynthesisRequest;
import com.github.evp2.langchain_api.service.CodeReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for invoking the individual review sub-agents on their own. */
@RestController
@RequestMapping("/api/v1/agents")
@Validated
@Tag(name = "Agents", description = "Invoke the individual review specialists (and synthesizer) on their own.")
public class AgentController {

    private final CodeReviewService service;

    public AgentController(CodeReviewService service) {
        this.service = service;
    }

    @Operation(
            summary = "Run only the change-risk (CHR) specialist",
            description = "Runs the change-risk reviewer alone over the PR diff and returns its raw analysis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed"),
            @ApiResponse(responseCode = "400", description = "Malformed PR URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PR not found or not accessible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/change-risk")
    public DimensionAnalysis changeRisk(
            @Parameter(description = "Full GitHub pull request URL",
                    example = "https://github.com/Vanguard/resiliency-agent.ecs/pull/228", required = true)
            @RequestParam("prUrl") @NotBlank String prUrl,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        return service.analyzeChangeRisk(prUrl, model);
    }

    @Operation(
            summary = "Run only the configuration (CFG) specialist",
            description = "Runs the configuration reviewer alone over the PR diff and returns its raw analysis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed"),
            @ApiResponse(responseCode = "400", description = "Malformed PR URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PR not found or not accessible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/configuration")
    public DimensionAnalysis configuration(
            @Parameter(description = "Full GitHub pull request URL",
                    example = "https://github.com/Vanguard/resiliency-agent.ecs/pull/228", required = true)
            @RequestParam("prUrl") @NotBlank String prUrl,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        return service.analyzeConfiguration(prUrl, model);
    }

    @Operation(
            summary = "Run only the observability (ORA) specialist",
            description = "Runs the observability reviewer alone over the PR diff and returns its raw analysis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Analysis completed"),
            @ApiResponse(responseCode = "400", description = "Malformed PR URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PR not found or not accessible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/observability")
    public DimensionAnalysis observability(
            @Parameter(description = "Full GitHub pull request URL",
                    example = "https://github.com/Vanguard/resiliency-agent.ecs/pull/228", required = true)
            @RequestParam("prUrl") @NotBlank String prUrl,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        return service.analyzeObservability(prUrl, model);
    }

    @Operation(
            summary = "Run only the synthesizer",
            description = """
                    Runs the synthesizer alone over caller-supplied specialist analyses and returns the
                    consolidated GO / CONDITIONAL / NO_GO verdict. Use this after calling the specialist
                    endpoints to combine their results without re-running the full pipeline.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Synthesis completed"),
            @ApiResponse(responseCode = "400", description = "Malformed request body",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/synthesizer")
    public Synthesis synthesizer(
            @Valid @RequestBody SynthesisRequest request,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        return service.synthesizeOnly(request, model);
    }
}
