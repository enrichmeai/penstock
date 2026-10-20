package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.ToolContext;
import com.example.agent.tools.ToolPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs the owner's hooks (#184): commands from {@code agent.hooks.*}, at four points of the loop.
 *
 * <ul>
 *   <li>{@code pre-tool}: after the tool policy (#179) has allowed the call. A hook may deny it,
 *       never allow what the policy refused. A hook that fails (non-zero exit, timeout, output
 *       that is not a JSON object) denies: fail closed.</li>
 *   <li>{@code post-tool}: may add a note to the result the model sees. Fails open.</li>
 *   <li>{@code turn-start} / {@code turn-end}: observe only. Fail open.</li>
 * </ul>
 *
 * <p>A hook gets one JSON object on stdin and may answer with one on stdout:
 * {@code {"decision":"deny","reason":"…"}} or {@code {"decision":"allow"}} for {@code pre-tool},
 * {@code {"note":"…"}} for {@code post-tool}; empty output means "no opinion". It runs in the
 * workspace with a minimal environment ({@code PATH}, {@code HOME}, {@code LANG} and
 * {@code PENSTOCK_HOOK_EVENT}), never the agent's own, so no credential of Penstock's reaches it.
 * Every run is audited as {@code hook.ran}.
 */
@Component
public class HookRunner {

    private static final Logger log = LoggerFactory.getLogger(HookRunner.class);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_NOTE_CHARS = 4_000;
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    public static final String PRE_TOOL = "pre-tool";
    public static final String POST_TOOL = "post-tool";
    public static final String TURN_START = "turn-start";
    public static final String TURN_END = "turn-end";

    private final AgentProperties.Hooks hooks;
    private final Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();
    private AuditLogger auditLogger;

    public HookRunner(AgentProperties props, Path agentWorkspace) {
        this.hooks = props.getHooks();
        this.workspace = agentWorkspace;
    }

    @Autowired(required = false)
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /** The reason a pre-tool hook denied this call, or empty when it may run. */
    public Optional<String> preTool(ToolContext context, ToolCall call) {
        List<AgentProperties.Hook> list = hooks.getPreTool();
        for (int i = 0; i < list.size(); i++) {
            AgentProperties.Hook hook = list.get(i);
            if (!appliesTo(hook, call.name())) continue;
            Run run = run(PRE_TOOL, hook, toolPayload(PRE_TOOL, context, call, null));
            if (!run.ok()) {
                audit(context, PRE_TOOL, i, call.name(), run.failure(), run.millis());
                return Optional.of("pre-tool hook " + i + " failed (" + run.failure() + "), so the call was denied");
            }
            Answer answer = parse(run.stdout());
            if (!answer.valid()) {
                audit(context, PRE_TOOL, i, call.name(), "error", run.millis());
                return Optional.of("pre-tool hook " + i + " did not answer with a JSON object, so the call was denied");
            }
            String decision = answer.text("decision");
            if ("deny".equalsIgnoreCase(decision)) {
                String reason = answer.text("reason");
                audit(context, PRE_TOOL, i, call.name(), "deny", run.millis());
                return Optional.of(reason.isBlank() ? "denied by pre-tool hook " + i : clip(reason));
            }
            audit(context, PRE_TOOL, i, call.name(), "allow".equalsIgnoreCase(decision) ? "allow" : "pass", run.millis());
        }
        return Optional.empty();
    }

    /** The result with any post-tool notes appended; the result itself when there are none. */
    public ToolResult postTool(ToolContext context, ToolCall call, ToolResult result) {
        List<AgentProperties.Hook> list = hooks.getPostTool();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            AgentProperties.Hook hook = list.get(i);
            if (!appliesTo(hook, call.name())) continue;
            Run run = run(POST_TOOL, hook, toolPayload(POST_TOOL, context, call, result));
            if (!run.ok()) {
                audit(context, POST_TOOL, i, call.name(), run.failure(), run.millis());
                continue;
            }
            Answer answer = parse(run.stdout());
            String note = answer.text("note");
            audit(context, POST_TOOL, i, call.name(), !answer.valid() ? "error" : note.isBlank() ? "pass" : "note", run.millis());
            if (!note.isBlank()) notes.add(clip(note));
        }
        if (notes.isEmpty()) return result;
        String content = (result.content() == null ? "" : result.content())
                + "\n\n[Note from the owner's post-tool hook]\n" + String.join("\n", notes);
        return new ToolResult(result.callId(), content, result.isError());
    }

    /** Runs the turn-start or turn-end hooks; they observe only, and a failure is logged. */
    public void turn(String event, ToolContext context, Map<String, Object> extra) {
        List<AgentProperties.Hook> list = TURN_START.equals(event) ? hooks.getTurnStart() : hooks.getTurnEnd();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("sessionId", context.sessionId());
            payload.put("userId", context.userId());
            payload.putAll(extra);
            Run run = run(event, list.get(i), payload);
            audit(context, event, i, null, run.ok() ? "pass" : run.failure(), run.millis());
        }
    }

    private static boolean appliesTo(AgentProperties.Hook hook, String toolName) {
        if (hook.getTools().isEmpty()) return true;
        String key = ToolPolicy.normaliseKey(toolName);
        return hook.getTools().stream().anyMatch(t -> ToolPolicy.normaliseKey(t).equals(key));
    }

    private static Map<String, Object> toolPayload(String event, ToolContext context, ToolCall call, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("sessionId", context.sessionId());
        payload.put("userId", context.userId());
        payload.put("callId", call.id());
        payload.put("tool", call.name());
        payload.put("arguments", call.arguments() == null ? Map.of() : call.arguments());
        if (result != null) {
            payload.put("result", Map.of("content", result.content() == null ? "" : result.content(), "isError", result.isError()));
        }
        return payload;
    }

    /** What a hook printed: {@code valid} is false when it was neither empty nor a JSON object. */
    private record Answer(boolean valid, JsonNode node) {
        String text(String field) {
            return node == null ? "" : node.path(field).asText("");
        }
    }

    /** Empty output: a valid "no opinion". A JSON object: the answer. Anything else: invalid. */
    private Answer parse(String stdout) {
        if (stdout == null || stdout.isBlank()) return new Answer(true, null);
        try {
            JsonNode node = mapper.readTree(stdout);
            return node != null && node.isObject() ? new Answer(true, node) : new Answer(false, null);
        } catch (IOException e) {
            return new Answer(false, null);
        }
    }

    private record Run(String failure, String stdout, long millis) {
        boolean ok() { return failure == null; }
    }

    private Run run(String event, AgentProperties.Hook hook, Map<String, Object> payload) {
        long start = System.nanoTime();
        if (hook.getCommand().isEmpty()) {
            return new Run("error", "", 0);
        }
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(hook.getCommand())
                    .directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> env = pb.environment();
            String path = env.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
            String home = env.getOrDefault("HOME", workspace.toString());
            String lang = env.getOrDefault("LANG", "C.UTF-8");
            env.clear();
            env.put("PATH", path);
            env.put("HOME", home);
            env.put("LANG", lang);
            env.put("PENSTOCK_HOOK_EVENT", event);
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            log.warn("hook {} {} could not start: {}", event, hook.getCommand(), e.toString());
            return new Run("error", "", elapsed(start));
        }
        byte[] input;
        try {
            input = mapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            process.destroyForcibly();
            return new Run("error", "", elapsed(start));
        }
        Process p = process;
        IO.execute(() -> {
            try (OutputStream in = p.getOutputStream()) {
                in.write(input);
            } catch (IOException ignored) {
                // the hook exited without reading its input; that is its business
            }
        });
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
            try (InputStream s = p.getInputStream()) {
                return s.readNBytes(MAX_OUTPUT_BYTES + 1);
            } catch (IOException e) {
                return new byte[0];
            }
        }, IO);
        // Everything the hook starts, seen while it runs: a child it leaves behind is killed
        // when the hook ends, whichever way it ends. Polled, so a child forked and orphaned
        // within one interval can still escape; hooks should not background work.
        java.util.Set<ProcessHandle> seen = new java.util.HashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(hook.getTimeoutSeconds());
        try {
            boolean exited = false;
            while (!exited && System.nanoTime() < deadline) {
                p.descendants().forEach(seen::add);
                exited = p.waitFor(Math.min(20, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS);
            }
            if (!exited) {
                p.descendants().forEach(seen::add);
                return new Run("timeout", "", elapsed(start));
            }
            // A child still holding stdout would keep the read open: kill the leftovers first.
            killAll(seen);
            byte[] bytes;
            try {
                bytes = out.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("hook {} {}: its output stayed open after it exited", event, hook.getCommand());
                return new Run("error", "", elapsed(start));
            }
            if (bytes.length > MAX_OUTPUT_BYTES) {
                log.warn("hook {} {}: output over {} bytes", event, hook.getCommand(), MAX_OUTPUT_BYTES);
                return new Run("output-too-large", "", elapsed(start));
            }
            String stdout = new String(bytes, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                return new Run("error", stdout, elapsed(start));
            }
            return new Run(null, stdout, elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run("error", "", elapsed(start));
        } catch (Exception e) {
            return new Run("error", "", elapsed(start));
        } finally {
            killAll(seen);
            if (p.isAlive()) p.destroyForcibly();
            out.cancel(true);
        }
    }

    private static void killAll(java.util.Set<ProcessHandle> handles) {
        handles.forEach(h -> {
            if (h.isAlive()) h.destroyForcibly();
        });
    }

    private void audit(ToolContext context, String event, int index, String tool, String outcome, long millis) {
        if (auditLogger != null) {
            auditLogger.hookRan(context.userId(), context.sessionId(), event, index, tool, outcome, millis);
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String clip(String s) {
        return s.length() <= MAX_NOTE_CHARS ? s : s.substring(0, MAX_NOTE_CHARS) + "…";
    }
}
