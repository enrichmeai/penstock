package com.example.agent.memory;

import java.util.List;

/** A parsed {@code requests/<id>.yaml} — only the fields {@link ContextAssembler} needs. */
public record RequestCard(String id, List<String> references, List<String> patterns) {
}
