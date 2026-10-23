package com.example.agent.lsp;

import com.example.agent.model.ToolResult;
import com.example.agent.tools.AfterToolCall;
import com.example.agent.tools.WorkspacePath;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * After write_file or edit_file on a file a language server serves, its errors come back with the
 * result (#190), so the agent fixes them before the next edit instead of finding out at the build.
 * Errors only, bounded; nothing when the server publishes nothing in time. Off with
 * {@code agent.tools.lsp.after-edit-wait-seconds=0}.
 */
@Component
@Conditional(LspTools.Configured.class)
public class AfterEditDiagnostics implements AfterToolCall {

    private static final Logger log = LoggerFactory.getLogger(AfterEditDiagnostics.class);
    private static final Set<String> EDITS = Set.of("write_file", "edit_file");
    private static final int MAX_LINES = 10;

    private final LspManager lsp;

    public AfterEditDiagnostics(LspManager lsp) {
        this.lsp = lsp;
    }

    /** Runs the lookups, so a server that is starting or slow never holds the edit past the wait. */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-after-edit");
        t.setDaemon(true);
        return t;
    });

    @Override
    public Optional<String> after(String toolName, Map<String, Object> arguments, ToolResult result) {
        int wait = lsp.props().getAfterEditWaitSeconds();
        if (wait <= 0 || !EDITS.contains(toolName) || !(arguments.get("path") instanceof String p)) {
            return Optional.empty();
        }
        // One deadline for everything, starting the server included: a start still under way when
        // it passes carries on in the background, so a later edit finds the server ready.
        java.util.concurrent.Future<Optional<String>> f = worker.submit(() -> summary(toolName, p, Duration.ofSeconds(wait)));
        try {
            return f.get(wait, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("No diagnostics within {} s after {} on {}", wait, toolName, p);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.util.concurrent.ExecutionException e) {
            log.debug("No diagnostics after {} on {}: {}", toolName, p, e.getCause().toString());
            return Optional.empty();
        }
    }

    private Optional<String> summary(String toolName, String p, Duration wait) throws Exception {
        Path file = WorkspacePath.resolve(lsp.workspace(), p);
        Optional<String> language = lsp.languageOf(file);
        if (language.isEmpty() || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode ds = lsp.client(language.get()).diagnostics(file, wait);
        if (ds == null) {
            return Optional.empty();
        }
        String rel = WorkspacePath.displayRelative(lsp.workspace(), file);
        List<String> errors = LspTools.diagnosticLines(lsp.workspace(), rel, ds, true);
        if (errors.isEmpty()) {
            // a server may publish an empty list before its analysis ends: "reported", not "none"
            return Optional.of("Language server, after this edit: no errors reported.");
        }
        return Optional.of("Language server, after this edit: " + errors.size() + (errors.size() == 1 ? " error\n" : " errors\n")
                + LspTools.bounded(errors, MAX_LINES, ""));
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
