package com.example.agent.memory;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed {@code facts/<id>.yaml} (issue #87) — only the fields {@link ContextAssembler} needs
 * to select and render it. {@code status} is kept so a superseded fact is loaded (the record
 * stays whole) but never selected (#88).
 */
public record FactCard(
        String id,
        String statement,
        String kind,
        String subject,
        String scope,
        String status,
        double confidence,
        LocalDate lastConfirmed,
        List<String> triggers) {

    public boolean isActive() {
        return !"superseded".equals(status);
    }
}
