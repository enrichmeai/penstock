package com.example.agent.tools;

/**
 * Resolves the outbound credential a tool or provider should use when acting for a user.
 *
 * Part C of the identity work: by default a tool authenticates as ITSELF with
 * its configured service credential, and the far system's rules decide what
 * the agent may touch. Deployments that provision per-user credentials get
 * per-user attribution at the far end with no tool-code changes — the tool
 * asks here first and falls back to its service credential.
 *
 * The same seam serves the model gateway: {@code OpenAiProvider} asks for service
 * {@code "openai"} so each user's calls carry their own gateway key.
 */
public interface CredentialResolver {

    /**
     * @param service logical service name, e.g. "cistern" or "openai"
     * @param userId  the acting user, as stamped on the session
     * @return the credential configured for this user on this service, or
     *         {@code null} when none is — callers fall back to their service
     *         credential, never treat null as an error
     */
    String resolve(String service, String userId);

    /** Convenience for tools, which hold a {@link ToolContext}. */
    default String resolve(String service, ToolContext context) {
        return context == null ? null : resolve(service, context.userId());
    }
}
