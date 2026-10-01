package com.example.agent.acp;

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
 * Drives the real application in {@code --acp} stdio mode as a child process and plays the
 * client side of {@code session/request_permission} — CLAUDE.md issue #62, part 2 of #59. The
 * permission-stub provider ({@link AcpPermissionStubLlmProvider}) always asks for the {@code
 * shell} or {@code pod} tool so every case below is deterministic.
 *
 * <p>RED on the part-1 branch: {@code shell}/{@code git}/{@code write_file}/{@code edit_file}
 * and {@code pod} writes are excluded from {@code agent.tools.enabled} entirely (part 1's
 * read-only ACP surface), so the stub's tool call comes back {@code "Unknown tool: shell"} and
 * no {@code session/request_permission} is ever sent — {@link #awaitRequest} times out.
 */
class AcpPermissionIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path workspace;

    private Process process;
    private OutputStream stdin;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final List<String> allLines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Test
    void allowOnceRunsTheToolOnceAndAsksAgainNextTime() throws Exception {
        String sessionId = startSession();

        send(request(3, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        JsonNode req1 = awaitRequest("session/request_permission");
        assertThat(req1.get("params").get("toolCall").get("kind").asText()).isEqualTo("execute");
        respondSelected(req1, "allow_once");
        assertThat(awaitResponse(3).get("stopReason").asText()).isEqualTo("end_turn");

        send(request(4, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        JsonNode req2 = awaitRequest("session/request_permission");
        respondSelected(req2, "allow_once");
        assertThat(awaitResponse(4).get("stopReason").asText()).isEqualTo("end_turn");

        assertEveryLineSoFarIsJson();
    }

    @Test
    void allowAlwaysRunsTheToolAndIsNotAskedAgainForThatTool() throws Exception {
        String sessionId = startSession();

        send(request(3, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        JsonNode req1 = awaitRequest("session/request_permission");
        respondSelected(req1, "allow_always");
        assertThat(awaitResponse(3).get("stopReason").asText()).isEqualTo("end_turn");

        send(request(4, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        // No session/request_permission this time: the prompt response must arrive directly.
        assertThat(awaitResponse(4).get("stopReason").asText()).isEqualTo("end_turn");

        assertThat(countRequestPermissionMessages()).isEqualTo(1);
        assertEveryLineSoFarIsJson();
    }

    @Test
    void rejectOnceYieldsARefusalTheModelIsTold() throws Exception {
        String sessionId = startSession();

        send(request(3, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        JsonNode req = awaitRequest("session/request_permission");
        respondSelected(req, "reject_once");
        assertThat(awaitResponse(3).get("stopReason").asText()).isEqualTo("end_turn");

        assertThat(allLines).anySatisfy(line -> assertThat(line)
                .contains("Refused")
                .contains("shell"));
        assertEveryLineSoFarIsJson();
    }

    @Test
    void cancellingDuringThePermissionRequestYieldsCancelledStopReason() throws Exception {
        String sessionId = startSession();

        send(request(3, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_SHELL)));
        JsonNode req = awaitRequest("session/request_permission");
        send(notification("session/cancel", Map.of("sessionId", sessionId)));
        // The protocol's own mechanism for unblocking an agent waiting on a pending permission
        // request: the client itself resolves it with outcome "cancelled".
        respondCancelled(req);

        JsonNode response = awaitMessage(n -> n.has("id") && n.get("id").asInt() == 3, TIMEOUT);
        assertThat(response.has("error")).as("cancellation must not surface as a JSON-RPC error").isFalse();
        assertThat(response.get("result").get("stopReason").asText()).isEqualTo("cancelled");

        assertEveryLineSoFarIsJson();
    }

    @Test
    void podReadNeedsNoPermissionButPodWriteDoes() throws Exception {
        String sessionId = startSession();

        send(request(3, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_POD_READ)));
        assertThat(awaitResponse(3).get("stopReason").asText()).isEqualTo("end_turn");
        assertThat(countRequestPermissionMessages()).as("a pod read must stay unprompted").isEqualTo(0);

        send(request(4, "session/prompt", promptParams(sessionId, AcpPermissionStubLlmProvider.RUN_POD_WRITE)));
        JsonNode req = awaitRequest("session/request_permission");
        assertThat(req.get("params").get("toolCall").get("kind").asText()).isEqualTo("edit");
        respondSelected(req, "allow_once");
        assertThat(awaitResponse(4).get("stopReason").asText()).isEqualTo("end_turn");

        assertThat(countRequestPermissionMessages()).isEqualTo(1);
        assertEveryLineSoFarIsJson();
    }

    // ---- scenario setup ----

    private String startSession() throws Exception {
        start();
        call(1, "initialize", Map.of("protocolVersion", 1));
        String cwd = workspace.toRealPath().toString();
        JsonNode newSessionResult = call(2, "session/new", Map.of("cwd", cwd, "mcpServers", List.of()));
        return newSessionResult.get("sessionId").asText();
    }

    private static Map<String, Object> promptParams(String sessionId, String text) {
        return Map.of("sessionId", sessionId, "prompt", List.of(Map.of("type", "text", "text", text)));
    }

    // ---- ACP client-side permission plumbing ----

    /** Waits for the agent-initiated {@code session/request_permission} JSON-RPC request. */
    private JsonNode awaitRequest(String method) throws Exception {
        return awaitMessage(n -> n.has("method") && method.equals(n.get("method").asText()) && n.has("id"), TIMEOUT);
    }

    private JsonNode awaitResponse(int id) throws Exception {
        JsonNode response = awaitMessage(n -> n.has("id") && n.get("id").asInt() == id, TIMEOUT);
        assertThat(response.has("error")).as("request " + id + " returned a JSON-RPC error: " + response).isFalse();
        return response.get("result");
    }

    private void respondSelected(JsonNode request, String optionId) throws IOException {
        // The agent's own request id is an opaque string (e.g. "66266d45-0"), not an int —
        // echo the JsonNode back verbatim rather than round-tripping it through asInt(),
        // which silently coerces an unparsable string to 0 and never resolves the right call.
        send(jsonRpcResult(request.get("id"), Map.of("outcome", Map.of("outcome", "selected", "optionId", optionId))));
    }

    private void respondCancelled(JsonNode request) throws IOException {
        send(jsonRpcResult(request.get("id"), Map.of("outcome", Map.of("outcome", "cancelled"))));
    }

    private long countRequestPermissionMessages() throws IOException {
        long count = 0;
        for (String line : allLines) {
            JsonNode node = JSON.readTree(line);
            if (node.has("method") && "session/request_permission".equals(node.get("method").asText())) {
                count++;
            }
        }
        return count;
    }

    // ---- harness (adapted from AcpStdioIT) ----

    private void start() throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.example.agent.AgentApplication",
                "--acp",
                "--agent.llm.provider=permission-stub",
                "--agent.storage.type=memory",
                "--agent.auth.enabled=false",
                "--agent.workspace=" + workspace.toAbsolutePath());
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = process.getOutputStream();

        Thread stdoutReader = new Thread(this::drainStdout, "acp-permission-it-stdout-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrDrain = new Thread(this::drainStderr, "acp-permission-it-stderr-drain");
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
            while (r.readLine() != null) {
                // discarded — logs are expected here, not asserted on
            }
        } catch (IOException ignored) {
        }
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

    private static Map<String, Object> jsonRpcResult(JsonNode id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private void send(Map<String, Object> message) throws IOException {
        String line = JSON.writeValueAsString(message);
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Sends a request and waits for its response, returning "result". */
    private JsonNode call(int id, String method, Object params) throws Exception {
        send(request(id, method, params));
        return awaitResponse(id);
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
