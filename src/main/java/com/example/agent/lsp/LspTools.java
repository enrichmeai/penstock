package com.example.agent.lsp;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.WorkspacePath;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only tools over a language server (#190): what grep cannot tell, the compiler's view.
 * {@code diagnostics} (compile errors and warnings), {@code definition}, {@code references} and
 * {@code symbols}. Registered only when {@code agent.tools.lsp.enabled} is true and at least one
 * server has a command. Paths are the workspace's; a location outside it (a JDK class, a library
 * jar) is reported by file name only.
 */
@Configuration
@Conditional(LspTools.Configured.class)
public class LspTools {

    /** On, and at least one server with a command: otherwise the tools are absent. */
    static class Configured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            AgentProperties.Lsp lsp = Binder.get(context.getEnvironment())
                    .bind("agent.tools.lsp", AgentProperties.Lsp.class).orElse(null);
            return lsp != null && lsp.isEnabled()
                    && lsp.getServers().values().stream().anyMatch(s -> !s.getCommand().isEmpty() && !s.getExtensions().isEmpty());
        }
    }

    @Bean
    Tool lspDiagnosticsTool(LspManager lsp) {
        return new Diagnostics(lsp);
    }

    @Bean
    Tool lspDefinitionTool(LspManager lsp) {
        return new Lookup(lsp, true);
    }

    @Bean
    Tool lspReferencesTool(LspManager lsp) {
        return new Lookup(lsp, false);
    }

    @Bean
    Tool lspSymbolsTool(LspManager lsp) {
        return new Symbols(lsp);
    }

    private static final Map<String, Object> PATH = Map.of("type", "string", "description", "A file in the workspace.");
    private static final Map<String, Object> LINE = Map.of("type", "integer", "description", "1-based line.");
    private static final Map<String, Object> COLUMN = Map.of("type", "integer",
            "description", "1-based column of a character inside the symbol's name.");

    /** A workspace file the configured servers serve, or an error result naming why not. */
    private static Path served(LspManager lsp, Object path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        Path file = WorkspacePath.resolve(lsp.workspace(), String.valueOf(path));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no such file: " + path);
        }
        if (lsp.languageOf(file).isEmpty()) {
            throw new IllegalArgumentException("no language server serves " + file.getFileName()
                    + " (configured: " + String.join(", ", lsp.languages()) + ")");
        }
        return file;
    }

    private static int positive(Object v, String name) {
        try {
            int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (n < 1) {
                throw new IllegalArgumentException(name + " must be 1 or more");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got " + v);
        }
    }

    /** A location as {@code path:line:column}, workspace-relative; outside it, the file name only. */
    static String describe(Path workspace, String uri, JsonNode range) {
        String where;
        try {
            URI u = URI.create(uri);
            Path p = "file".equals(u.getScheme()) ? Paths.get(u).normalize() : null;
            if (p != null && p.startsWith(workspace)) {
                where = WorkspacePath.displayRelative(workspace, p);
            } else {
                String path = u.getPath() != null ? u.getPath() : u.getSchemeSpecificPart();
                String name = path == null ? uri : path.substring(path.lastIndexOf('/') + 1);
                return "(outside the workspace) " + name;
            }
        } catch (Exception e) {
            return "(outside the workspace) unreadable location";
        }
        if (range == null || range.isMissingNode() || range.isNull()) {
            return where;
        }
        JsonNode start = range.path("start");
        return where + ":" + (start.path("line").asInt() + 1) + ":" + (start.path("character").asInt() + 1);
    }

    /** Location, Location[], LocationLink[] or null, flattened. */
    static List<String> locations(Path workspace, JsonNode result) {
        List<String> out = new ArrayList<>();
        if (result == null || result.isNull()) {
            return out;
        }
        for (JsonNode l : result.isArray() ? result : List.of(result)) {
            if (l.has("targetUri")) {
                out.add(describe(workspace, l.get("targetUri").asText(), l.path("targetSelectionRange")));
            } else if (l.has("uri")) {
                out.add(describe(workspace, l.get("uri").asText(), l.path("range")));
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern ABSOLUTE_PATH = java.util.regex.Pattern.compile("/[^\\s'\"<>|:,;()]+");

    /** Absolute paths in server text: workspace-relative inside it, the file name only outside. */
    static String scrubPaths(Path workspace, String text) {
        java.util.regex.Matcher m = ABSOLUTE_PATH.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Path p = Paths.get(m.group()).normalize();
            String replacement = p.startsWith(workspace) ? WorkspacePath.displayRelative(workspace, p)
                    : String.valueOf(p.getFileName());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    static String bounded(List<String> lines, int max, String none) {
        if (lines.isEmpty()) {
            return none;
        }
        List<String> distinct = lines.stream().distinct().toList();
        StringBuilder sb = new StringBuilder();
        distinct.stream().limit(max).forEach(l -> sb.append(l).append('\n'));
        if (distinct.size() > max) {
            sb.append("(").append(distinct.size() - max).append(" more not shown)\n");
        }
        return sb.toString().strip();
    }

    static final class Diagnostics implements Tool {
        private final LspManager lsp;

        Diagnostics(LspManager lsp) { this.lsp = lsp; }

        @Override public String name() { return "diagnostics"; }

        @Override public String description() {
            return "Compile errors and warnings for a file, from its language server (the compiler's view, not a guess). "
                    + "Run it after editing a file, before building.";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of("path", PATH), "required", List.of("path"));
        }

        @Override
        public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
            try {
                Path file = served(lsp, args.get("path"));
                LspClient c = lsp.client(lsp.languageOf(file).orElseThrow());
                JsonNode ds = c.diagnostics(file, Duration.ofSeconds(lsp.props().getDiagnosticsWaitSeconds()));
                String rel = WorkspacePath.displayRelative(lsp.workspace(), file);
                if (ds == null) {
                    return ToolResult.ok(callId, "The language server published no diagnostics for " + rel
                            + " within " + lsp.props().getDiagnosticsWaitSeconds() + " s (it may still be indexing).");
                }
                List<String> lines = new ArrayList<>();
                for (JsonNode d : ds) {
                    String severity = switch (d.path("severity").asInt(1)) {
                        case 1 -> "error";
                        case 2 -> "warning";
                        case 3 -> "info";
                        default -> "hint";
                    };
                    JsonNode start = d.path("range").path("start");
                    lines.add(rel + ":" + (start.path("line").asInt() + 1) + ":" + (start.path("character").asInt() + 1)
                            + " " + severity + ": " + scrubPaths(lsp.workspace(), d.path("message").asText().replace('\n', ' ')));
                }
                return ToolResult.ok(callId, bounded(lines, lsp.props().getMaxResults(), rel + ": no errors or warnings."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error(callId, "interrupted");
            } catch (Exception e) {
                return ToolResult.error(callId, messageOf(e));
            }
        }
    }

    static final class Lookup implements Tool {
        private final LspManager lsp;
        private final boolean definition;

        Lookup(LspManager lsp, boolean definition) {
            this.lsp = lsp;
            this.definition = definition;
        }

        @Override public String name() { return definition ? "definition" : "references"; }

        @Override public String description() {
            return definition
                    ? "Where the symbol at a position is declared, as the compiler resolves it (the right overload, the real implementation)."
                    : "Where the symbol at a position is used, as the compiler resolves it (not a text match).";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of("path", PATH, "line", LINE, "column", COLUMN),
                    "required", List.of("path", "line", "column"));
        }

        @Override
        public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
            try {
                Path file = served(lsp, args.get("path"));
                int line = positive(args.get("line"), "line") - 1;
                int column = positive(args.get("column"), "column") - 1;
                LspClient c = lsp.client(lsp.languageOf(file).orElseThrow());
                JsonNode result = definition ? c.definition(file, line, column) : c.references(file, line, column);
                return ToolResult.ok(callId, bounded(locations(lsp.workspace(), result), lsp.props().getMaxResults(),
                        definition ? "No declaration found at that position." : "No uses found."));
            } catch (Exception e) {
                return ToolResult.error(callId, messageOf(e));
            }
        }
    }

    static final class Symbols implements Tool {
        private final LspManager lsp;

        Symbols(LspManager lsp) { this.lsp = lsp; }

        @Override public String name() { return "symbols"; }

        @Override public String description() {
            return "Find classes, methods and fields by name across the workspace, from the language server: "
                    + "name, kind and where each is declared.";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties",
                    Map.of("query", Map.of("type", "string", "description", "A name or part of one."),
                            "language", Map.of("type", "string", "description", "Which server to ask; default: the first configured.")),
                    "required", List.of("query"));
        }

        @Override
        public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
            try {
                String query = String.valueOf(args.getOrDefault("query", "")).trim();
                if (query.isEmpty()) {
                    return ToolResult.error(callId, "query is required");
                }
                String language = args.get("language") != null ? String.valueOf(args.get("language")) : lsp.languages().get(0);
                if (!lsp.languages().contains(language)) {
                    return ToolResult.error(callId, "no language server named " + language + " (configured: "
                            + String.join(", ", lsp.languages()) + ")");
                }
                JsonNode result = lsp.client(language).workspaceSymbols(query);
                List<String> lines = new ArrayList<>();
                if (result != null && result.isArray()) {
                    for (JsonNode s : result) {
                        JsonNode loc = s.path("location");
                        lines.add(s.path("name").asText() + " (" + kind(s.path("kind").asInt()) + ") "
                                + describe(lsp.workspace(), loc.path("uri").asText(), loc.path("range")));
                    }
                }
                return ToolResult.ok(callId, bounded(lines, lsp.props().getMaxResults(), "No symbols match " + query + "."));
            } catch (Exception e) {
                return ToolResult.error(callId, messageOf(e));
            }
        }

        private static String kind(int k) {
            return switch (k) {
                case 5 -> "class";
                case 6 -> "method";
                case 8 -> "field";
                case 9 -> "constructor";
                case 10 -> "enum";
                case 11 -> "interface";
                case 12 -> "function";
                case 13 -> "variable";
                case 14 -> "constant";
                case 23 -> "struct";
                default -> "symbol";
            };
        }
    }
}
