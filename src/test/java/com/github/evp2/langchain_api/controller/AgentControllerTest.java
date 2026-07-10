package com.github.evp2.langchain_api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.evp2.langchain_api.model.DimensionAnalysis;
import com.github.evp2.langchain_api.model.ModelChoice;
import com.github.evp2.langchain_api.model.Synthesis;
import com.github.evp2.langchain_api.model.Verdict;
import com.github.evp2.langchain_api.service.CodeReviewService;
import com.github.evp2.langchain_api.service.ModelBackendException;
import com.github.evp2.langchain_api.service.PullRequestNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    private static final String PR_URL = "https://github.com/o/r/pull/1";
    private static final DimensionAnalysis ANALYSIS = new DimensionAnalysis("summary", List.of());

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CodeReviewService service;

    @Test
    void changeRiskDelegatesWithDefaultModel() throws Exception {
        when(service.analyzeChangeRisk(PR_URL, ModelChoice.CLAUDE_SONNET)).thenReturn(ANALYSIS);
        mvc.perform(post("/api/v1/agents/change-risk").param("prUrl", PR_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("summary"));
    }

    @Test
    void configurationDelegatesWithExplicitModel() throws Exception {
        when(service.analyzeConfiguration(PR_URL, ModelChoice.NVIDIA_NEMOTRON)).thenReturn(ANALYSIS);
        mvc.perform(post("/api/v1/agents/configuration")
                        .param("prUrl", PR_URL)
                        .param("model", "NVIDIA_NEMOTRON"))
                .andExpect(status().isOk());
    }

    @Test
    void observabilityDelegates() throws Exception {
        when(service.analyzeObservability(PR_URL, ModelChoice.CLAUDE_SONNET)).thenReturn(ANALYSIS);
        mvc.perform(post("/api/v1/agents/observability").param("prUrl", PR_URL))
                .andExpect(status().isOk());
    }

    @Test
    void synthesizerAcceptsBodyAndReturnsVerdict() throws Exception {
        when(service.synthesizeOnly(any(), eq(ModelChoice.CLAUDE_SONNET)))
                .thenReturn(new Synthesis(Verdict.CONDITIONAL, "fix first", List.of()));
        mvc.perform(post("/api/v1/agents/synthesizer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"o/r\",\"number\":1,\"title\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("CONDITIONAL"));
    }

    @Test
    void blankRepositoryFailsValidation() throws Exception {
        mvc.perform(post("/api/v1/agents/synthesizer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"\",\"number\":1}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void backendFailureMapsTo502() throws Exception {
        when(service.analyzeChangeRisk(PR_URL, ModelChoice.CLAUDE_SONNET))
                .thenThrow(new ModelBackendException("bedrock down"));
        mvc.perform(post("/api/v1/agents/change-risk").param("prUrl", PR_URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("bedrock down"));
    }

    @Test
    void missingPrMapsTo404() throws Exception {
        when(service.analyzeChangeRisk(PR_URL, ModelChoice.CLAUDE_SONNET))
                .thenThrow(new PullRequestNotFoundException("no such PR"));
        mvc.perform(post("/api/v1/agents/change-risk").param("prUrl", PR_URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("no such PR"));
    }

    @Test
    void invalidPrUrlMapsTo400() throws Exception {
        when(service.analyzeChangeRisk("garbage", ModelChoice.CLAUDE_SONNET))
                .thenThrow(new IllegalArgumentException("Not a valid GitHub PR URL"));
        mvc.perform(post("/api/v1/agents/change-risk").param("prUrl", "garbage"))
                .andExpect(status().isBadRequest());
    }
}
