package com.example.agent.memory;

import com.example.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code patterns/*}/manifest.yaml, {@code requests/*.yaml}, {@code references/*.yaml},
 * {@code facts/*.yaml} (#87) and {@code episodes/*.yaml} (#86) under {@code agent.memory.root}
 * at startup, and again on a bounded interval so an edit to a
 * card is picked up without a restart. Invalid YAML is logged and skipped — never fatal, since
 * one bad card must not take retrieval down for every other one.
 *
 * <p>No-ops entirely (including no filesystem access) while {@code agent.memory.enabled} is
 * false — the off-by-default posture in {@link AgentProperties.Memory}.
 */
@Component
public class PatternCatalog {

    private static final Logger log = LoggerFactory.getLogger(PatternCatalog.class);
    /** A project section name: lower-case letters, digits and dashes, never a path. */
    private static final java.util.regex.Pattern PROJECT_NAME = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    /** The last refused project name, so the refusal is logged once and not on every reload. */
    private volatile String refusedProject;

    /** How often {@link #scheduledReload()} re-reads the cards — bounded, not configurable. */
    private static final long RELOAD_INTERVAL_MS = 30_000;

    private final AgentProperties props;

    private volatile List<PatternManifest> patterns = List.of();
    private volatile List<RequestCard> requests = List.of();
    private volatile List<ReferenceCard> references = List.of();
    private volatile List<FactCard> facts = List.of();
    private volatile List<EpisodeCard> episodes = List.of();
    private volatile List<SectionDecision> sectionDecisions = List.of();

    /** The audiences a project declares (#112): public is seen by everyone, private by the owner. */
    private static final List<String> AUDIENCES = List.of("public", "private");
    /** What of estate/ a project loads: all of it, only shareable/public cards, or none. */
    private static final List<String> PERSONAL = List.of("all", "shareable", "none");
    private static final java.util.Set<String> SHAREABLE = java.util.Set.of("shareable", "public");

    /** A folder to load, and the card visibilities allowed from it (null: every card). */
    private record Section(Path dir, java.util.Set<String> visibilities) {
        boolean admits(Map<String, Object> card) {
            // format v1 cards carry a visibility; one without is treated as private
            return visibilities == null || visibilities.contains(String.valueOf(card.getOrDefault("visibility", "private")));
        }
    }

    /** One project as the root's memory.yaml declares it. */
    private record Project(String audience, String personal, List<String> uses) {
    }

