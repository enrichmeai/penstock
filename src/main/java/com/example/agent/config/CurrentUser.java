package com.example.agent.config;

import com.example.agent.model.BearerToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the current user's name for session ownership.
 *
 *  - Auth disabled (or no security context set): returns "anonymous".
 *  - Auth enabled + authenticated: returns the principal name (e.g. "alice").
 *
 * All sessions are stamped with a userId derived from this value; store
 * implementations use it to filter queries so users only see their own data.
 *
 * <p>Both methods read thread-local security state and are therefore only
 * meaningful on the request thread. Capture what you need there and pass it
 * on explicitly (CLAUDE.md, "Identity on background threads").
 */
@Component
public class CurrentUser {

    public static final String ANONYMOUS = "anonymous";

    public String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return ANONYMOUS;
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? ANONYMOUS : name;
    }

    /**
     * The bearer token the caller authenticated with, when they did — that is, the
     * token Spring Security validated for this request ({@code agent.auth.mode=oidc}),
     * not whatever an {@code Authorization} header happened to say. Empty for Basic
     * auth, for the anonymous user, and off the request thread.
     */
    public Optional<BearerToken> bearer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AbstractOAuth2TokenAuthenticationToken<?> tokenAuth && tokenAuth.isAuthenticated()) {
            OAuth2Token token = tokenAuth.getToken();
            return token == null ? Optional.empty() : BearerToken.of(token.getTokenValue());
        }
        return Optional.empty();
    }
}
