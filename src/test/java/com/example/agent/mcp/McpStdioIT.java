package com.example.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #116: the real application in {@code --mcp} stdio mode, driven over actual pipes the way Claude
 * Code or Claude Desktop launches a local MCP server (MCP 2025-11-25, basic/transports: one JSON-RPC
 * message per line on stdout, logs on stderr). The memory root is a placeholder fixture: oss-lib is
 * public and its memory.yaml {@code uses} names private product, so recall must keep product's card
 * and the owner's private estate card out, exactly as a Penstock turn would (#112).
 *
 * <p>RED on main: {@code --mcp} is not recognised, so the process starts as a web app, prints the
 * banner to stdout and never answers {@code initialize}.
 */
class McpStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;
    @TempDir
    Path workspace;

    private Process process;
    private OutputStream stdin;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final List<String> allLines = new CopyOnWriteArrayList<>();
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fixture() throws IOException {
        Files.writeString(root.resolve("memory.yaml"), """
                format: 1
                visibility: private
                projects:
                  oss-lib: { audience: public, uses: [product] }
                  product: { audience: private }
                  standalone: { audience: private, personal: none, uses: [oss-lib] }
                """);
        fact("estate", "owner-private-account", "estate", "private");
        fact("estate", "owner-review-habit", "estate", "shareable");
        fact("projects/oss-lib", "oss-lib-build", "project:oss-lib", "private");
        fact("projects/product", "product-customer", "project:product", "private");
        fact("projects/standalone", "standalone-plan", "project:standalone", "private");
        fact("projects/undeclared", "undeclared-note", "project:undeclared", "private");
    }

    private void fact(String section, String id, String scope, String visibility) throws IOException {
        Path dir = root.resolve(section).resolve("facts");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yaml"), "format: 1\nvisibility: " + visibility + "\nid: " + id
                + "\nstatement: " + id + " holds.\nkind: convention\nsubject: s/" + id + "\nscope: " + scope
                + "\nstatus: asserted\nconfidence: 1.0\nlast_confirmed: 2026-10-07\ntriggers: [zebra]\n");
    }

    @AfterEach
    void tearDown() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Test
    void initializeListsTheTwoToolsAndThePrompt() throws Exception {
        start();
        JsonNode init = initialize();
        assertThat(init.get("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(init.get("serverInfo").get("name").asText()).isEqualTo("penstock");
        assertThat(init.get("capabilities").has("tools")).isTrue();
        assertThat(init.get("capabilities").has("prompts")).isTrue();

        JsonNode tools = call(2, "tools/list", Map.of()).get("tools");
        assertThat(tools.findValuesAsText("name")).containsExactlyInAnyOrder("recall", "draft_episode");

        JsonNode prompts = call(3, "prompts/list", Map.of()).get("prompts");
        assertThat(prompts.findValuesAsText("name")).contains("start-with-memory");
        JsonNode prompt = call(4, "prompts/get", Map.of("name", "start-with-memory",
                "arguments", Map.of("task", "fix the zebra build")));
        assertThat(prompt.toString()).contains("recall").contains("fix the zebra build");

        assertEveryLineSoFarIsJson();
    }

    @Test
    void recallReturnsWhatTheRulesAllowAndAuditsTheRest() throws Exception {
        start();
        initialize();

        JsonNode result = call(2, "tools/call", Map.of("name", "recall",
                "arguments", Map.of("question", "How does the zebra build work?")));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        String text = result.get("content").get(0).get("text").asText();
        assertThat(text)
                .contains("oss-lib-build")
                .contains("owner-review-habit")
                .doesNotContain("owner-private-account")
                .doesNotContain("product-customer");

        awaitStderrContains(Duration.ofSeconds(10), "section.refused", "projects/product");
        awaitStderrContains(Duration.ofSeconds(10), "mcp.recall", "project=oss-lib", "outcome=ok");
        assertEveryLineSoFarIsJson();
    }

    @Test
    void recallForAnotherProjectAppliesThatProjectsRules() throws Exception {
        start();
        initialize();
        // private, personal: none, uses oss-lib: its own card and oss-lib's, no personal card at all
        String standalone = recallText(2, "standalone");
        assertThat(standalone).contains("standalone-plan").contains("oss-lib-build")
                .doesNotContain("owner-review-habit").doesNotContain("owner-private-account")
                .doesNotContain("product-customer");
        // private, no uses: its own card, all personal cards, nothing of oss-lib
        String product = recallText(3, "product");
        assertThat(product).contains("product-customer").contains("owner-private-account")
                .contains("owner-review-habit").doesNotContain("oss-lib-build");
        // not declared in memory.yaml: the personal cards only, never its own folder
        String undeclared = recallText(4, "undeclared");
        assertThat(undeclared).contains("owner-private-account").doesNotContain("undeclared-note");
        awaitStderrContains(Duration.ofSeconds(10), "section.refused", "personal: none");
    }

    private String recallText(int id, String project) throws Exception {
        JsonNode r = call(id, "tools/call", Map.of("name", "recall",
                "arguments", Map.of("question", "zebra?", "project", project)));
        assertThat(r.path("isError").asBoolean(false)).as(r.toString()).isFalse();
        return r.get("content").get(0).get("text").asText();
    }

    @Test
    void aLineThatIsNotJsonIsDroppedAndTheServerStillExitsWhenStdinCloses() throws Exception {
        start();
        stdin.write("this is not json\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        initialize();
        assertThat(call(2, "tools/list", Map.of()).get("tools").size()).isEqualTo(2);
        stdin.close();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("the server exits once stdin closes").isTrue();
        assertEveryLineSoFarIsJson();
    }

    @Test
    void recallRefusesAProjectNameThatIsAPath() throws Exception {
        start();
        initialize();
        JsonNode result = call(2, "tools/call", Map.of("name", "recall",
                "arguments", Map.of("question", "zebra", "project", "../product")));
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        assertThat(result.toString()).doesNotContain("product-customer");
        awaitStderrContains(Duration.ofSeconds(10), "mcp.recall", "outcome=refused");
    }

    @Test
    void draftEpisodeWritesOneNewFileAndNeverOverwritesOrStoresACredential() throws Exception {
        start();
        initialize();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("project", "oss-lib");
        args.put("slug", "first-draft");
        args.put("asked", "Wire the zebra build");
        args.put("decided", List.of(Map.of("what", "keep one build file", "why", "two drifted before")));
        args.put("learned", List.of("the build reads ZEBRA_HOME, by name only"));

        JsonNode written = call(2, "tools/call", Map.of("name", "draft_episode", "arguments", args));
        assertThat(written.path("isError").asBoolean(false)).as(written.toString()).isFalse();
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        Path file = root.resolve("projects/oss-lib/episodes/" + date + "-first-draft.yaml");
        assertThat(file).exists();
        String body = Files.readString(file);
        assertThat(body).contains("id: " + date + "-first-draft").contains("project: oss-lib")
                .contains("format: 1").contains("visibility: private").contains("keep one build file");

        JsonNode again = call(3, "tools/call", Map.of("name", "draft_episode", "arguments", args));
        assertThat(again.path("isError").asBoolean(false)).isTrue();
        assertThat(Files.readString(file)).isEqualTo(body);

        Map<String, Object> leaky = new LinkedHashMap<>(args);
        leaky.put("slug", "leaky");
        leaky.put("learned", List.of("the box password: hunter2abcdef1234"));
        JsonNode refused = call(4, "tools/call", Map.of("name", "draft_episode", "arguments", leaky));
        assertThat(refused.path("isError").asBoolean(false)).isTrue();
        assertThat(refused.toString()).doesNotContain("hunter2abcdef1234");
        assertThat(root.resolve("projects/oss-lib/episodes/" + date + "-leaky.yaml")).doesNotExist();
        awaitStderrContains(Duration.ofSeconds(10), "mcp.draft_episode");
        assertEveryLineSoFarIsJson();
    }

    // ---- harness (the same shape as AcpStdioIT) ----

    private JsonNode initialize() throws Exception {
        JsonNode init = call(1, "initialize", Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mcp-stdio-it", "version", "1")));
        send(notification("notifications/initialized"));
        return init;
    }

    private void start() throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.example.agent.AgentApplication",
                "--mcp",
                "--agent.storage.type=memory",
                "--agent.memory.root=" + root.toAbsolutePath(),
                "--agent.memory.project=oss-lib",
                "--agent.workspace=" + workspace.toAbsolutePath());
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = process.getOutputStream();
        Thread out = new Thread(this::drainStdout, "mcp-it-stdout");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(this::drainStderr, "mcp-it-stderr");
        err.setDaemon(true);
        err.start();
    }

    private void drainStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                allLines.add(line);
                queue.add(line);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stderrLines.add(line);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private void awaitStderrContains(Duration timeout, String... parts) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (String line : stderrLines) {
                boolean all = true;
                for (String p : parts) {
                    if (!line.contains(p)) {
                        all = false;
                        break;
                    }
                }
                if (all) return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no stderr line with " + List.of(parts) + "\nstderr:\n" + String.join("\n", stderrLines));
    }

    private static Map<String, Object> notification(String method) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        return m;
    }

    private void send(Map<String, Object> message) throws IOException {
        stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private JsonNode call(int id, String method, Object params) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.put("params", params);
        send(m);
        JsonNode response = awaitMessage(n -> n.has("id") && n.get("id").asInt() == id, Duration.ofSeconds(40));
        assertThat(response.has("error")).as(method + " returned a JSON-RPC error: " + response).isFalse();
        return response.get("result");
    }

    private JsonNode awaitMessage(Predicate<JsonNode> predicate, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("timed out\nstdout:\n" + String.join("\n", allLines)
                        + "\nstderr:\n" + String.join("\n", stderrLines.subList(Math.max(0, stderrLines.size() - 40), stderrLines.size())));
            }
            String line = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) continue;
            JsonNode node = JSON.readTree(line);
            if (predicate.test(node)) return node;
        }
    }

    private void assertEveryLineSoFarIsJson() throws Exception {
        Thread.sleep(200);
        for (String line : allLines) {
            assertThat(JSON.readTree(line).has("jsonrpc")).as("not JSON-RPC: " + line).isTrue();
        }
    }
}
