package com.example.agent.config;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies SecurityConfig gating: auth off = everything open; auth on = 401 without
 * credentials, 200 with valid Basic auth.
 */
class SecurityConfigTest {

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

    // ---------- default: auth ON ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            // Deliberately NO agent.auth.enabled: the product default must be
            // secure. An agent that executes shell commands never ships open —
            // opting OUT is the explicit act.
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-default",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class DefaultIsAuthOn {
        @Autowired MockMvc mvc;

        @Test
        void unauthenticatedCallsAreRejectedByDefault() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void healthStaysOpenByDefault() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }
    }

    // ---------- auth OFF ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-off",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOff {
        @Autowired MockMvc mvc;

        @Test
        void anyEndpointIsOpen() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isOk());
            mvc.perform(get("/api/sessions")).andExpect(status().isOk());
        }
    }

    // ---------- auth ON, metrics deliberately reopened ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=alice",
            "agent.auth.password=wonderland",
            "agent.metrics.public-scrape=true",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-scrape",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOnWithPublicScrape {
        @Autowired MockMvc mvc;

        @Test
        void metricsAreReadableWithoutCredentials() throws Exception {
            // The escape hatch for a scraper that cannot authenticate. Asserting
            // "not 401" rather than 200: this slice has no Prometheus registry, so
            // the endpoint itself errors — but it is reached, which is the point.
            mvc.perform(get("/actuator/prometheus"))
               .andExpect(r -> org.junit.jupiter.api.Assertions.assertNotEquals(
                       401, r.getResponse().getStatus(),
                       "public-scrape=true must not require auth"));
        }

        @Test
        void reopeningMetricsDoesNotReopenTheApiDocs() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        }

        @Test
        void reopeningMetricsDoesNotReopenTheApi() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }
    }

    // ---------- auth ON ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=alice",
            "agent.auth.password=wonderland",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-on",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOn {
        @Autowired MockMvc mvc;

        @Test
        void healthIsAlwaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }

        @Test
        void unauthenticatedCallsAreRejected() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void metricsRequireAuthByDefault() throws Exception {
            // Labels carry tool names, providers, token counts and request rates.
            mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        }

        @Test
        void apiDocsAndSwaggerRequireAuth() throws Exception {
            // The OpenAPI document describes every endpoint and schema.
            mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }

        @Test
        void actuatorHealthStaysOpenForLoadBalancers() throws Exception {
            // Assert reachability, not 200: the stub provider makes the llmProvider
            // contributor report DOWN, so the endpoint legitimately answers 503.
            // What matters here is that security did not reject it.
            mvc.perform(get("/actuator/health"))
               .andExpect(r -> org.junit.jupiter.api.Assertions.assertNotEquals(
                       401, r.getResponse().getStatus(), "health must not require auth"));
        }

        @Test
        void validBasicAuthSucceeds() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:wonderland".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isOk());
        }

        @Test
        void wrongPasswordIsRejected() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:nope".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isUnauthorized());
        }
    }

    // ---------- auth ON + OAuth registered ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=alice",
            "agent.auth.password=wonderland",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-oauth",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false",
            // OAuthClientConfig reads these via Environment (any property source works)
            "GITHUB_OAUTH_CLIENT_ID=test-id",
            "GITHUB_OAUTH_CLIENT_SECRET=test-secret"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOnWithOAuth {
        @Autowired MockMvc mvc;

        @Test
        void apiBasicAuthStillWorks() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:wonderland".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isOk());
        }

        @Test
        void apiUnauthenticatedStillReturns401() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void oauthAuthorizationEndpointRedirectsToProvider() throws Exception {
            String location = mvc.perform(get("/oauth2/authorization/github"))
               .andExpect(status().is3xxRedirection())
               .andReturn().getResponse().getHeader("Location");
            assertNotNull(location, "Location header must be set");
            assertTrue(location.startsWith("https://github.com/login/oauth/authorize?"),
                    "Expected GitHub authorize URL but got: " + location);
            assertTrue(location.contains("client_id=test-id"),
                    "Authorize URL should embed configured client id: " + location);
        }
    }

    // ---------- auth ON, mode=oidc ----------

    /**
     * OIDC mode: API requires a JWT bearer token. We replace the production
     * {@link JwtDecoder} with a {@link MockBean} so the resource server does
     * NOT try to fetch JWKS from {@code https://test.example.com/.well-known/...}
     * at startup. Tests then use Spring Security's
     * {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt()}
     * helper to inject an authenticated context — that path short-circuits the
     * decoder, so the {@code @MockBean} only exists to satisfy the bean
     * dependency graph at startup.
     */
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=oidc",
            "agent.auth.oidc.issuer-uri=https://test.example.com",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-oidc",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class OidcAuthMode {
        @Autowired MockMvc mvc;
        @MockitoBean JwtDecoder jwtDecoder;

        @Test
        void unauthenticatedReturns401() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void validJwtSucceeds() throws Exception {
            mvc.perform(get("/api/tools").with(jwt().jwt(j -> j.subject("alice"))))
               .andExpect(status().isOk());
        }

        @Test
        void healthIsAlwaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }
    }

    // ---------- auth ON, no password configured (issue #18) ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=admin",
            // deliberately NO agent.auth.password — the shipped default must
            // not authenticate; a random password is generated instead.
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-nopass",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOnWithoutPassword {
        @Autowired MockMvc mvc;

        @Test
        void knownDefaultPasswordIsRejected() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("admin:change-me".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isUnauthorized());
        }

        @Test
        void healthStaysOpenAndOtherEndpointsStayGated() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }
    }

    // ---------- "acp" profile active without web-application-type=none ----------

    /**
     * Regression guard for issue #61's reviewer finding: SecurityConfig must not be excluded
     * by the "acp" Spring profile name alone — a profile can be activated independently of
     * {@code spring.main.web-application-type} (e.g. a bare {@code SPRING_PROFILES_ACTIVE=acp}
     * that never goes through {@code AgentApplication}'s own {@code --acp} detection). This
     * context is a normal servlet web app (the default {@code @SpringBootTest} web environment)
     * with the {@code acp} profile active; if SecurityConfig were keyed on the profile name,
     * this would boot with every endpoint open. It must still require auth.
     */
    @SpringBootTest
    @AutoConfigureMockMvc
    @org.springframework.test.context.ActiveProfiles("acp")
    @TestPropertySource(properties = {
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-acp-profile-servlet",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AcpProfileActiveButStillAServletWebApp {
        @Autowired MockMvc mvc;

        @Test
        void authIsStillEnforced() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void healthStaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }
    }
}
