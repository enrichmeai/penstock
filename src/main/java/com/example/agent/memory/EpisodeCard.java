package com.example.agent.memory;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed {@code episodes/<date>-<slug>.yaml} (issue #86) reduced to what a turn needs to
 * recall it (#88): what was asked, decided, refused and left open. {@code built} file lists
 * and {@code learned} lines are deliberately not carried — the first is bulk the block cannot
 * afford, the second reaches the turn as facts once consolidated (#87).
 */
public record EpisodeCard(
        String id,
        LocalDate date,
        String project,
        String asked,
        List<String> decided,
        List<String> refused,
        List<String> open) {
}
