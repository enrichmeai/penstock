package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.BearerToken;
import com.example.agent.model.ToolOutcome;
import com.example.agent.model.ToolResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whose credential the pod tool presents, per {@link CredentialMode}, and what else every
 * outbound call carries. Also pins the tool's refusal contract (403 → successful "refused"
 * result) and that a missing credential never becomes a silent switch of identity.
 */
class CisternToolTest {

    private static final String SERVICE_TOKEN = "service-token";
    private static final String ALICE_PROVISIONED = "alice-token";
    private static final String ALICE_INBOUND = "eyJ.alice.inbound";

    private MockWebServer pod;

    @BeforeEach
    void setUp() throws Exception {
        pod = new MockWebServer();
        pod.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        pod.shutdown();
    }

    private AgentProperties properties(CredentialMode mode, String serviceToken) {
        AgentProperties props = new AgentProperties();
        props.getTools().getCistern().setBaseUrl(pod.url("/").toString());
        props.getTools().getCistern().setToken(serviceToken);
        props.getTools().getCistern().setCredentialMode(mode);
        props.getCredentials().setPerUser(Map.of("cistern", Map.of("alice", ALICE_PROVISIONED)));
        return props;
    }

    private CisternTool tool(CredentialMode mode) {
        return tool(mode, SERVICE_TOKEN);
    }

    private CisternTool tool(CredentialMode mode, String serviceToken) {
        AgentProperties props = properties(mode, serviceToken);
        return new CisternTool(props, WebClient.builder(), new ConfiguredCredentialResolver(props));
    }

    private static ToolContext signedIn(String userId, String inboundBearer) {
        return new ToolContext(userId, "s1", "req-1", BearerToken.of(inboundBearer));
    }

    private static Map<String, Object> read(String path) {
        return Map.of("type", "read", "path", path);
    }

    @Nested
    class ServiceMode {

        @Test
        void sendsTheAgentsOwnCredentialEvenWhenTheUserHasOneProvisioned() throws Exception {
            pod.enqueue(new MockResponse().setBody("doc"));

            ToolResult r = tool(CredentialMode.SERVICE).execute("c1", read("/notes/a.ttl"),
                    signedIn("alice", ALICE_INBOUND));

            assertFalse(r.isError());
            assertEquals("Bearer " + SERVICE_TOKEN, pod.takeRequest().getHeader("Authorization"));
        }

        @Test
        void withoutAServiceTokenItIsAConfigurationError() throws Exception {
            ToolResult r = tool(CredentialMode.SERVICE, "").execute("c2", read("/x"),
                    signedIn("alice", ALICE_INBOUND));

            assertTrue(r.isError());
            assertTrue(r.content().contains("agent.tools.cistern.token"), r.content());
            assertNull(pod.takeRequest(200, TimeUnit.MILLISECONDS), "no call is made without a credential");
        }
    }

    @Nested
    class PerUserMode {

        @Test
        void actsWithThePerUserCredentialWhenOneIsConfigured() throws Exception {
            pod.enqueue(new MockResponse().setBody("doc"));

            ToolResult r = tool(CredentialMode.PER_USER).execute("c1", read("/notes/a.ttl"),
                    new ToolContext("alice", "s1"));

            assertFalse(r.isError());
            RecordedRequest req = pod.takeRequest();
            assertEquals("Bearer " + ALICE_PROVISIONED, req.getHeader("Authorization"));
        }

        @Test
        void fallsBackToTheServiceCredentialForUsersWithoutOne() throws Exception {
            pod.enqueue(new MockResponse().setBody("doc"));

            ToolResult r = tool(CredentialMode.PER_USER).execute("c2", read("/notes/a.ttl"),
                    new ToolContext("bob", "s2"));

            assertFalse(r.isError());
            assertEquals("Bearer " + SERVICE_TOKEN, pod.takeRequest().getHeader("Authorization"));
        }

        @Test
        void anonymousContextUsesTheServiceCredential() throws Exception {
            pod.enqueue(new MockResponse().setBody("doc"));

            tool(CredentialMode.PER_USER).execute("c3", read("/x"), ToolContext.anonymous());

            assertEquals("Bearer " + SERVICE_TOKEN, pod.takeRequest().getHeader("Authorization"));
        }

        @Test
        void neitherCredentialIsAnErrorNamingTheUser() throws Exception {
            ToolResult r = tool(CredentialMode.PER_USER, "").execute("c4", read("/x"),
                    new ToolContext("bob", "s2"));

            assertTrue(r.isError());
            assertTrue(r.content().contains("agent.credentials.per-user.cistern.bob"), r.content());
            assertNull(pod.takeRequest(200, TimeUnit.MILLISECONDS));
        }
    }

    @Nested
    class ForwardMode {

