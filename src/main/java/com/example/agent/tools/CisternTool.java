package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.config.RequestIdFilter;
import com.example.agent.model.BearerToken;
import com.example.agent.model.ToolOutcome;
import com.example.agent.model.ToolResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads and writes the user's own documents through a Cistern pod.
 *
 * <p>The difference between this and {@code read_file} is the whole reason it exists. A file
 * tool reads whatever the filesystem hands it, which is everything the process can see. This
 * asks a server that knows who is asking: the tool presents a credential, the pod resolves it
 * to a WebID, and Web Access Control decides — against rules the owner wrote, not rules the
 * agent was trusted to respect. Every allow and every deny is receipted on the owner's side,
 * and revoking the grant stops the next request without touching anything else.
 *
 * <p><em>Whose</em> credential is {@link CredentialMode}: the agent's own (the default —
 * the pod sees this application's WebID, so a grant to the agent is a grant to it alone),
 * one provisioned for the user, or the bearer the signed-in user presented on the request
 * that started this turn, in which case the pod sees the user. Every outbound call also
 * carries the inbound {@code X-Request-Id}, so the pod's receipt and this service's audit
 * log name the same request.
 *
 * <p><strong>A refusal is an answer, not a failure.</strong> {@code 403} means the owner did
 * not grant this, and the model needs to understand that as a fact about permission it should
 * report, rather than as a malfunction it should retry or work around. Only genuine faults —
 * a misconfigured credential, an unreachable pod — are returned as errors.
 */
@Component
public class CisternTool implements Tool {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String SERVICE = "cistern";
    private static final String DEFAULT_WRITE_MEDIA_TYPE = "text/turtle";

    /** A credential together with the mode that actually supplied it, so messages can be exact. */
    private record Credential(BearerToken token, CredentialMode source) {}

    private final String baseUrl;
    private final Optional<BearerToken> serviceToken;
    private final CredentialMode mode;
    private final WebClient webClient;
    private final CredentialResolver credentials;

    public CisternTool(AgentProperties props, WebClient.Builder webClientBuilder,
                       CredentialResolver credentials) {
        AgentProperties.Cistern cfg = props.getTools().getCistern();
        this.baseUrl = trimTrailingSlash(cfg.getBaseUrl());
        this.serviceToken = BearerToken.of(cfg.getToken());
        this.mode = cfg.getCredentialMode();
        this.webClient = webClientBuilder.build();
        this.credentials = credentials;
    }

    @Override
    public String name() {
        return "pod";
    }

    @Override
    public String description() {
        return "Read, list and write the user's own documents in their Cistern pod, " + actingAs() + ". "
            + "Operations: 'read' (fetch a resource), 'list' (contents of a container), "
            + "'write' (create or replace a resource), 'receipts' (what has been "
            + "accessed, if permitted). Access is decided by the owner's rules: a refusal "
            + "means you were not granted that resource — report it, do not work around it. "
            + "Large responses are truncated to the tool output cap (16 KB by default, "
            + "head and tail kept with an omission marker) — a truncated read is NOT the "
            + "full document; say so rather than treating it as complete. "
            + "Requires agent.tools.cistern.base-url and a credential for the configured "
            + "credential-mode.";
    }

