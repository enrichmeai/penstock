package com.example.agent.service.persistence;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "agent_sessions")
public class SessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /** The agent's plan (#174) as JSON; null when there is none. */
    @Column(name = "plan_json")
    private String planJson;

    protected SessionEntity() {}

    public SessionEntity(String id, String title, Instant createdAt, String userId) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.userId = userId;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
}
