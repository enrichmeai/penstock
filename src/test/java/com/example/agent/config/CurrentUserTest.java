package com.example.agent.config;

import com.example.agent.model.BearerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CurrentUser#bearer()} hands out the token Spring Security validated for the
 * request — and nothing else. Basic auth has no bearer; neither does no auth.
 */
class CurrentUserTest {

    private static final String TOKEN_VALUE = "eyJ.header.payload.sig";

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theValidatedJwtIsAvailableAsABearer() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(TOKEN_VALUE)
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        // The resource server builds the authenticated (authorities-carrying) form; the
        // single-arg constructor is the pre-authentication one and is never what a request sees.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("SCOPE_read")));

        Optional<BearerToken> bearer = currentUser.bearer();

        assertEquals(TOKEN_VALUE, bearer.orElseThrow().secret());
        assertEquals("alice", currentUser.name());
    }

    @Test
    void basicAuthHasNoBearer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "secret", AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertTrue(currentUser.bearer().isEmpty());
        assertEquals("alice", currentUser.name());
    }

    @Test
    void noAuthenticationHasNoBearer() {
        assertTrue(currentUser.bearer().isEmpty());
        assertEquals(CurrentUser.ANONYMOUS, currentUser.name());
    }
}
