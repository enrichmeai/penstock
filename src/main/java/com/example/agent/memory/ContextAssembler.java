package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Matches a turn's user input against {@link PatternCatalog} and, when at least one pattern
 * matches, builds the single labelled context block {@code AgentService.runTurn} prepends to
 * the system prompt for that turn (see CLAUDE.md "retrieve before reason", issue #73).
 *
 * <p>Matching is deterministic, not a model call: keyword overlap between the user's input and
 * a pattern's {@code triggers}, plus whether any of its {@code signatures} globs matches a file
 * already in {@code agent.workspace}. Patterns are ranked by hit count, ties broken by
 * {@code verified-against.date} (newest first), and at most three are loaded. For each loaded
 * pattern, every request card that lists it under {@code patterns:} contributes its
 * {@code references:} ids to the block as well.
 */
@Component
public class ContextAssembler {

    /** Loads at most this many patterns per turn. */
    static final int MAX_PATTERNS = 3;

    /** Total block size cap, documented here and in the issue: beyond this, later entries are dropped whole, never truncated mid-entry. */
    static final int MAX_BLOCK_CHARS = 12_000;

    static final String HEADER =
            "## Established patterns (owner's memory)\n\n" +
            "These are the owner's established patterns; apply them, do not redesign; " +
            "say so if one does not fit.\n\n";

    private final PatternCatalog catalog;
    private final Path workspace;
    private final AgentProperties props;

    public ContextAssembler(PatternCatalog catalog, Path agentWorkspace, AgentProperties props) {
        this.catalog = catalog;
        this.workspace = agentWorkspace;
        this.props = props;
    }

    /** One pattern the block drew from, for {@code AuditLogger.patternLoaded}. */
    public record LoadedPattern(String id, String version) {
    }

    public record Assembled(String block, List<LoadedPattern> loadedPatterns) {
    }

    public Optional<Assembled> assemble(String userInput) {
        if (!props.getMemory().isEnabled() || userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        List<PatternManifest> all = catalog.patterns();
        if (all.isEmpty()) {
            return Optional.empty();
        }

        String inputLower = userInput.toLowerCase(Locale.ROOT);
        // One walk of the workspace per turn, shared by every pattern's signatures — not one
        // per signature. Never reaches outside agent.workspace, and a tree that cannot be
        // walked (an unreadable subdirectory, a vanished file) costs the signature hits for
        // this turn, never the turn itself.
        List<Path> workspaceFiles = workspaceFiles();
        List<Scored> scored = new ArrayList<>();
        for (PatternManifest p : all) {
            int hits = triggerHits(p, inputLower) + signatureHits(p, workspaceFiles);
            if (hits > 0) {
                scored.add(new Scored(p, hits));
            }
        }
        if (scored.isEmpty()) {
            return Optional.empty();
        }

        scored.sort(Comparator
                .comparingInt((Scored s) -> s.hits).reversed()
                .thenComparing((Scored s) -> s.pattern.verifiedAgainstDate() == null ? LocalDate.MIN : s.pattern.verifiedAgainstDate(),
                        Comparator.reverseOrder()));

        List<PatternManifest> top = scored.stream().map(s -> s.pattern).limit(MAX_PATTERNS).toList();

        StringBuilder block = new StringBuilder(HEADER);
        List<LoadedPattern> loaded = new ArrayList<>();
        Set<String> referenceIds = new LinkedHashSet<>();

        for (PatternManifest p : top) {
            String entry = renderPattern(p);
            if (block.length() + entry.length() > MAX_BLOCK_CHARS) {
                // Dropped whole, never truncated mid-entry — a smaller pattern further down
                // the ranking may still fit the remaining budget.
                continue;
            }
            block.append(entry);
            loaded.add(new LoadedPattern(p.id(), p.version()));
            for (RequestCard r : catalog.requests()) {
                if (r.patterns().contains(p.id())) {
                    referenceIds.addAll(r.references());
                }
            }
        }

        if (loaded.isEmpty()) {
            return Optional.empty();
        }

        for (String refId : referenceIds) {
            catalog.references().stream()
                    .filter(r -> r.id().equals(refId))
                    .findFirst()
                    .ifPresent(ref -> {
                        String entry = renderReference(ref);
                        if (block.length() + entry.length() <= MAX_BLOCK_CHARS) {
                            block.append(entry);
                        }
                    });
        }

        return Optional.of(new Assembled(block.toString(), List.copyOf(loaded)));
    }

    private int triggerHits(PatternManifest p, String inputLower) {
        return (int) p.triggers().stream()
                .filter(t -> t != null && !t.isBlank() && inputLower.contains(t.toLowerCase(Locale.ROOT)))
                .count();
    }

    private int signatureHits(PatternManifest p, List<Path> workspaceFiles) {
        if (workspaceFiles.isEmpty()) {
            return 0;
        }
        return (int) p.signatures().stream()
                .filter(sig -> matchesAny(sig, workspaceFiles))
                .count();
    }

    /** Regular files under the workspace, relative to it; empty when the walk fails for any reason. */
    private List<Path> workspaceFiles() {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(workspace)) {
            return walk.filter(Files::isRegularFile)
                    .map(workspace::relativize)
                    .toList();
        } catch (IOException | UncheckedIOException | SecurityException e) {
            // Files.walk reports a failure met mid-tree as UncheckedIOException from the
            // stream, not as IOException from the call — both land here.
            return List.of();
        }
    }

    private boolean matchesAny(String signature, List<Path> relativeFiles) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + signature);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return false; // a malformed glob in one manifest must not fail the turn
        }
        return relativeFiles.stream().anyMatch(matcher::matches);
    }

    private String renderPattern(PatternManifest p) {
        StringBuilder sb = new StringBuilder();
        sb.append("### pattern:").append(p.id()).append(" (v").append(p.version()).append(")\n\n");
        sb.append(p.patternMd()).append('\n');
        if (!p.skeletonFiles().isEmpty()) {
            sb.append("\nSkeleton files:\n");
            for (String f : p.skeletonFiles()) {
                sb.append("- ").append(f).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderReference(ReferenceCard r) {
        StringBuilder sb = new StringBuilder();
        sb.append("### reference:").append(r.id()).append(" (v").append(r.version()).append(")\n\n");
        for (String fact : r.holds()) {
            sb.append("- ").append(fact).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private record Scored(PatternManifest pattern, int hits) {
    }
}
