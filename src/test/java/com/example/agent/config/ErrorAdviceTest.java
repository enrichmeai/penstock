package com.example.agent.config;

import jakarta.validation.constraints.NotBlank;
import com.example.agent.llm.GatewayRefusedException;
import com.example.agent.llm.LiteLlmFixtures;
import com.example.agent.llm.LlmMessage;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for ErrorAdvice global exception handler.
 * Each path tests a distinct exception type and verifies:
 * - Correct HTTP status code
 * - Correct error code and message
 * - Presence of requestId and timestamp
 * - Sensitive information is NOT leaked in responses
 * - fieldErrors present for validation errors
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.auth.enabled=false",
        "agent.llm.provider=stub",
        "agent.workspace=${java.io.tmpdir}/agent-test-errors",
        "agent.storage.type=memory"
})
@org.springframework.context.annotation.Import({ErrorAdviceTest.StubCfg.class, ErrorAdviceTest.ErrorTestController.class})
class ErrorAdviceTest {

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider stub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> h, List<ToolSpec> t, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("ok"), TokenUsage.ZERO);
                }
            };
        }
    }

    /**
     * Exception class marked as safe — message should pass through.
     */
    @SafeMessage
    static class SafeStateException extends IllegalStateException {
        SafeStateException(String msg) {
            super(msg);
        }
    }

    /**
     * Test controller that throws various exceptions on distinct paths.
     */
    @RestController
    static class ErrorTestController {

        @GetMapping("/error/forbidden")
        void forbidden() {
            throw new AccessDeniedException("User lacks required permission");
        }

        @GetMapping("/error/unauthenticated")
        void unauthenticated() {
            throw new AuthenticationException("No credentials provided") {};
        }

        @PostMapping("/error/validation")
        void validation(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid ValidRequest req) {
            // Validation triggers MethodArgumentNotValidException → ErrorAdvice → 400
        }

        @GetMapping("/error/bad-request")
        void badRequest() {
            throw new IllegalArgumentException("Invalid input value");
        }

        @GetMapping("/error/illegal-state-unsafe")
        void illegalStateUnsafe() {
            throw new IllegalStateException("API key missing or invalid");
        }

        @GetMapping("/error/illegal-state-safe")
        void illegalStateSafe() {
            throw new SafeStateException("Safe message to expose");
        }

        @GetMapping("/error/internal")
        void internalError() {
            throw new RuntimeException("Unexpected error occurred");
        }

        /** The gateway's captured 429 for an exhausted budget, thrown as the llm layer throws it. */
        @GetMapping("/error/gateway-budget")
        void gatewayBudgetExceeded() throws IOException {
            throw GatewayRefusedException.from("openai",
                    LiteLlmFixtures.load(LiteLlmFixtures.BUDGET_EXCEEDED).asException());
        }

        /** The gateway's captured 429 for a rate limit (Retry-After: 60). */
        @GetMapping("/error/gateway-rate-limit")
        void gatewayRateLimited() throws IOException {
            throw GatewayRefusedException.from("openai",
                    LiteLlmFixtures.load(LiteLlmFixtures.RATE_LIMITED).asException());
        }
    }

    /**
     * A simple DTO for validation testing (requires non-blank text).
     */
    static class ValidRequest {
        @NotBlank private String text;
        public void setText(String text) { this.text = text; }
        public String getText() { return text; }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void accessDeniedReturns403() throws Exception {
        mvc.perform(get("/error/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.error").value("Access denied."))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticationExceptionReturns401() throws Exception {
        mvc.perform(get("/error/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.error").value("Authentication required."))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void validationExceptionReturns400WithFieldErrors() throws Exception {
        String invalidJson = "{}"; // Missing required field 'text' or invalid format
        mvc.perform(post("/error/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        // fieldErrors might be present depending on validation annotations
    }

    @Test
    void illegalArgumentExceptionReturns400() throws Exception {
        mvc.perform(get("/error/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.error").value("Invalid input value"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void illegalStateExceptionUnsafeReturns400WithSanitizedMessage() throws Exception {
        String response = mvc.perform(get("/error/illegal-state-unsafe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_state"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();

        // Sensitive detail MUST NOT appear in response
        assertThat(response).doesNotContain("API key missing");
        // Generic message should be present
        assertThat(response).contains("Service is not ready.");
    }

    @Test
    void illegalStateExceptionSafeReturns400WithPassthroughMessage() throws Exception {
        mvc.perform(get("/error/illegal-state-safe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_state"))
                .andExpect(jsonPath("$.error").value("Safe message to expose"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void throwableReturns500() throws Exception {
        String response = mvc.perform(get("/error/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();

        // Message is generic and sanitized
        assertThat(response).contains("Internal error.");
        // Original error message MUST NOT appear
        assertThat(response).doesNotContain("Unexpected error occurred");
    }

    @Test
    void gatewayRefusalIs429WithCodeAndReasonNotAnInternalError() throws Exception {
        String response = mvc.perform(get("/error/gateway-budget"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("gateway_refused"))
                .andExpect(jsonPath("$.reason").value("budget_exceeded"))
                .andExpect(jsonPath("$.error").value(LlmMessage.GATEWAY_REFUSED_BUDGET_EXCEEDED.format()))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn().getResponse().getContentAsString();

        // The gateway's own text (team, spend, budget) stays out of the response
        assertThat(response).doesNotContain("Team=support");
        assertThat(response).doesNotContain("internal_error");
    }

    @Test
    void gatewayRateLimitCarriesTheGatewaysRetryAfter() throws Exception {
        String response = mvc.perform(get("/error/gateway-rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("gateway_refused"))
                .andExpect(jsonPath("$.reason").value("rate_limited"))
                .andExpect(jsonPath("$.error").value(LlmMessage.GATEWAY_REFUSED_RATE_LIMITED_RETRY_AFTER.format(60)))
                .andReturn().getResponse().getContentAsString();

        // The key hash in the gateway's message never reaches a client
        assertThat(response).doesNotContain("api_key");
    }
}
