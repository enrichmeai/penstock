package com.example.agent.memory;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed {@code patterns/<id>/manifest.yaml} plus the two files {@link ContextAssembler}
 * renders alongside it ({@code PATTERN.md} and the skeleton file list) — loaded together by
 * {@link PatternCatalog} so a reload picks up edits to any of the three at once.
 */
public record PatternManifest(
        String id,
        String version,
        List<String> triggers,
        List<String> signatures,
        LocalDate verifiedAgainstDate,
        String patternMd,
        List<String> skeletonFiles) {
}
