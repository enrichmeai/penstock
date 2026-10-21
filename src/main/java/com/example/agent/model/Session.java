package com.example.agent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A conversation with the agent.
 */
public class Session {
    private final String id;
    private final Instant createdAt;
    private final List<ChatMessage> history = new ArrayList<>();
    private String title;
    private String userId = "anonymous";
    private volatile TokenUsage totalUsage = TokenUsage.ZERO;
    /** The agent's plan (#174), replaced whole on each change so readers never see it half-written. */
    private volatile List<PlanItem> plan = List.of();
    private final Object usageLock = new Object();

    public Session() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.title = "New session";
    }

    public Session(String userId) {
        this();
        this.userId = (userId == null || userId.isBlank()) ? "anonymous" : userId;
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ChatMessage> getHistory() { return Collections.unmodifiableList(history); }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = (userId == null || userId.isBlank()) ? "anonymous" : userId; }

    public TokenUsage getTotalUsage() { return totalUsage; }

    /** Thread-safe accumulation — SSE writer and API reader run on different threads. */
    public void addUsage(TokenUsage delta) {
        synchronized (usageLock) { this.totalUsage = this.totalUsage.plus(delta); }
    }

    public void add(ChatMessage m) { history.add(m); }

    public List<PlanItem> getPlan() { return plan; }
    public void setPlan(List<PlanItem> plan) { this.plan = plan == null ? List.of() : List.copyOf(plan); }
}