    public PatternCatalog(AgentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void initialLoad() {
        reload();
    }

    /**
     * A catalogue read once, now, for the given properties: the MCP {@code recall} tool (#116)
     * builds one per call so an edited card or memory.yaml is picked up, and so a call for another
     * project applies that project's rules, not the configured one's.
     */
    public static PatternCatalog loadedNow(AgentProperties props) {
        PatternCatalog catalog = new PatternCatalog(props);
        catalog.reload();
        return catalog;
    }

    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    void scheduledReload() {
        reload();
    }

    public List<PatternManifest> patterns() {
        return patterns;
    }

    public List<RequestCard> requests() {
        return requests;
    }

    public List<ReferenceCard> references() {
        return references;
    }

    /** Every parsed fact, superseded ones included; {@link ContextAssembler} filters on status. */
    public List<FactCard> facts() {
        return facts;
    }

    /** Every parsed episode, newest first. */
    public List<EpisodeCard> episodes() {
        return episodes;
    }

    /** One section of a sectioned root, loaded or refused, and why (#112). */
    public record SectionDecision(String section, boolean loaded, String reason) {
    }

    /**
     * What the last reload loaded and refused, in order: the estate, the project's own section,
     * then each project it uses. Empty when the root declares no projects (#112).
     */
    public List<SectionDecision> sectionDecisions() {
        return sectionDecisions;
    }

    /** For tests in this package: the properties this catalogue reads on every reload. */
    AgentProperties props() {
        return props;
    }

    private synchronized void reload() {
        if (!props.getMemory().isEnabled()) {
            return;
        }
        Path root = Paths.get(props.getMemory().getRoot());
        List<PatternManifest> ps = new ArrayList<>();
        List<RequestCard> rs = new ArrayList<>();
        List<ReferenceCard> refs = new ArrayList<>();
        List<FactCard> fs = new ArrayList<>();
        List<EpisodeCard> es = new ArrayList<>();
        List<SectionDecision> decisions = new ArrayList<>();
        for (Section section : sections(root, decisions)) {
            ps.addAll(loadPatterns(section, section.dir().resolve("patterns")));
            rs.addAll(loadRequests(section, section.dir().resolve("requests")));
            refs.addAll(loadReferences(section, section.dir().resolve("references")));
            fs.addAll(loadFacts(section, section.dir().resolve("facts")));
            es.addAll(loadEpisodes(section, section.dir().resolve("episodes")));
        }
        fs.sort(Comparator.comparing(FactCard::id));
        es.sort(Comparator.comparing(EpisodeCard::date).thenComparing(EpisodeCard::id).reversed());
        patterns = List.copyOf(ps);
        requests = List.copyOf(rs);
        references = List.copyOf(refs);
        facts = List.copyOf(fs);
        episodes = List.copyOf(es);
        sectionDecisions = List.copyOf(decisions);
    }

    /**
     * The folders that hold cards (#91). A flat root (a repository's own folders) is itself the one
     * section. A sectioned root (it has {@code projects/}) contributes {@code estate/} plus
     * {@code projects/<project>/} for the workspace's project, never another project's section:
     * a valuedocs session does not load cistern's cards. With no project resolved, the estate only.
     *
     * <p>When the root's memory.yaml declares {@code projects:} (#112), a card loads into a project
     * only if everyone who sees that project's output may see it: the project's own section; each
     * project in its {@code uses} whose audience is at least as wide (a public project never loads
     * a private one); and of the estate what its {@code personal} allows (default: every card for a
     * private project, only shareable and public cards for a public one; {@code none}: nothing).
     * A project the block does not declare gets the estate only. Each decision is recorded.
     */
    private List<Section> sections(Path root, List<SectionDecision> decisions) {
        if (!Files.isDirectory(root.resolve("projects"))) {
            return List.of(new Section(root, null));
        }
        String workspace = props.getWorkspace();
        String project = ContextAssembler.resolveProject(
                workspace == null || workspace.isBlank() ? null : Paths.get(workspace).toAbsolutePath().normalize(), props);
        boolean validName = PROJECT_NAME.matcher(project).matches();
        if (!validName && !project.isEmpty() && !project.equals(refusedProject)) {
            refusedProject = project;
            log.warn("Project name '{}' is not a plain folder name; loading the estate section only", project);
        }
        Map<String, Project> declared = declaredProjects(root);
        List<Section> out = new ArrayList<>();
        if (declared.isEmpty()) {
            out.add(new Section(root.resolve("estate"), null));
            if (validName) {
                out.add(new Section(root.resolve("projects").resolve(project), null));
            }
            return out;
        }
        Project self = validName ? declared.get(project) : null;
        if (self == null) {
            out.add(new Section(root.resolve("estate"), null));
            decisions.add(new SectionDecision("estate", true, "personal: all (project "
                    + (project.isEmpty() ? "<none>" : project) + " is not declared in memory.yaml)"));
            if (validName) {
                decisions.add(new SectionDecision("projects/" + project, false, "not declared in memory.yaml"));
            }
            return out;
        }
        String personal = self.personal() != null ? self.personal()
                : "public".equals(self.audience()) ? "shareable" : "all";
        switch (personal) {
            case "none" -> decisions.add(new SectionDecision("estate", false, "personal: none"));
            case "shareable" -> {
                out.add(new Section(root.resolve("estate"), SHAREABLE));
                decisions.add(new SectionDecision("estate", true, "personal: shareable (shareable and public cards only)"));
            }
            default -> {
                out.add(new Section(root.resolve("estate"), null));
                decisions.add(new SectionDecision("estate", true, "personal: all"));
            }
        }
        out.add(new Section(root.resolve("projects").resolve(project), null));
        decisions.add(new SectionDecision("projects/" + project, true, "own (audience " + self.audience() + ")"));
        for (String used : self.uses()) {
            Project target = declared.get(used);
            if (used.equals(project)) {
                continue;
            }
            if (target == null || !PROJECT_NAME.matcher(used).matches()) {
                decisions.add(new SectionDecision("projects/" + used, false, "not declared in memory.yaml"));
            } else if ("private".equals(target.audience()) && "public".equals(self.audience())) {
                decisions.add(new SectionDecision("projects/" + used, false,
                        "audience private is narrower than this project's public audience"));
            } else {
                out.add(new Section(root.resolve("projects").resolve(used), null));
                decisions.add(new SectionDecision("projects/" + used, true, "uses (audience " + target.audience() + ")"));
            }
        }
        return out;
    }

    /**
     * The root memory.yaml's {@code projects:} block (#112), name to project. Empty when the file or
     * the block is missing; an entry with an unknown audience or personal value is logged and left
     * out, so it gets the estate only rather than a guess.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Project> declaredProjects(Path root) {
        Path file = root.resolve("memory.yaml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Object block;
        try {
            block = readYamlMapping(file).get("projects");
        } catch (Exception e) {
            log.warn("Could not read {}: {}; loading as if no projects were declared", file, e.getMessage());
            return Map.of();
        }
        if (!(block instanceof Map)) {
            return Map.of();
        }
        Map<String, Project> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) block).entrySet()) {
            String name = String.valueOf(e.getKey());
            Map<String, Object> entry = e.getValue() instanceof Map ? (Map<String, Object>) e.getValue() : Map.of();
            String audience = stringify(entry.get("audience"));
            Object personal = entry.get("personal");
            if (!PROJECT_NAME.matcher(name).matches() || !AUDIENCES.contains(audience)
                    || (personal != null && !PERSONAL.contains(personal.toString()))) {
                log.warn("memory.yaml project '{}' is invalid (audience {} / personal {}); it is treated as undeclared",
                        name, audience.isEmpty() ? "<none>" : audience, personal);
                continue;
            }
            out.put(name, new Project(audience, personal == null ? null : personal.toString(), stringList(entry.get("uses"))));
        }
        return out;
    }

    private List<FactCard> loadFacts(Section section, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FactCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
                    if (!section.admits(data)) {
                        continue;
                    }
                    String id = requireString(data, "id", file);
                    String statement = requireString(data, "statement", file);
                    double confidence = data.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
                    result.add(new FactCard(id, statement, stringify(data.get("kind")), stringify(data.get("subject")),
                            stringify(data.get("scope")), stringify(data.get("status")), confidence,
                            toLocalDate(data.get("last_confirmed")), stringList(data.get("triggers"))));
                } catch (Exception e) {
                    log.warn("Skipping invalid fact card {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list facts directory {}: {}", dir, e.getMessage());
        }
        result.sort(Comparator.comparing(FactCard::id));
        return List.copyOf(result);
    }

    private List<EpisodeCard> loadEpisodes(Section section, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<EpisodeCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
                    if (!section.admits(data)) {
                        continue;
                    }
                    String id = requireString(data, "id", file);
                    LocalDate date = toLocalDate(data.get("date"));
                    if (date == null) {
                        throw new IOException(file + " has no 'date'");
                    }
                    result.add(new EpisodeCard(id, date, stringify(data.get("project")), stringify(data.get("asked")),
                            whatWhyList(data.get("decided")), whatWhyList(data.get("refused")), stringList(data.get("open"))));
                } catch (Exception e) {
                    log.warn("Skipping invalid episode card {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list episodes directory {}: {}", dir, e.getMessage());
        }
        // newest first; the id (date-slug) breaks ties within a day deterministically
        result.sort(Comparator.comparing(EpisodeCard::date).thenComparing(EpisodeCard::id).reversed());
        return List.copyOf(result);
    }

    /** A {@code what:}/{@code why:} entry renders as "what — why"; a plain string stays as is. */
    @SuppressWarnings("unchecked")
    private List<String> whatWhyList(Object v) {
        if (!(v instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o instanceof Map<?, ?> m) {
                String what = stringify(m.get("what"));
                String why = stringify(m.get("why"));
                if (!what.isBlank()) {
                    out.add(why.isBlank() ? what : what + " — " + why);
                }
            } else if (o != null && !o.toString().isBlank()) {
                out.add(o.toString());
            }
        }
        return List.copyOf(out);
    }

    private LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.trim());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private List<PatternManifest> loadPatterns(Section section, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<PatternManifest> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path patternDir : entries) {
                Path manifestFile = patternDir.resolve("manifest.yaml");
                if (!Files.isRegularFile(manifestFile)) {
                    continue;
                }
                try {
                    if (!section.admits(readYamlMapping(manifestFile))) {
                        continue;
                    }
                    result.add(readManifest(patternDir, manifestFile));
                } catch (Exception e) {
                    log.warn("Skipping invalid pattern manifest {}: {}", manifestFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list patterns directory {}: {}", dir, e.getMessage());
        }
        return List.copyOf(result);
    }

    private PatternManifest readManifest(Path patternDir, Path manifestFile) throws IOException {
        Map<String, Object> data = readYamlMapping(manifestFile);
        String id = requireString(data, "id", manifestFile);
        String version = stringify(data.get("version"));
        List<String> triggers = stringList(data.get("triggers"));
        List<String> signatures = stringList(data.get("signatures"));
        LocalDate verifiedDate = verifiedAgainstDate(data.get("verified-against"));
        String patternMd = readIfPresent(patternDir.resolve("PATTERN.md"));
        List<String> skeletonFiles = listSkeletonFiles(patternDir.resolve("skeleton"));
        return new PatternManifest(id, version, triggers, signatures, verifiedDate, patternMd, skeletonFiles);
    }

    @SuppressWarnings("unchecked")
    private LocalDate verifiedAgainstDate(Object verifiedAgainst) {
        if (!(verifiedAgainst instanceof Map)) {
            return null;
        }
        Object date = ((Map<String, Object>) verifiedAgainst).get("date");
        if (date instanceof LocalDate ld) {
            return ld;
        }
        if (date instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (date instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.trim());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String readIfPresent(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            return "";
        }
    }

    private List<String> listSkeletonFiles(Path skeletonDir) {
        if (!Files.isDirectory(skeletonDir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> walk = Files.list(skeletonDir)) {
            List<String> names = walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            return names;
        } catch (IOException e) {
            log.warn("Failed to list skeleton directory {}: {}", skeletonDir, e.getMessage());
            return List.of();
        }
    }

    private List<RequestCard> loadRequests(Section section, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<RequestCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
                    if (!section.admits(data)) {
                        continue;
                    }
                    String id = requireString(data, "id", file);
                    result.add(new RequestCard(id, stringList(data.get("references")), stringList(data.get("patterns"))));
                } catch (Exception e) {
                    log.warn("Skipping invalid request card {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list requests directory {}: {}", dir, e.getMessage());
        }
        result.sort(Comparator.comparing(RequestCard::id));
        return List.copyOf(result);
    }

    private List<ReferenceCard> loadReferences(Section section, Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ReferenceCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
                    if (!section.admits(data)) {
                        continue;
                    }
                    String id = requireString(data, "id", file);
                    result.add(new ReferenceCard(id, stringify(data.get("version")), stringList(data.get("holds"))));
                } catch (Exception e) {
                    log.warn("Skipping invalid reference card {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list references directory {}: {}", dir, e.getMessage());
        }
        result.sort(Comparator.comparing(ReferenceCard::id));
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMapping(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new IOException(file + " did not parse to a YAML mapping");
            }
            return (Map<String, Object>) loaded;
        }
    }

    private String requireString(Map<String, Object> data, String key, Path file) throws IOException {
        Object v = data.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IOException(file + " has no '" + key + "'");
        }
        return v.toString();
    }

    private String stringify(Object v) {
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object v) {
        if (!(v instanceof List)) {
            return List.of();
        }
        List<Object> raw = (List<Object>) v;
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return List.copyOf(out);
    }
}
