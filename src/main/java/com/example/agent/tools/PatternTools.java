package com.example.agent.tools;

import com.example.agent.memory.PatternCatalog;
import com.example.agent.memory.PatternManifest;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AuditLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-demand skills (#175): {@code list_patterns} and {@code load_pattern}, so the model can pull a
 * pattern part-way through a task instead of relying only on the trigger words recall matched
 * before the first call (#88). Both read {@link PatternCatalog#patterns()}, which already holds
 * only what this session's project may see under its audience and sections (#112), so a pattern
 * outside the audience is neither listed nor loadable. Read-only: the agent never writes a
 * pattern; promotion stays {@code promote-pattern.sh}, by PR. Registered with
 * {@code agent.memory.enabled=true}, like recall.
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.memory", name = "enabled", havingValue = "true")
public class PatternTools {

    /**
     * UTF-8 bytes of one PATTERN.md returned by {@code load_pattern}; the rest is cut and says so.
     * Under {@code agent.tools.max-output-bytes} (16 384) with room for the header and file list, so
     * the registry's own head-and-tail truncation never cuts the note out.
     */
    static final int MAX_PATTERN_BYTES = 12_000;
    private static final int MAX_SUMMARY_CHARS = 160;
    /** Rows {@code list_patterns} returns; a larger catalogue says how many were left out. */
    static final int MAX_LISTED = 50;
    private static final int MAX_ECHOED_ID = 80;

    /** The catalogue's patterns, one per id: the first wins, as {@code load_pattern} resolves it. */
    static List<PatternManifest> distinctById(List<PatternManifest> patterns) {
        java.util.Map<String, PatternManifest> byId = new java.util.LinkedHashMap<>();
        for (PatternManifest p : patterns) byId.putIfAbsent(p.id(), p);
        return List.copyOf(byId.values());
    }

    /** The longest prefix of {@code s} that is at most {@code maxBytes} in UTF-8, cut on a character boundary. */
    static String utf8Prefix(String s, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) break;
            bytes += len;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    @Bean
    ListPatternsTool listPatternsTool(PatternCatalog catalog) {
        return new ListPatternsTool(catalog);
    }

    @Bean
    LoadPatternTool loadPatternTool(PatternCatalog catalog) {
        return new LoadPatternTool(catalog);
    }

    /** The first line of prose in PATTERN.md (not a heading), as a one-line summary. */
    static String summaryOf(PatternManifest p) {
        String md = p.patternMd() == null ? "" : p.patternMd();
        StringBuilder sb = new StringBuilder();
        for (String line : md.split("\n")) {
            String t = line.strip();
            if (t.startsWith("#")) {
                if (sb.length() > 0) break;
                continue;
            }
            if (t.isEmpty()) {
                if (sb.length() > 0) break;
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        String s = sb.toString();
        return s.length() <= MAX_SUMMARY_CHARS ? s : s.substring(0, MAX_SUMMARY_CHARS) + "…";
    }

    public static class ListPatternsTool implements Tool {
        private final PatternCatalog catalog;

        ListPatternsTool(PatternCatalog catalog) {
            this.catalog = catalog;
        }

        @Override public String name() { return "list_patterns"; }

        @Override public String description() {
            return "List the patterns (the owner's recorded ways of building things) this project may use: "
                    + "id, version and a one-line summary. Load one with load_pattern when a task matches it.";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context) {
            List<PatternManifest> patterns = distinctById(catalog.patterns());
            if (patterns.isEmpty()) {
                return ToolResult.ok(callId, "No patterns are available to this project.");
            }
            StringBuilder sb = new StringBuilder();
            for (PatternManifest p : patterns.subList(0, Math.min(MAX_LISTED, patterns.size()))) {
                sb.append("- ").append(p.id()).append(" (v").append(p.version()).append("): ")
                        .append(summaryOf(p)).append('\n');
            }
            if (patterns.size() > MAX_LISTED) {
                sb.append("(").append(patterns.size() - MAX_LISTED).append(" more not listed)\n");
            }
            return ToolResult.ok(callId, sb.toString().strip());
        }
    }

    public static class LoadPatternTool implements Tool {
        private final PatternCatalog catalog;
        private AuditLogger auditLogger;

        LoadPatternTool(PatternCatalog catalog) {
            this.catalog = catalog;
        }

        @Autowired(required = false)
        public void setAuditLogger(AuditLogger auditLogger) {
            this.auditLogger = auditLogger;
        }

        @Override public String name() { return "load_pattern"; }

        @Override public String description() {
            return "Load one pattern by id (from list_patterns): its PATTERN.md and the list of its skeleton files. "
                    + "Follow it as the owner's recorded way of building that kind of thing.";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "string", "description", "The pattern id, as list_patterns shows it.")),
                    "required", List.of("id"));
        }

        @Override
        public ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context) {
            Object raw = arguments == null ? null : arguments.get("id");
            if (!(raw instanceof String id) || id.isBlank()) {
                return ToolResult.error(callId, "load_pattern needs an 'id' string; list_patterns shows them.");
            }
            Optional<PatternManifest> found = catalog.patterns().stream().filter(p -> p.id().equals(id.trim())).findFirst();
            if (found.isEmpty()) {
                // Unknown and out-of-audience read the same, so the answer does not reveal what exists elsewhere.
                String shown = id.trim().length() <= MAX_ECHOED_ID ? id.trim() : id.trim().substring(0, MAX_ECHOED_ID) + "…";
                return ToolResult.error(callId, "Refused: no pattern '" + shown + "' is available to this project. "
                        + "list_patterns shows the ones that are.");
            }
            PatternManifest p = found.get();
            if (auditLogger != null) {
                auditLogger.patternLoaded(context.userId(), context.sessionId(), p.id(), p.version(), "on-demand");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Pattern ").append(p.id()).append(" (v").append(p.version()).append(")\n\n");
            String md = p.patternMd() == null ? "" : p.patternMd();
            int mdBytes = md.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (mdBytes > MAX_PATTERN_BYTES) {
                sb.append(utf8Prefix(md, MAX_PATTERN_BYTES)).append("\n\n[PATTERN.md cut at ").append(MAX_PATTERN_BYTES)
                        .append(" bytes of ").append(mdBytes).append("]\n");
            } else {
                sb.append(md).append('\n');
            }
            if (!p.skeletonFiles().isEmpty()) {
                sb.append("\nSkeleton files (in its skeleton/ folder):\n");
                p.skeletonFiles().stream().limit(MAX_LISTED).forEach(f -> sb.append("- ").append(f).append('\n'));
                if (p.skeletonFiles().size() > MAX_LISTED) {
                    sb.append("(").append(p.skeletonFiles().size() - MAX_LISTED).append(" more files)\n");
                }
            }
            return ToolResult.ok(callId, sb.toString().strip());
        }
    }
}
