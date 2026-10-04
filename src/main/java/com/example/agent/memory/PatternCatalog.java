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

    /** How often {@link #scheduledReload()} re-reads the cards — bounded, not configurable. */
    private static final long RELOAD_INTERVAL_MS = 30_000;

    private final AgentProperties props;

    private volatile List<PatternManifest> patterns = List.of();
    private volatile List<RequestCard> requests = List.of();
    private volatile List<ReferenceCard> references = List.of();
    private volatile List<FactCard> facts = List.of();
    private volatile List<EpisodeCard> episodes = List.of();

    public PatternCatalog(AgentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void initialLoad() {
        reload();
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

    private synchronized void reload() {
        if (!props.getMemory().isEnabled()) {
            return;
        }
        Path root = Paths.get(props.getMemory().getRoot());
        patterns = loadPatterns(root.resolve("patterns"));
        requests = loadRequests(root.resolve("requests"));
        references = loadReferences(root.resolve("references"));
        facts = loadFacts(root.resolve("facts"));
        episodes = loadEpisodes(root.resolve("episodes"));
    }

    private List<FactCard> loadFacts(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FactCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
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

    private List<EpisodeCard> loadEpisodes(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<EpisodeCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
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

    private List<PatternManifest> loadPatterns(Path dir) {
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

    private List<RequestCard> loadRequests(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<RequestCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
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

    private List<ReferenceCard> loadReferences(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ReferenceCard> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : entries) {
                try {
                    Map<String, Object> data = readYamlMapping(file);
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
