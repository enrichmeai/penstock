package com.example.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** The body of {@code POST /api/sessions/{id}/approvals/{callId}} (#179): "approve" or "deny". */
public record ApprovalDecision(@NotBlank String decision) {}
