package com.example.agent.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal Language Server Protocol client (#190): one server process over stdio, the
 * {@code Content-Length}-framed JSON-RPC the protocol defines. It asks only the questions the
 * read-only tools need (definition, references, workspace symbols) and keeps the diagnostics the
 * server publishes. A request the server sends us is answered with an empty result, so a server
 * waiting on configuration or progress tokens is never left hanging.
 *
 * <p>The server runs as {@code HookRunner}'s hooks do: in the workspace, with an environment of
 * PATH, HOME and LANG plus only the variables its configuration names, so it never sees
 * Penstock's own credentials; and on close or a failed start its whole process tree is killed.
 */
public class LspClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LspClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** A frame larger than this is refused: a confused server must not exhaust memory. */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    /** A header line longer than this is refused, for the same reason. */
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    /** A file larger than this is not sent to the server. */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** How long close() waits for a polite shutdown before killing the process tree. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    /** What the server last published for a file, and for which document version (null: unstated). */
    private record Published(Integer version, long receivedAt, JsonNode diagnostics) {
    }

    private final Process process;
    private final OutputStream out;
    private final Path workspace;
    private final Duration timeout;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Published> diagnostics = new ConcurrentHashMap<>();
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Object syncLock = new Object();
    /** Replies to the server's own requests, so the reader thread never waits on a write. */
    private final java.util.concurrent.ExecutorService replies = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsp-replies");
        t.setDaemon(true);
        return t;
    });
    private final Thread reader;
    private volatile boolean broken;

    public LspClient(List<String> command, Path workspace, Duration timeout, List<String> passEnv) throws IOException {
        this.workspace = workspace;
        this.timeout = timeout;
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> env = environment(pb.environment(), passEnv, workspace);
        pb.environment().clear();
        pb.environment().putAll(env);
        this.process = pb.start();
        this.out = process.getOutputStream();
        this.reader = new Thread(this::readLoop, "lsp-reader");
        this.reader.setDaemon(true);
        this.reader.start();
        try {
            initialize(workspace);
        } catch (IOException | RuntimeException e) {
            kill();
            throw e;
        }
    }

    /**
     * The server's environment: PATH, HOME and LANG from {@code parent} (with defaults), plus the
     * variables {@code pass} names that {@code parent} has. Nothing else: Penstock's model keys,
     * database password and tokens stay out of the server's reach.
     */
    static Map<String, String> environment(Map<String, String> parent, List<String> pass, Path workspace) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("PATH", parent.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        env.put("HOME", parent.getOrDefault("HOME", workspace.toString()));
        env.put("LANG", parent.getOrDefault("LANG", "C.UTF-8"));
        for (String name : pass == null ? List.<String>of() : pass) {
            if (parent.containsKey(name)) {
                env.put(name, parent.get(name));
            }
        }
        return env;
    }

    private void initialize(Path workspace) throws IOException {
        ObjectNode caps = JSON.createObjectNode();
        caps.putObject("textDocument").putObject("publishDiagnostics");
        ObjectNode params = JSON.createObjectNode();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootUri", workspace.toUri().toString());
        params.set("capabilities", caps);
        params.putArray("workspaceFolders").addObject()
                .put("uri", workspace.toUri().toString()).put("name", String.valueOf(workspace.getFileName()));
        request("initialize", params, timeout);
        notify("initialized", JSON.createObjectNode());
    }

    /** Opens the file (or sends its current text again) so the server analyses what is on disk now. */
    public int sync(Path file) throws IOException {
        synchronized (syncLock) {
            return syncLocked(file);
        }
    }

    private int syncLocked(Path file) throws IOException {
        String uri = file.toUri().toString();
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException(file.getFileName() + " is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB; not sent to the language server");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Integer version = versions.merge(uri, 1, Integer::sum);
        ObjectNode params = JSON.createObjectNode();
        if (version == 1) {
            params.putObject("textDocument").put("uri", uri).put("languageId", languageId(file))
                    .put("version", version).put("text", text);
            notify("textDocument/didOpen", params);
        } else {
            params.putObject("textDocument").put("uri", uri).put("version", version);
            params.putArray("contentChanges").addObject().put("text", text);
            notify("textDocument/didChange", params);
        }
        return version;
    }

    public JsonNode definition(Path file, int line, int character) throws IOException {
        sync(file);
        return request("textDocument/definition", position(file, line, character), timeout);
    }

    public JsonNode references(Path file, int line, int character) throws IOException {
        sync(file);
        ObjectNode params = position(file, line, character);
        params.putObject("context").put("includeDeclaration", false);
        return request("textDocument/references", params, timeout);
    }

    public JsonNode workspaceSymbols(String query) throws IOException {
        ObjectNode params = JSON.createObjectNode();
        params.put("query", query);
        return request("workspace/symbol", params, timeout);
    }

    /**
     * The diagnostics the server publishes for {@code file} as it is on disk now, waiting up to
     * {@code wait}. A publication for an older version of the file is ignored; one that states no
     * version counts only if it arrived after the sync. Null when none arrived in time.
     */
    public JsonNode diagnostics(Path file, Duration wait) throws IOException, InterruptedException {
        String uri = file.toUri().toString();
        long syncedAt = System.nanoTime();
        int version = sync(file);
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline && isAlive()) {
            Published p = diagnostics.get(uri);
            if (p != null && (p.version() != null ? p.version() >= version : p.receivedAt() > syncedAt)) {
                return p.diagnostics();
            }
            Thread.sleep(25);
        }
        return null;
    }

    public Path workspace() {
        return workspace;
    }

    /** The process runs and its stream is still being read; false after a malformed frame. */
    public boolean isAlive() {
        return process.isAlive() && reader.isAlive() && !broken;
    }

    private ObjectNode position(Path file, int line, int character) {
        ObjectNode params = JSON.createObjectNode();
        params.putObject("textDocument").put("uri", file.toUri().toString());
        params.putObject("position").put("line", line).put("character", character);
        return params;
    }

    private JsonNode request(String method, JsonNode params, Duration wait) throws IOException {
        int id = ids.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        send(msg);
        try {
            JsonNode response = f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                throw new IOException(method + " failed: " + response.get("error").path("message").asText());
            }
            return response.get("result");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException(method + " got no answer within " + wait.toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(method + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(method + " was interrupted", e);
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0").put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        send(msg);
    }

    private void send(JsonNode msg) throws IOException {
        byte[] body = JSON.writeValueAsBytes(msg);
        synchronized (writeLock) {
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }
    }

    private void readLoop() {
        try (InputStream in = new BufferedInputStream(process.getInputStream())) {
            while (true) {
                int length = readHeaders(in);
                if (length < 0) {
                    break;
                }
                JsonNode msg = JSON.readTree(in.readNBytes(length));
                dispatch(msg);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Language server stream unreadable, the server will be restarted on next use: {}", e.toString());
        } finally {
            broken = true;
            pending.values().forEach(f -> f.completeExceptionally(new IOException("the language server stopped answering")));
        }
    }

    private void dispatch(JsonNode msg) {
        if (msg == null) {
            return;
        }
        if (msg.has("id") && !msg.has("method")) {
            CompletableFuture<JsonNode> f = pending.get(msg.get("id").asInt());
            if (f != null) {
                f.complete(msg);
            }
        } else if (msg.has("id")) {
            // a request from the server: configuration, capability registration, progress tokens.
            // Answered on another thread, so this reader never blocks on a write.
            ObjectNode reply = JSON.createObjectNode();
            reply.put("jsonrpc", "2.0").set("id", msg.get("id"));
            switch (msg.path("method").asText()) {
                case "workspace/configuration" -> reply.putArray("result");
                case "workspace/applyEdit" -> reply.putObject("result").put("applied", false); // read-only
                default -> reply.putNull("result");
            }
            replies.execute(() -> {
                try {
                    send(reply);
                } catch (IOException e) {
                    log.debug("Could not answer the language server: {}", e.getMessage());
                }
            });
        } else if ("textDocument/publishDiagnostics".equals(msg.path("method").asText())) {
            JsonNode params = msg.path("params");
            String uri = params.path("uri").asText("");
            if (versions.containsKey(uri)) { // only files a tool asked about are kept
                Integer version = params.hasNonNull("version") ? params.get("version").asInt() : null;
                diagnostics.put(uri, new Published(version, System.nanoTime(), params.path("diagnostics")));
            }
        }
    }

    /** The Content-Length of the next frame, or -1 at the end of the stream. */
    private static int readHeaders(InputStream in) throws IOException {
        int length = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return -1;
            }
            if (b == '\n') {
                String header = line.toString(StandardCharsets.US_ASCII).trim();
                line.reset();
                if (header.isEmpty()) {
                    if (length < 0 || length > MAX_FRAME_BYTES) {
                        throw new IOException("bad frame length " + length);
                    }
                    return length;
                }
                if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    try {
                        length = Integer.parseInt(header.substring(15).trim());
                    } catch (NumberFormatException e) {
                        throw new IOException("bad Content-Length: " + header);
                    }
                }
            } else {
                if (line.size() >= MAX_HEADER_BYTES) {
                    throw new IOException("header line longer than " + MAX_HEADER_BYTES + " bytes");
                }
                line.write(b);
            }
        }
    }

    private static String languageId(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "plaintext" : name.substring(dot + 1);
    }

    @Override
    public void close() {
        // taken first: a launcher script may exit before its server, which would then be reparented
        List<ProcessHandle> tree = process.descendants().toList();
        try {
            if (isAlive()) {
                request("shutdown", null, SHUTDOWN_WAIT);
                notify("exit", null);
                process.waitFor(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.debug("Language server did not shut down cleanly: {}", e.getMessage());
        } finally {
            kill(tree);
        }
    }

    private void kill() {
        kill(process.descendants().toList());
    }

    private void kill(List<ProcessHandle> tree) {
        java.util.ArrayList<ProcessHandle> all = new java.util.ArrayList<>(tree);
        process.descendants().forEach(all::add);
        process.destroyForcibly();
        all.forEach(ProcessHandle::destroyForcibly);
        replies.shutdownNow();
    }
}
