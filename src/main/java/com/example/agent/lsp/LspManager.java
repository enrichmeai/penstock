package com.example.agent.lsp;

import com.example.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The language servers configured under {@code agent.tools.lsp.servers} (#190), one process per
 * language, started on first use and restarted if it died. Stopped with the application.
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.lsp", name = "enabled", havingValue = "true")
public class LspManager {

    private static final Logger log = LoggerFactory.getLogger(LspManager.class);

    private final AgentProperties.Lsp props;
    private final Path workspace;
    private final Map<String, LspClient> clients = new ConcurrentHashMap<>();
    /** When a server last failed to start: it is not started again for {@link #RETRY_AFTER}. */
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static final java.time.Duration RETRY_AFTER = java.time.Duration.ofMinutes(1);

    public LspManager(AgentProperties props, Path agentWorkspace) {
        this.props = props.getTools().getLsp();
        Path real;
        try {
            // the server reports real paths; compare them with the real workspace
            real = agentWorkspace.toRealPath();
        } catch (IOException e) {
            real = agentWorkspace;
        }
        this.workspace = real;
    }

    /** Whether any language server is configured with a command. */
    public boolean configured() {
        return props.getServers().values().stream().anyMatch(s -> !s.getCommand().isEmpty());
    }

    /** The language whose server serves this file, by extension. */
    public Optional<String> languageOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return props.getServers().entrySet().stream()
                .filter(e -> !e.getValue().getCommand().isEmpty())
                .filter(e -> e.getValue().getExtensions().stream()
                        .anyMatch(ext -> name.endsWith(ext.toLowerCase(Locale.ROOT))))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** The configured languages that have a command, in configuration order. */
    public java.util.List<String> languages() {
        return props.getServers().entrySet().stream()
                .filter(e -> !e.getValue().getCommand().isEmpty()).map(Map.Entry::getKey).toList();
    }

    public synchronized LspClient client(String language) throws IOException {
        LspClient c = clients.remove(language);
        if (c != null && c.isAlive()) {
            clients.put(language, c);
            return c;
        }
        if (c != null) {
            c.close(); // a dead client may still have a child process: kill its tree before replacing it
        }
        AgentProperties.Lsp.Server server = props.getServers().get(language);
        if (server == null || server.getCommand().isEmpty()) {
            throw new IOException("no language server is configured for " + language);
        }
        Long failed = failedAt.get(language);
        if (failed != null && System.nanoTime() - failed < RETRY_AFTER.toNanos()) {
            throw new IOException("the " + language + " language server failed to start less than "
                    + RETRY_AFTER.toMinutes() + " min ago; not retried yet");
        }
        log.info("Starting the {} language server", language);
        try {
            c = new LspClient(server.getCommand(), workspace, Duration.ofSeconds(props.getTimeoutSeconds()), server.getEnv());
        } catch (IOException | RuntimeException e) {
            failedAt.put(language, System.nanoTime());
            throw e;
        }
        failedAt.remove(language);
        clients.put(language, c);
        return c;
    }

    public AgentProperties.Lsp props() {
        return props;
    }

    public Path workspace() {
        return workspace;
    }

    @PreDestroy
    public void stop() {
        clients.values().forEach(LspClient::close);
        clients.clear();
    }
}
