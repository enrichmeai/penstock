package com.example.agent.memory;

import java.util.List;

/** A parsed {@code references/<id>.yaml} — only the fields {@link ContextAssembler} needs. */
public record ReferenceCard(String id, String version, List<String> holds) {
}
