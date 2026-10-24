package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The build's JUnit XML reports, summarised (#200): counts, then each failure and error with its
 * message and up to five frames outside the JDK and the test libraries, instead of the console log of
 * {@code gradle test}.
 *
 * <p>Reads {@code TEST-*.xml} under a {@code test-results} (Gradle), {@code surefire-reports} or
 * {@code failsafe-reports} (Maven) directory, inside the workspace only: the walk does not follow
 * symbolic links, and a file whose real path leaves the workspace is not read. Read-only, and off
 * when the file tools are. Gradle reports every exception as a failure; only Maven reports errors.
 */
@Component
public class TestResultsTool implements Tool {

    static final int MAX_FILES = 2000;
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_MESSAGE_CHARS = 500;
    static final int MAX_FRAMES = 5;
    static final int DEFAULT_FAILURES = 10;
    static final int MAX_FAILURES = 50;
    static final int MAX_FIELD_CHARS = 300;
    static final int MAX_OUTPUT_CHARS = 12_000;
    static final int MAX_DIRS = 20_000;

    private static final Set<String> REPORT_DIRS = Set.of("test-results", "surefire-reports", "failsafe-reports");
    /**
     * Not walked: version control, caches, dependency trees and compiled output, and resource
     * folders (a report under src/test/resources is a fixture, not a result).
     */
    private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", "node_modules", ".idea", ".venv", "venv",
            "__pycache__", ".next", "classes", "resources");
    /** Test runner and assertion library frames: not where the test failed. */
    private static final List<String> FRAMEWORK = List.of(
            "org.junit.", "junit.", "org.opentest4j.", "org.assertj.", "org.hamcrest.", "org.mockito.",
            "org.gradle.", "worker.org.gradle.", "org.apache.maven.", "org.springframework.test.");
    /** The JDK's own frames: left out, so its I/O and reflection layers do not fill the frames shown. */
    private static final List<String> JDK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private final Path workspace;
    private final AgentProperties.File cfg;

    public TestResultsTool(Path agentWorkspace, AgentProperties props) {
        this.workspace = agentWorkspace;
        this.cfg = props.getTools().getFile();
    }

    @Override public String name() { return "test_results"; }

    @Override public String description() {
        return "After a build, summarise its JUnit XML test reports (Gradle build/test-results, Maven "
                + "target/surefire-reports): how many tests ran, failed, errored and were skipped, then each "
                + "failure with its message and up to five frames outside the JDK and the test libraries. Shorter and more exact "
                + "than the console log. Optional `path` narrows the search to one module.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "Directory to search, relative to the workspace (default: the whole workspace)."),
                        "max_failures", Map.of("type", "integer",
                                "description", "Most failures to describe (default " + DEFAULT_FAILURES + ", at most " + MAX_FAILURES + ")."))
        );
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> args, ToolContext context) {
        try {
            if (!cfg.isEnabled()) return ToolResult.error(callId, "File tool is disabled.");
            Path root = workspace.toRealPath();
            Path base = args.get("path") == null ? root : WorkspacePath.resolve(root, String.valueOf(args.get("path")));
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || !base.toRealPath().startsWith(root)) {
                return ToolResult.error(callId, "Not a directory inside the workspace: " + args.get("path"));
            }
            int maxFailures = Math.max(1, Math.min(MAX_FAILURES, toInt(args.get("max_failures"), DEFAULT_FAILURES)));

            Found found = find(root, base);
            String stopped = found.stoppedAtDirs()
                    ? "Stopped after " + MAX_DIRS + " directories: pass `path` (a module) to search all of it.\n" : "";
            if (found.reports().isEmpty()) {
                return ToolResult.ok(callId, "No test reports under " + WorkspacePath.displayRelative(root, base)
                        + " (looked for TEST-*.xml in build/test-results and target/surefire-reports). "
                        + "Run the build's tests first.\n" + stopped);
            }
            return ToolResult.ok(callId, summarise(root, found.reports(), maxFailures) + stopped);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return ToolResult.error(callId, e.getMessage());
        }
    }

    record Found(List<Path> reports, boolean stoppedAtDirs) {}

    /** {@code TEST-*.xml} files below a report directory, never through a symbolic link. */
    static Found find(Path root, Path base) throws IOException {
        List<Path> found = new ArrayList<>();
        boolean[] stopped = {false};
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            int dirs;

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (++dirs > MAX_DIRS) {
                    stopped[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return !dir.equals(base) && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && name.startsWith("TEST-") && name.endsWith(".xml")
                        && underReportDir(root, file) && file.toRealPath().startsWith(root)) {
                    found.add(file);
                }
                return found.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return new Found(found, stopped[0]);
    }

    private static boolean underReportDir(Path root, Path file) {
        for (Path p : root.relativize(file.getParent())) {
            if (REPORT_DIRS.contains(p.toString())) return true;
        }
        return false;
    }

    private static String summarise(Path root, List<Path> reports, int maxFailures) {
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        long newest = 0;
        List<String> problems = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        DocumentBuilder parser = parser();
        for (Path report : reports) {
            String rel = WorkspacePath.displayRelative(root, report);
            Document doc;
            try {
                if (Files.size(report) > MAX_FILE_BYTES) {
                    unread.add(rel + " (larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB)");
                    continue;
                }
                if (!report.toRealPath().startsWith(root)) {
                    unread.add(rel + " (now outside the workspace)");
                    continue;
                }
                newest = Math.max(newest, Files.getLastModifiedTime(report).toMillis());
                try (InputStream in = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                    doc = parser.parse(in);
                }
            } catch (Exception e) {
                unread.add(rel + " (not a readable JUnit report)");
                continue;
            } finally {
                parser.reset();
            }
            // Every testcase counts, and no failure is dropped: two entries with one name are a retry
            // (Gradle's test-retry without mergeReruns) or two tests sharing a name (a parameterised
            // test, a @DisplayName), and a report cannot tell which. A later pass is said, not assumed.
            List<Element> cases = new ArrayList<>();
            NodeList all = doc.getElementsByTagName("testcase");
            for (int i = 0; i < all.getLength(); i++) cases.add((Element) all.item(i));
            Map<String, Integer> lastPass = new HashMap<>();
            for (int i = 0; i < cases.size(); i++) {
                Element tc = cases.get(i);
                if (child(tc, "failure") == null && child(tc, "error") == null && child(tc, "skipped") == null) {
                    lastPass.put(key(tc), i);
                }
            }
            for (int i = 0; i < cases.size(); i++) {
                Element tc = cases.get(i);
                tests++;
                Element failure = child(tc, "failure");
                Element error = child(tc, "error");
                String note = (failure != null || error != null) && lastPass.getOrDefault(key(tc), -1) > i
                        ? "  note: a later entry with the same name passed: a retry, or another test with this name\n" : "";
                if (failure != null) {
                    failures++;
                    problems.add(describe("FAILED", tc, failure) + note);
                } else if (error != null) {
                    errors++;
                    problems.add(describe("ERROR", tc, error) + note);
                } else if (child(tc, "skipped") != null) {
                    skipped++;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Tests: ").append(tests).append(", failed: ").append(failures)
                .append(", errors: ").append(errors).append(", skipped: ").append(skipped).append('\n');
        out.append("From ").append(reports.size()).append(reports.size() == 1 ? " report" : " reports");
        if (newest > 0) out.append(", newest written ").append(Instant.ofEpochMilli(newest));
        out.append(reports.size() >= MAX_FILES ? " (stopped at " + MAX_FILES + " reports)" : "").append('\n');
        int shown = 0;
        for (; shown < Math.min(maxFailures, problems.size()); shown++) {
            if (out.length() + problems.get(shown).length() > MAX_OUTPUT_CHARS) break;
            out.append('\n').append(problems.get(shown));
        }
        if (problems.size() > shown) {
            out.append("\n... and ").append(problems.size() - shown).append(" more not shown (")
                    .append(shown < maxFailures ? MAX_OUTPUT_CHARS + " characters" : "max_failures").append(")\n");
        }
        if (!unread.isEmpty()) {
            out.append("\nNot read (not counted above): ").append(cut(String.join("; ", unread.subList(0, Math.min(10, unread.size())))))
                    .append(unread.size() > 10 ? "; and " + (unread.size() - 10) + " more" : "").append('\n');
        }
        return out.toString();
    }

    private static String key(Element tc) {
        return tc.getAttribute("classname") + "\u0000" + tc.getAttribute("name");
    }

    /** {@code FAILED Class > test [Type]}, the message, the test's own top frames and the root cause. */
    static String describe(String kind, Element tc, Element problem) {
        String trace = problem.getTextContent() == null ? "" : problem.getTextContent();
        String message = problem.getAttribute("message");
        if (message.isBlank()) {
            message = trace.lines().findFirst().orElse("");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(kind).append(' ').append(cut(tc.getAttribute("classname"), MAX_FIELD_CHARS))
                .append(" > ").append(cut(tc.getAttribute("name"), MAX_FIELD_CHARS));
        if (!problem.getAttribute("type").isBlank()) sb.append("  [").append(cut(problem.getAttribute("type"), MAX_FIELD_CHARS)).append(']');
        sb.append('\n');
        if (!message.isBlank()) sb.append("  message: ").append(cut(oneLine(message))).append('\n');
        for (String frame : topFrames(trace)) {
            sb.append("  ").append(cut(frame, MAX_FIELD_CHARS)).append('\n');
        }
        String cause = null;
        // The main exception's chain only: a suppressed exception's causes are indented.
        for (String line : trace.lines().toList()) {
            if (line.startsWith("Caused by:")) cause = line.strip();
        }
        if (cause != null) sb.append("  root cause: ").append(cut(cause.substring("Caused by:".length()).strip())).append('\n');
        return sb.toString();
    }

    /**
     * Up to {@link #MAX_FRAMES} frames of the code under test: the JDK's frames are left out, the
     * runner's and assertion library's leading frames are skipped, and the next one of theirs ends
     * the list. A frame's class-loader and module prefix ({@code app//}, {@code java.base@21/}) is
     * dropped. When no frame qualifies, the first three are shown as they are.
     */
    static List<String> topFrames(String trace) {
        List<String> all = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        boolean started = false;
        for (String line : trace.lines().toList()) {
            String s = line.strip();
            if (s.startsWith("Caused by:") || s.startsWith("Suppressed:")) break;
            if (!s.startsWith("at ")) continue;
            String frame = withoutLoader(s.substring(3));
            all.add("at " + frame);
            if (JDK.stream().anyMatch(frame::startsWith)) continue;
            boolean framework = FRAMEWORK.stream().anyMatch(frame::startsWith);
            if (framework) {
                if (started) break;
                continue;
            }
            started = true;
            frames.add("at " + frame);
            if (frames.size() == MAX_FRAMES) break;
        }
        return frames.isEmpty() ? all.subList(0, Math.min(3, all.size())) : frames;
    }

    /**
     * {@code app//a.B.c(B.java:1)} and {@code java.base@21/a.B.c(...)} become {@code a.B.c(B.java:1)}:
     * up to two leading class-loader and module names, each ending in {@code /}. A hidden class's own
     * slash ({@code Foo$$Lambda/0x1a.run}) is kept: a loader or module name has no {@code $}, and the
     * part after a hidden class's slash starts with {@code 0x}.
     */
    static String withoutLoader(String frame) {
        String rest = frame;
        for (int i = 0; i < 2; i++) {
            int slash = rest.indexOf('/');
            int paren = rest.indexOf('(');
            if (slash < 0 || (paren >= 0 && slash > paren)) break;
            String name = rest.substring(0, slash);
            String after = rest.substring(slash + 1);
            if (name.contains("$") || after.startsWith("0x")) break;
            rest = after;
        }
        return rest;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    private static String cut(String s) {
        return cut(s, MAX_MESSAGE_CHARS);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + " ...";
    }

    /** No DOCTYPE, no external entities: a report is data, never a fetch. */
    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("no XML parser", e);
        }
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) try { return Integer.parseInt(s.strip()); } catch (NumberFormatException ignore) {}
        return def;
    }
}
