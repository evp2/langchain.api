package com.github.evp2.langchain_api.controller;

import com.github.evp2.langchain_api.model.ErrorResponse;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.PromptRequest;
import com.github.evp2.langchain_api.model.PromptResponse;
import com.github.evp2.langchain_api.service.CodeReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for sending a free-form prompt directly to a selected model backend. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Prompt", description = "Send a free-form prompt straight to a selected model, bypassing the review agents.")
public class PromptController {

    private final CodeReviewService service;

    public PromptController(CodeReviewService service) {
        this.service = service;
    }

    @Operation(
            summary = "Send a direct prompt to a model",
            description = "Passes the supplied prompt verbatim to the selected model backend and returns its raw text completion.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Completion produced"),
            @ApiResponse(responseCode = "400", description = "Empty prompt",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Model/backend failure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/prompt")
    public PromptResponse prompt(
            @Valid @RequestBody PromptRequest request,
            @Parameter(description = "Which model backend to invoke")
            @RequestParam(value = "model", defaultValue = "CLAUDE_SONNET") ModelChoice model) {
        return new PromptResponse(model, service.prompt(request.prompt(), model));
    }
}
