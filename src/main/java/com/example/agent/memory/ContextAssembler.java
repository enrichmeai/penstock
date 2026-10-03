package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the single labelled context block {@code AgentService.runTurn} prepends to the system
 * prompt for a turn (CLAUDE.md "retrieve before reason", #73; "recall in the turn", #88), from
 * the cards {@link PatternCatalog} holds. Selection is deterministic, never a model call or an
 * embedding (#85 invariant 4):
 *
 * <ul>
 *   <li><b>Facts</b> (#87): an active fact is selected when one of its {@code triggers} occurs
 *       in the input as a whole word (case-insensitive), or when its scope is
 *       {@code project:<p>} for the workspace's project and its kind is one a turn there will
 *       need anyway ({@code account}, {@code identifier}, {@code location-of-secret}). At most
 *       {@code agent.memory.max-facts}, by confidence then {@code last_confirmed}, newest first.
 *       A superseded fact is never selected.</li>
 *   <li><b>Episodes</b> (#86): the latest {@code agent.memory.max-episodes} whose
 *       {@code project} is the workspace's project; only asked, decided, refused and open are
 *       rendered.</li>
 *   <li><b>Patterns</b>: unchanged from #73 — trigger overlap plus {@code signatures} globs
 *       against {@code agent.workspace}, ranked by hit count then {@code verified-against.date},
 *       at most three, each bringing the references of the requests that list it.</li>
 * </ul>
 *
 * <p>Order in the block is facts, episodes, patterns, references. One cap applies to the whole
 * block; an entry that would cross it is dropped whole, never truncated, so the patterns at
 * the end are the first to go when the block is full — facts are the cheapest and most often
 * decisive. The workspace's project is {@code agent.memory.project}, or derived once at
 * startup from the workspace directory name through {@code agent.memory.project-aliases}.
 */
@Component
public class ContextAssembler {

    /** Loads at most this many patterns per turn. */
    static final int MAX_PATTERNS = 3;

    /** Total block size cap, documented here and in the issue: beyond this, later entries are dropped whole, never truncated mid-entry. */
    static final int MAX_BLOCK_CHARS = 12_000;

    static final String HEADER =
            "## Owner's memory\n\n" +
            "Established facts, recent episodes and patterns for this owner and project; " +
            "apply them, do not redesign, and say when you depart from one.\n\n";

    /** The fact kinds a turn in the project will need whether or not the input names them (#88). */
    static final Set<String> ALWAYS_NEEDED_KINDS = Set.of("account", "identifier", "location-of-secret");

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private final PatternCatalog catalog;
    private final Path workspace;
    private final AgentProperties props;
    private final String project;

    public ContextAssembler(PatternCatalog catalog, Path agentWorkspace, AgentProperties props) {
        this.catalog = catalog;
        this.workspace = agentWorkspace;
        this.props = props;
        this.project = resolveProject(agentWorkspace, props);
        if (props.getMemory().isEnabled()) {
            log.info("memory project={} (from {})", project.isEmpty() ? "<none>" : project,
                    props.getMemory().getProject() == null || props.getMemory().getProject().isBlank()
                            ? "the workspace directory name" : "agent.memory.project");
        }
    }

    /**
     * {@code agent.memory.project} when set; otherwise the workspace directory's name mapped
     * through {@code agent.memory.project-aliases}, or that name itself when it is not an
     * alias key. Empty (no project-scoped recall) when neither yields anything.
     */
    static String resolveProject(Path workspace, AgentProperties props) {
        String explicit = props.getMemory().getProject();
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (workspace == null || workspace.getFileName() == null) {
            return "";
        }
        String dirName = workspace.getFileName().toString();
        return props.getMemory().getProjectAliases().getOrDefault(dirName, dirName);
    }

    /** The project this assembler recalls for; empty when none could be resolved. */
    public String project() {
        return project;
    }

    /** One pattern the block drew from, for {@code AuditLogger.patternLoaded}. */
    public record LoadedPattern(String id, String version) {
    }

    /** One fact the block drew from, for {@code AuditLogger.factLoaded}. */
    public record LoadedFact(String id, String status, double confidence) {
    }

    /** One episode the block drew from, for {@code AuditLogger.episodeLoaded}. */
    public record LoadedEpisode(String id, String date) {
    }

    public record Assembled(String block, List<LoadedFact> loadedFacts, List<LoadedEpisode> loadedEpisodes,
                            List<LoadedPattern> loadedPatterns) {
    }

    public Optional<Assembled> assemble(String userInput) {
        if (!props.getMemory().isEnabled() || userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        String inputLower = userInput.toLowerCase(Locale.ROOT);
        List<FactCard> facts = selectFacts(inputLower);
        List<EpisodeCard> episodes = selectEpisodes();
        List<PatternManifest> top = rankPatterns(inputLower);
        if (facts.isEmpty() && episodes.isEmpty() && top.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder block = new StringBuilder(HEADER);
        List<LoadedFact> loadedFacts = new ArrayList<>();
        List<LoadedEpisode> loadedEpisodes = new ArrayList<>();
        List<LoadedPattern> loaded = new ArrayList<>();
        Set<String> referenceIds = new LinkedHashSet<>();

        // Facts first, then episodes, then patterns: with one cap over the whole block and
        // entries dropped whole from the end, patterns are the first to go when it is full.
        String factsHeading = "### facts\n\n";
        for (FactCard f : facts) {
            String entry = (loadedFacts.isEmpty() ? factsHeading : "") + renderFact(f);
            if (block.length() + entry.length() + 1 > MAX_BLOCK_CHARS) {
                continue; // the heading rides the first fact that fits, so no empty section
            }
            block.append(entry);
            loadedFacts.add(new LoadedFact(f.id(), f.status(), f.confidence()));
        }
        if (!loadedFacts.isEmpty()) {
            block.append('\n');
        }
        for (EpisodeCard e : episodes) {
            String entry = renderEpisode(e);
            if (block.length() + entry.length() > MAX_BLOCK_CHARS) {
                continue;
            }
            block.append(entry);
            loadedEpisodes.add(new LoadedEpisode(e.id(), e.date().toString()));
        }
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

        if (loadedFacts.isEmpty() && loadedEpisodes.isEmpty() && loaded.isEmpty()) {
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

        return Optional.of(new Assembled(block.toString(), List.copyOf(loadedFacts), List.copyOf(loadedEpisodes),
                List.copyOf(loaded)));
    }

    private List<FactCard> selectFacts(String inputLower) {
        List<FactCard> selected = new ArrayList<>();
        String projectScope = project.isEmpty() ? null : "project:" + project;
        for (FactCard f : catalog.facts()) {
            if (!f.isActive()) {
                continue; // loaded, never selected: the record keeps what it used to believe
            }
            boolean byTrigger = f.triggers().stream().anyMatch(t -> wholeWord(t, inputLower));
            boolean byProject = projectScope != null && projectScope.equals(f.scope())
                    && ALWAYS_NEEDED_KINDS.contains(f.kind());
            if (byTrigger || byProject) {
                selected.add(f);
            }
        }
        selected.sort(Comparator
                .comparingDouble(FactCard::confidence).reversed()
                .thenComparing(f -> f.lastConfirmed() == null ? LocalDate.MIN : f.lastConfirmed(), Comparator.reverseOrder())
                .thenComparing(FactCard::id));
        int max = Math.max(0, props.getMemory().getMaxFacts());
        return selected.size() > max ? List.copyOf(selected.subList(0, max)) : List.copyOf(selected);
    }

    private List<EpisodeCard> selectEpisodes() {
        if (project.isEmpty()) {
            return List.of();
        }
        int max = Math.max(0, props.getMemory().getMaxEpisodes());
        return catalog.episodes().stream() // already newest first
                .filter(e -> project.equals(e.project()))
                .limit(max)
                .toList();
    }

    private List<PatternManifest> rankPatterns(String inputLower) {
        List<PatternManifest> all = catalog.patterns();
        if (all.isEmpty()) {
            return List.of();
        }
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
        scored.sort(Comparator
                .comparingInt((Scored s) -> s.hits).reversed()
                .thenComparing((Scored s) -> s.pattern.verifiedAgainstDate() == null ? LocalDate.MIN : s.pattern.verifiedAgainstDate(),
                        Comparator.reverseOrder()));
        return scored.stream().map(s -> s.pattern).limit(MAX_PATTERNS).toList();
    }

    /** Whole-word, case-insensitive: "console" matches "the console", not "consoled". */
    static boolean wholeWord(String trigger, String inputLower) {
        if (trigger == null || trigger.isBlank()) {
            return false;
        }
        String t = trigger.toLowerCase(Locale.ROOT).trim();
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(t) + "(?![\\p{L}\\p{N}])").matcher(inputLower).find();
    }

    private String renderFact(FactCard f) {
        StringBuilder sb = new StringBuilder();
        sb.append("- fact:").append(f.id()).append(" [").append(f.kind()).append(" · ").append(f.scope())
                .append(" · ").append(f.status()).append(' ').append(f.confidence());
        if (f.lastConfirmed() != null) {
            sb.append(" · confirmed ").append(f.lastConfirmed());
        }
        sb.append("] ").append(f.statement().strip()).append('\n');
        return sb.toString();
    }

    private String renderEpisode(EpisodeCard e) {
        StringBuilder sb = new StringBuilder();
        sb.append("### episode:").append(e.id()).append(" (").append(e.date()).append(")\n\n");
        if (!e.asked().isBlank()) {
            sb.append("Asked: ").append(e.asked().strip()).append('\n');
        }
        appendList(sb, "Decided", e.decided());
        appendList(sb, "Refused", e.refused());
        appendList(sb, "Open", e.open());
        sb.append('\n');
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (String item : items) {
            sb.append("- ").append(item.strip()).append('\n');
        }
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
        try (Stream<Path> walk = Files.walk(workspace, props.getMemory().getSignatureDepth())) {
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