        @Test
        void forwardsTheBearerTheSignedInUserPresented() throws Exception {
            pod.enqueue(new MockResponse().setBody("doc"));

            ToolResult r = tool(CredentialMode.FORWARD).execute("c1", read("/notes/a.ttl"),
                    signedIn("alice", ALICE_INBOUND));

            assertFalse(r.isError());
            assertEquals("Bearer " + ALICE_INBOUND, pod.takeRequest().getHeader("Authorization"),
                    "the inbound token, not the provisioned one and not the agent's own");
        }

        @Test
        void withoutAnInboundBearerItRefusesRatherThanActingAsTheAgent() throws Exception {
            // alice has a provisioned credential and the agent has its own; neither may be substituted.
            ToolResult r = tool(CredentialMode.FORWARD).execute("c2", read("/notes/a.ttl"),
                    new ToolContext("alice", "s1"));

            assertTrue(r.isError());
            assertTrue(r.content().contains("no bearer token"), r.content());
            assertNull(pod.takeRequest(200, TimeUnit.MILLISECONDS), "no call is made with a substitute credential");
        }

        @Test
        void aRejectedForwardedCredentialIsReportedAsTheUsersNotTheAgents() throws Exception {
            pod.enqueue(new MockResponse().setResponseCode(401));

            ToolResult r = tool(CredentialMode.FORWARD).execute("c3", read("/notes/a.ttl"),
                    signedIn("alice", ALICE_INBOUND));

            assertTrue(r.isError());
            assertTrue(r.content().contains("signed-in user's credential"), r.content());
            assertFalse(r.content().contains("agent.tools.cistern.token"), r.content());
        }
    }

    @Test
    void everyOutboundCallCarriesTheInboundRequestId() throws Exception {
        pod.enqueue(new MockResponse().setBody("doc"));
        pod.enqueue(new MockResponse().setResponseCode(201));
        CisternTool tool = tool(CredentialMode.SERVICE);
        ToolContext context = new ToolContext("alice", "s1", "req-42", Optional.empty());

        tool.execute("c1", read("/notes/a.ttl"), context);
        tool.execute("c2", Map.of("type", "write", "path", "/notes/b.ttl", "content", "<a> <b> <c> ."), context);

        RecordedRequest get = pod.takeRequest();
        RecordedRequest put = pod.takeRequest();
        assertEquals("req-42", get.getHeader("X-Request-Id"));
        assertEquals("req-42", put.getHeader("X-Request-Id"));
        assertEquals("PUT", put.getMethod());
        assertEquals("text/turtle", put.getHeader("Content-Type"));
    }

    @Test
    void aTurnWithoutARequestIdSendsNoHeader() throws Exception {
        pod.enqueue(new MockResponse().setBody("doc"));

        tool(CredentialMode.SERVICE).execute("c1", read("/x"), new ToolContext("alice", "s1"));

        assertNull(pod.takeRequest().getHeader("X-Request-Id"));
    }

    @Test
    void aRefusalIsItsOwnOutcomeNotAnError() {
        pod.enqueue(new MockResponse().setResponseCode(403));

        ToolResult r = tool(CredentialMode.SERVICE).execute("c4", read("/private/x"),
                new ToolContext("alice", "s1"));

        assertEquals(ToolOutcome.REFUSED, r.outcome(), "403 is the owner's decision: refused, not ok and not an error");
        assertFalse(r.isError(), "403 is the owner's decision, not a malfunction");
        assertTrue(r.content().contains("Refused"));
        assertTrue(r.content().contains("do not attempt another route"), "the do-not-retry instruction to the model stays");
    }

    @Test
    void anUnrecognisedCredentialAndAMissingResourceAreErrors() {
        pod.enqueue(new MockResponse().setResponseCode(401));
        pod.enqueue(new MockResponse().setResponseCode(404));
        CisternTool tool = tool(CredentialMode.SERVICE);

        ToolResult unauthorised = tool.execute("c5", read("/x"), new ToolContext("alice", "s1"));
        ToolResult missing = tool.execute("c6", read("/y"), new ToolContext("alice", "s1"));

        assertEquals(ToolOutcome.ERROR, unauthorised.outcome());
        assertEquals(ToolOutcome.ERROR, missing.outcome());
        assertTrue(missing.content().contains("No such resource"));
    }

    @Test
    void anUnreachablePodIsAnError() throws Exception {
        pod.shutdown();

        ToolResult r = tool(CredentialMode.SERVICE).execute("c7", read("/x"), new ToolContext("alice", "s1"));

        assertEquals(ToolOutcome.ERROR, r.outcome());
        assertTrue(r.content().contains("Could not reach the pod"), r.content());
    }

    @Test
    void descriptionTellsTheModelWhoItActsAs() {
        assertTrue(tool(CredentialMode.SERVICE).description().contains("as this agent"));
        assertTrue(tool(CredentialMode.PER_USER).description().contains("as the user"));
        assertTrue(tool(CredentialMode.FORWARD).description().contains("as the signed-in user"));
    }
}
