package com.example.agent.tools;

/**
 * Whose credential the pod tool presents to Cistern — that is, who the agent acts as.
 *
 * <p>Bound from {@code agent.tools.cistern.credential-mode}. Spring's lenient enum binding
 * accepts {@code service}, {@code per-user} (or {@code per_user}) and {@code forward} in any
 * case; the environment form is {@code AGENT_TOOLS_CISTERN_CREDENTIALMODE}.
 */
public enum CredentialMode {

    /**
     * The agent's own credential ({@code agent.tools.cistern.token}). The pod sees the
     * agent's WebID, and the owner's grant to the agent is what decides. Default.
     */
    SERVICE,

    /**
     * A credential provisioned for the acting user
     * ({@code agent.credentials.per-user.cistern.<userId>}); a user with none provisioned
     * falls back to the agent's own credential, as {@code CredentialResolver} documents.
     */
    PER_USER,

    /**
     * The bearer token the signed-in user presented on the inbound request, forwarded as
     * is. The pod sees the user. There is no fallback: a request that carried no bearer
     * gets a refusal from the tool, because substituting the agent's own credential would
     * change who is acting without anyone asking for that.
     */
    FORWARD
}