    private String actingAs() {
        return switch (mode) {
            case SERVICE -> "as this agent with its own credential";
            case PER_USER -> "as the user with a credential provisioned for them";
            case FORWARD -> "as the signed-in user with the credential they presented";
        };
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of("read", "list", "write", "receipts"),
                    "description", "Operation to perform"),
                "path", Map.of("type", "string",
                    "description", "Path within the pod, e.g. /notes/meeting.ttl or /notes/"),
                "content", Map.of("type", "string",
                    "description", "Body to store. Required for 'write'."),
                "contentType", Map.of("type", "string",
                    "description", "Media type for 'write'. Defaults to " + DEFAULT_WRITE_MEDIA_TYPE + ".")),
            "required", List.of("type", "path"));
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context) {
        if (baseUrl.isBlank()) {
            return ToolResult.error(callId, "No pod configured. Set agent.tools.cistern.base-url.");
        }
        Optional<Credential> credential = credentialFor(context);
        if (credential.isEmpty()) {
            return ToolResult.error(callId, missingCredential(context));
        }
        String type = string(arguments, "type");
        String path = string(arguments, "path");
        if (path.isBlank()) {
            return ToolResult.error(callId, "path is required");
        }
        String uri = baseUrl + (path.startsWith("/") ? path : "/" + path);
        Consumer<HttpHeaders> headers = outboundHeaders(credential.get().token(), context);

        try {
            return switch (type) {
                case "read", "list" -> ToolResult.ok(callId, get(uri, headers));
                case "receipts" -> ToolResult.ok(callId, get(uri + "?receipts", headers));
                case "write" -> {
                    String content = string(arguments, "content");
                    String contentType = string(arguments, "contentType");
                    yield ToolResult.ok(callId, put(uri, content,
                        contentType.isBlank() ? DEFAULT_WRITE_MEDIA_TYPE : contentType, headers));
                }
                default -> ToolResult.error(callId, "Unknown operation: " + type);
            };
        } catch (WebClientResponseException e) {
            return describe(callId, uri, e, credential.get().source());
        } catch (RuntimeException e) {
            return ToolResult.error(callId, "Could not reach the pod at " + baseUrl + ": " + e.getMessage());
        }
    }

    /**
     * The credential this call presents, per {@link CredentialMode}. Empty means the tool
     * must not act at all — never that it should quietly act as someone else.
     */
    private Optional<Credential> credentialFor(ToolContext context) {
        return switch (mode) {
            case SERVICE -> serviceToken.map(t -> new Credential(t, CredentialMode.SERVICE));
            case PER_USER -> BearerToken.of(credentials == null ? null : credentials.resolve(SERVICE, context))
                .map(t -> new Credential(t, CredentialMode.PER_USER))
                .or(() -> serviceToken.map(t -> new Credential(t, CredentialMode.SERVICE)));
            case FORWARD -> context.bearer().map(t -> new Credential(t, CredentialMode.FORWARD));
        };
    }

    private String missingCredential(ToolContext context) {
        return switch (mode) {
            case SERVICE -> "No pod credential configured. Set agent.tools.cistern.token.";
            case PER_USER -> "No pod credential for user '" + context.userId() + "'. Set "
                + "agent.credentials.per-user.cistern." + context.userId()
                + ", or agent.tools.cistern.token as the fallback.";
            case FORWARD -> "The pod tool acts as the signed-in user (agent.tools.cistern.credential-mode=forward), "
                + "but this request carried no bearer token to forward. The agent will not substitute "
                + "its own credential; the user must sign in with a bearer token.";
        };
    }

    /**
     * Turns the pod's answer into something the model can act on correctly.
     *
     * <p>The distinction that matters: {@code 403} is the owner's decision and belongs in the
     * conversation as such, so it comes back as a {@link ToolOutcome#REFUSED refused} result —
     * not a success (the audit trail must not count it as one) and not an error (the model must
     * not retry it). {@code 401} and {@code 404} are the agent's problem or the caller's, and
     * are errors.
     */
    private static ToolResult describe(String callId, String uri, WebClientResponseException e,
                                       CredentialMode presented) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == HttpStatus.FORBIDDEN) {
            return ToolResult.refused(callId, "Refused: the owner has not granted access to "
                + uri + ". This is a permission decision, not an error — report it to the user "
                + "and do not attempt another route to the same content.");
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return ToolResult.error(callId, switch (presented) {
                case SERVICE -> "The pod did not recognise this agent's credential. "
                    + "Check agent.tools.cistern.token against the pod's configuration.";
                case PER_USER -> "The pod did not recognise the credential provisioned for this user. "
                    + "Check agent.credentials.per-user.cistern.<userId> against the pod's configuration.";
                case FORWARD -> "The pod did not accept the signed-in user's credential. "
                    + "It may have expired, or be issued for a different audience than the pod requires.";
            });
        }
        if (status == HttpStatus.NOT_FOUND) {
            return ToolResult.error(callId, "No such resource: " + uri);
        }
        return ToolResult.error(callId, "Pod returned " + e.getStatusCode() + " for " + uri);
    }

    /** Authorization for the chosen credential, plus the inbound request ID when there is one. */
    private static Consumer<HttpHeaders> outboundHeaders(BearerToken credential, ToolContext context) {
        return h -> {
            h.set(HttpHeaders.AUTHORIZATION, credential.authorizationHeaderValue());
            if (context.requestId() != null) {
                h.set(RequestIdFilter.REQUEST_ID_HEADER, context.requestId());
            }
        };
    }

    private String get(String uri, Consumer<HttpHeaders> headers) {
        return webClient.get()
            .uri(uri)
            .headers(headers)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .block();
    }

    private String put(String uri, String content, String contentType, Consumer<HttpHeaders> headers) {
        webClient.put()
            .uri(uri)
            .headers(headers)
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .bodyValue(content)
            .retrieve()
            .toBodilessEntity()
            .timeout(TIMEOUT)
            .block();
        return "Stored " + uri;
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : value.toString();
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : (url == null ? "" : url);
    }
}
