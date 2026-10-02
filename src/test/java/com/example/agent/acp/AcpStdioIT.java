package com.example.agent.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application in {@code --acp} stdio mode as a child process and drives it
 * over actual pipes — the scripted session CLAUDE.md's issue #61 asks for: {@code initialize
 * -> session/new -> session/prompt}, plus {@code session/cancel} mid-turn.
 *
 * <p>Runs the child with the current JVM's test classpath (main classes/resources + test
 * classes/resources + runtime deps), not the packaged jar — so {@link AcpStubLlmProvider}
 * (component-scanned the same way a real provider is, via {@code agent.llm.provider=stub})
 * is on its classpath without ever shipping in {@code bootJar}, and so the test needs no
 * build-ordering dependency on packaging.
 *
 * <p>RED on {@code main}: {@code --acp} is not recognised at all, so the process starts as a
 * normal web app — it prints the Spring banner to stdout and never answers {@code initialize}
 * (it has no stdin reader), so {@code awaitResponse} times out waiting for a response that
 * never comes, with the banner lines sitting in {@code allLines} as proof of what went wrong.
 */
class AcpStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private Process process;
    private OutputStream stdin;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final List<String> allLines = new CopyOnWriteArrayList<>();
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Test
    void initializeNewSessionAndPromptReturnEndTurn() throws Exception {
        start();

        JsonNode initResult = call(1, "initialize", Map.of("protocolVersion", 1));
        assertThat(initResult.get("protocolVersion").asInt()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
        assertThat(initResult.has("agentCapabilities")).isTrue();

        String cwd = workspace.toRealPath().toString();
        JsonNode newSessionResult = call(2, "session/new", Map.of("cwd", cwd, "mcpServers", List.of()));
        String sessionId = newSessionResult.get("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        JsonNode promptResult = call(3, "session/prompt", Map.of(
                "sessionId", sessionId,
                "prompt", List.of(Map.of("type", "text", "text", "hello"))));
        assertThat(promptResult.get("stopReason").asText()).isEqualTo("end_turn");

        assertEveryLineSoFarIsJson();
    }

    @Test
    void sessionCancelMidTurnReturnsCancelledStopReason() throws Exception {
        start();

        call(1, "initialize", Map.of("protocolVersion", 1));
        String cwd = workspace.toRealPath().toString();
        JsonNode newSessionResult = call(2, "session/new", Map.of("cwd", cwd, "mcpServers", List.of()));
        String sessionId = newSessionResult.get("sessionId").asText();

        send(request(3, "session/prompt", Map.of(
                "sessionId", sessionId,
                "prompt", List.of(Map.of("type", "text", "text", AcpStubLlmProvider.SLEEP_TRIGGER)))));
        // The stub provider is now sleeping (simulating a slow in-flight provider call);
        // the cancel notification races it, with ample margin before the sleep ends.
        send(notification("session/cancel", Map.of("sessionId", sessionId)));

        JsonNode promptResponse = awaitMessage(n -> n.has("id") && n.get("id").asInt() == 3, Duration.ofSeconds(15));
        assertThat(promptResponse.has("error"))
                .as("cancellation must surface as a normal prompt response, not a JSON-RPC error: " + promptResponse)
                .isFalse();
        assertThat(promptResponse.get("result").get("stopReason").asText()).isEqualTo("cancelled");

        assertEveryLineSoFarIsJson();
    }

    /**
     * Issue #73 ("retrieve before reason"): the same {@code AgentService.runTurn} step runs
     * inside {@code AcpMode.prompt}, so a prompt matching the seed pattern's triggers must
     * load it here too — confirmed via the stderr line {@code AuditLogger.patternLoaded}
     * writes, since ACP mode's forced {@code storage.type=memory} has no audit table.
     */
    @Test
    void promptMatchingAPatternLogsItToStderr() throws Exception {
        start();

        call(1, "initialize", Map.of("protocolVersion", 1));
        String cwd = workspace.toRealPath().toString();
        JsonNode newSessionResult = call(2, "session/new", Map.of("cwd", cwd, "mcpServers", List.of()));
        String sessionId = newSessionResult.get("sessionId").asText();

        JsonNode promptResult = call(3, "session/prompt", Map.of(
                "sessionId", sessionId,
                "prompt", List.of(Map.of("type", "text", "text",
                        "How do I wire up a stdio json-rpc agent for the editor?"))));
        assertThat(promptResult.get("stopReason").asText()).isEqualTo("end_turn");

        awaitStderrContains(Duration.ofSeconds(10), "pattern.loaded", "stdio-json-rpc-agent");

        assertEveryLineSoFarIsJson();
    }

    // ---- harness ----

    private void start() throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.example.agent.AgentApplication",
                "--acp",
                "--agent.llm.provider=stub",
                "--agent.storage.type=memory",
                "--agent.auth.enabled=false",
                // Harmless for the other scenarios here (their prompts hit no trigger);
                // lets promptMatchingAPatternLogsItToStderr below exercise retrieval in ACP
                // mode, where memory storage means stderr is the only trail (no audit table).
                "--agent.memory.enabled=true",
                "--agent.workspace=" + workspace.toAbsolutePath());
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = process.getOutputStream();

        Thread stdoutReader = new Thread(this::drainStdout, "acp-it-stdout-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrDrain = new Thread(this::drainStderr, "acp-it-stderr-drain");
        stderrDrain.setDaemon(true);
        stderrDrain.start();
    }

    private void drainStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                allLines.add(line);
                queue.add(line);
            }
        } catch (IOException ignored) {
            // process ended / stream closed
        }
    }

    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // Normally just logs, not asserted on — but in ACP mode, storage is forced to
                // memory (no audit table), so AuditLogger.patternLoaded's INFO line is the
                // only trail of what retrieval loaded; promptMatchingAPatternLogsItToStderr
                // below reads it back from here.
                stderrLines.add(line);
            }
        } catch (IOException ignored) {
        }
    }

    /** Polls the captured stderr lines for one containing every given substring. */
    private void awaitStderrContains(Duration timeout, String... substrings) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (String line : stderrLines) {
                boolean matchesAll = true;
                for (String s : substrings) {
                    if (!line.contains(s)) {
                        matchesAll = false;
                        break;
                    }
                }
                if (matchesAll) return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for a stderr line containing " + List.of(substrings)
                + ".\nstderr so far:\n" + String.join("\n", stderrLines));
    }

    private static Map<String, Object> request(int id, String method, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.put("params", params);
        return m;
    }

    private static Map<String, Object> notification(String method, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.put("params", params);
        return m;
    }

    private void send(Map<String, Object> message) throws IOException {
        String line = JSON.writeValueAsString(message);
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Sends a request and waits (generously — the first call pays full Spring Boot startup) for its response, returning "result". */
    private JsonNode call(int id, String method, Object params) throws Exception {
        send(request(id, method, params));
        JsonNode response = awaitMessage(n -> n.has("id") && n.get("id").asInt() == id, Duration.ofSeconds(30));
        assertThat(response.has("error"))
                .as(method + " returned a JSON-RPC error: " + response)
                .isFalse();
        return response.get("result");
    }

    private JsonNode awaitMessage(Predicate<JsonNode> predicate, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Timed out waiting for a matching JSON-RPC message.\nLines so far:\n"
                        + String.join("\n", allLines));
            }
            String line = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) continue;
            JsonNode node = JSON.readTree(line);
            if (predicate.test(node)) return node;
        }
    }

    /** Every line the process has written to stdout so far must be valid JSON-RPC — never a banner or log line. */
    private void assertEveryLineSoFarIsJson() throws IOException, InterruptedException {
        Thread.sleep(200); // let any already-in-flight write land before the snapshot
        for (String line : allLines) {
            JsonNode node = JSON.readTree(line); // throws JsonProcessingException on anything non-JSON
            assertThat(node.has("jsonrpc")).as("not a JSON-RPC message: " + line).isTrue();
        }
    }
}
