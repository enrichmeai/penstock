package com.example.agent.mcp;

import com.example.agent.config.AgentProperties;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The MCP {@code draft_episode} tool (#116): one new episode file, in memory format v1, in the
 * memory root's episodes folder for the project, for the owner to review and commit. It never
 * overwrites, never writes anything but episodes, and refuses the whole draft when a line looks
 * like a credential. The layout and visibility follow {@code scripts/write-episode.sh --root}.
 */
public class EpisodeDrafts {

    /** A project or slug: lower-case letters, digits and dashes, never a path. */
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    /** The projects of a root that declares none (#91's closed list). */
    static final List<String> DEFAULT_PROJECTS = List.of("penstock", "cistern", "valuedocs", "site");
    private static final Set<String> VISIBILITIES = Set.of("private", "shareable", "public");

    private final AgentProperties props;

    public EpisodeDrafts(AgentProperties props) {
        this.props = props;
    }

    /** The outcome: the path written (relative to the root), or why nothing was. */
    public record Result(boolean written, String message) {
    }

    public Result draft(Map<String, Object> args) {
        String project = text(args.get("project"));
        String slug = text(args.get("slug"));
        String asked = text(args.get("asked"));
        if (!"estate".equals(project) && !NAME.matcher(project).matches()) {
            return refused("project must be estate or a project name ([a-z0-9-]), got '" + project + "'");
        }
        if (!NAME.matcher(slug).matches()) {
            return refused("slug must be [a-z0-9-], starting with a letter or digit");
        }
        if (asked.isBlank()) {
            return refused("asked is required: what the work was for");
        }
        Path root = Paths.get(props.getMemory().getRoot()).toAbsolutePath().normalize();
        Map<String, Object> config = memoryYaml(root);
        boolean sectioned = Files.isDirectory(root.resolve("projects"));
        if (!"estate".equals(project) && !declared(config).contains(project)) {
            return refused("project '" + project + "' is not declared in this root's memory.yaml");
        }
        if (!sectioned && "estate".equals(project)) {
            return refused("this memory root is flat; estate episodes need a sectioned root");
        }
        List<Map<String, String>> decided;
        List<Map<String, String>> refusedItems;
        try {
            decided = whatWhy(args.get("decided"), "decided");
            refusedItems = whatWhy(args.get("refused"), "refused");
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        List<String> learned = strings(args.get("learned"));
        List<String> open = strings(args.get("open"));

        String date = LocalDate.now(ZoneOffset.UTC).toString();
        String id = date + "-" + slug;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", id);
        card.put("format", 1);
        card.put("visibility", visibility(config, sectioned, project));
        card.put("date", date);
        card.put("project", project);
        card.put("asked", asked);
        card.put("built", List.of());
        card.put("decided", decided);
        card.put("refused", refusedItems);
        card.put("learned", learned);
        card.put("open", open);
        // Scan what the caller sent, line by line, before anything is rendered: YAML may fold a long
        // line, and a value split from its key would pass a scan of the rendered text.
        List<String> raw = new ArrayList<>(List.of(asked));
        decided.forEach(m -> raw.addAll(m.values()));
        refusedItems.forEach(m -> raw.addAll(m.values()));
        raw.addAll(learned);
        raw.addAll(open);
        for (String value : raw) {
            if (CredentialShape.firstMatchingLine(value) > 0) {
                return refused("an entry matches a credential shape; nothing was written. "
                        + "Name where a secret lives, never its value.");
            }
        }
        String yaml = "# Drafted through Penstock's MCP draft_episode tool (#116). Review it, edit it, and\n"
                + "# commit it, or delete it: nothing reads a draft until you commit it.\n" + dump(card);

        int line = CredentialShape.firstMatchingLine(yaml);
        if (line > 0) {
            return refused("line " + line + " of the draft matches a credential shape; nothing was written. "
                    + "Name where a secret lives, never its value.");
        }
        Path dir = sectioned
                ? ("estate".equals(project) ? root.resolve("estate/episodes") : root.resolve("projects").resolve(project).resolve("episodes"))
                : root.resolve("episodes");
        Path file = dir.resolve(id + ".yaml");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            return refused(root.relativize(file) + " already exists; a draft never overwrites. Choose another slug.");
        } catch (IOException e) {
            return refused("could not write the draft: " + e.getMessage());
        }
        return new Result(true, "Drafted " + root.relativize(file) + ". Review and commit it in the memory root; "
                + "until then nothing reads it.");
    }

    private static Result refused(String why) {
        return new Result(false, why);
    }

    /** The projects the root declares (#112), or the closed list when it declares none. */
    @SuppressWarnings("unchecked")
    static List<String> declared(Map<String, Object> config) {
        Object block = config.get("projects");
        if (block instanceof Map<?, ?> m && !m.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object k : ((Map<Object, Object>) m).keySet()) {
                names.add(String.valueOf(k));
            }
            return names;
        }
        return DEFAULT_PROJECTS;
    }

    /**
     * As write-episode.sh (write_episode_generate.py): the root's visibility only for the project the
     * root's memory.yaml names as its own; every other draft, and every draft in a root that names
     * none (a sectioned root), is private.
     */
    private static String visibility(Map<String, Object> config, boolean sectioned, String project) {
        String v = text(config.get("visibility"));
        if (!VISIBILITIES.contains(v)) {
            return "private";
        }
        return project.equals(text(config.get("project"))) ? v : "private";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> memoryYaml(Path root) {
        Path file = root.resolve("memory.yaml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        } catch (Exception e) {
            return Map.of(); // a malformed memory.yaml declares nothing; the draft stays private
        }
    }

    private static List<Map<String, String>> whatWhy(Object v, String field) {
        List<Map<String, String>> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list of { what, why }");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || text(m.get("what")).isBlank() || text(m.get("why")).isBlank()) {
                throw new IllegalArgumentException(field + " entries need both what and why");
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("what", text(m.get("what")));
            entry.put("why", text(m.get("why")));
            out.add(entry);
        }
        return out;
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static String dump(Map<String, Object> card) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndicatorIndent(0);
        options.setWidth(Integer.MAX_VALUE); // never fold: the file holds the lines that were scanned
        return new Yaml(options).dump(card);
    }
}
