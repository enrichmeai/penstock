package com.example.agent.model;

/**
 * One step of the agent's plan (#174): what to do, where it stands ({@code pending},
 * {@code in_progress} or {@code done}), and an optional short note. Kept on the {@link Session}.
 */
public record PlanItem(String text, String status, String note) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String DONE = "done";

    public PlanItem {
        text = text == null ? "" : text;
        status = status == null ? PENDING : status;
        note = note == null || note.isBlank() ? null : note;
    }
}
