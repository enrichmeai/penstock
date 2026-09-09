package com.example.agent.model;

import com.example.agent.service.TurnContext;
import com.example.agent.tools.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "never log the token" guarantee is structural: nothing that carries a bearer can
 * reveal it through toString, so a context in a log line or an exception message is safe.
 */
class BearerTokenTest {

    private static final String SECRET = "eyJ.super.secret";

    @Test
    void toStringNeverRevealsTheSecret() {
        BearerToken token = new BearerToken(SECRET);

        assertFalse(token.toString().contains(SECRET));
        assertFalse(new ToolContext("alice", "s1", "req-1", Optional.of(token)).toString().contains(SECRET));
        assertFalse(new TurnContext("req-1", Optional.of(token)).toString().contains(SECRET));
    }

    @Test
    void onlyTheExplicitAccessorsRevealIt() {
        BearerToken token = new BearerToken(SECRET);

        assertEquals(SECRET, token.secret());
        assertEquals("Bearer " + SECRET, token.authorizationHeaderValue());
    }

    @Test
    void blankIsAbsentNotAnEmptyCredential() {
        assertTrue(BearerToken.of(null).isEmpty());
        assertTrue(BearerToken.of("  ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new BearerToken(""));
        assertEquals(SECRET, BearerToken.of(SECRET).orElseThrow().secret());
    }
}
