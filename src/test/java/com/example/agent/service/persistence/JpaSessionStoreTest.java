package com.example.agent.service.persistence;

import com.example.agent.config.CurrentUser;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolOutcome;
import com.example.agent.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for JpaSessionStore against an in-memory H2 database.
 * The store is instantiated directly (bypassing @ConditionalOnProperty wiring),
 * so we only need the repositories and an ObjectMapper from the test context.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class JpaSessionStoreTest {

    @Autowired SessionRepository sessionRepo;
    @Autowired MessageRepository messageRepo;

    private JpaSessionStore store;

    /** Mutable stub so tests can switch who "me" is between steps. */
    static class StubUser extends CurrentUser {
        volatile String who = CurrentUser.ANONYMOUS;
        @Override public String name() { return who; }
    }
    private StubUser user;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());
        user = new StubUser();
        store = new JpaSessionStore(sessionRepo, messageRepo, mapper, user);
    }

    @Test
    void createGetAppendAndHydrate() {
        Session s = store.create();
        assertThat(sessionRepo.findById(s.getId())).isPresent();

        ChatMessage user = ChatMessage.user("hello");
        ChatMessage assistant = ChatMessage.assistantText("world");
        s.add(user);       store.appendMessage(s, user);
        s.add(assistant);  store.appendMessage(s, assistant);

        Session loaded = store.get(s.getId()).orElseThrow();
        List<ChatMessage> h = loaded.getHistory();
        assertThat(h).hasSize(2);
        assertThat(h.get(0).text()).isEqualTo("hello");
        assertThat(h.get(1).text()).isEqualTo("world");
    }

    @Test
    void aRefusedToolResultSurvivesTheStore() {
        Session s = store.create();
        ChatMessage call = ChatMessage.assistant("Reading.", List.of(new ToolCall("c1", "pod", java.util.Map.of("type", "read"))));
        ChatMessage results = ChatMessage.tool(List.of(
                ToolResult.refused("c1", "Refused: the owner has not granted access."),
                ToolResult.error("c2", "boom"),
                ToolResult.ok("c3", "doc")));
        s.add(call);     store.appendMessage(s, call);
        s.add(results);  store.appendMessage(s, results);

        List<ToolResult> loaded = store.get(s.getId()).orElseThrow().getHistory().get(1).toolResults();

        assertThat(loaded).extracting(ToolResult::outcome)
                .containsExactly(ToolOutcome.REFUSED, ToolOutcome.ERROR, ToolOutcome.OK);
        assertThat(loaded.get(0).isError()).isFalse();
        assertThat(messageRepo.findBySessionIdOrderByPositionAsc(s.getId()).get(1).getPayloadJson())
                .contains("\"outcome\":\"REFUSED\"");
    }

    @Test
    void updateTitle() {
        Session s = store.create();
        s.setTitle("My chat");
        store.update(s);
        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getTitle()).isEqualTo("My chat");
    }

    @Test
    void deleteRemovesSessionAndMessages() {
        Session s = store.create();
        store.appendMessage(s, ChatMessage.user("x"));
        store.appendMessage(s, ChatMessage.user("y"));
        store.delete(s.getId());

        assertThat(sessionRepo.findById(s.getId())).isEmpty();
        assertThat(messageRepo.findBySessionIdOrderByPositionAsc(s.getId())).isEmpty();
    }

    @Test
    void listReturnsAll() {
        Session a = store.create();
        Session b = store.create();
        assertThat(store.list()).extracting(Session::getId).contains(a.getId(), b.getId());
    }

    @Test
    void crossUserAccessLooksLikeNotFound() {
        user.who = "alice";
        Session s = store.create();
        store.appendMessage(s, ChatMessage.user("secret"));

        user.who = "bob";
        // Bob can't see Alice's session — not present in list, and get() returns empty
        assertThat(store.list()).extracting(Session::getId).doesNotContain(s.getId());
        assertThat(store.get(s.getId())).isEmpty();

        // Bob's delete is a no-op on Alice's session
        store.delete(s.getId());

        user.who = "alice";
        // Alice still sees it intact
        assertThat(store.get(s.getId())).isPresent();
        assertThat(store.get(s.getId()).get().getHistory()).hasSize(1);
    }

    @Test
    void updatePersistsTitleWhenSecurityContextIsAbsent() {
        // The agent loop calls update() from a pooled SSE thread where the
        // SecurityContext is absent, so the thread's identity resolves to
        // "anonymous". The update must be scoped by the session's own userId
        // (ownership was enforced when the session was loaded), or the title
        // written mid-stream is silently dropped.
        user.who = "alice";
        Session s = store.create();

        user.who = CurrentUser.ANONYMOUS;   // what me() yields on the SSE thread
        s.setTitle("First message title");
        store.update(s);

        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getTitle())
                .isEqualTo("First message title");
    }
}
