package com.example.agent.mcp;

import com.example.agent.config.AgentProperties;
import com.example.agent.service.AuditLogger;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * {@code --mcp}: a local MCP server over this process's stdio (#116), for Claude Code, Claude
 * Desktop or any MCP client that launches it as a subprocess. Two tools ({@code recall},
 * {@code draft_episode}) and one prompt ({@code start-with-memory}); no network listener and no
 * credentials. stdout carries MCP messages only (MCP 2025-11-25, basic/transports): it is captured
 * for the transport and {@code System.out} is pointed at stderr before the server starts, the same
 * way Cistern's cistern-mcp does it.
 */
@Component
@Profile("mcp")
public class McpStdioRunner {

    static final String SERVER_NAME = "penstock";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final String INSTRUCTIONS = """
            Penstock's memory for the owner's work. Call `recall` with the task as the question \
            before you plan or answer: it returns the facts, recent episodes and patterns this \
            project may see, already filtered by the owner's rules. Apply them and say when you \
            depart from one. Use `draft_episode` at the end of a piece of work to propose a record \
            of it: it writes a draft file the owner reviews; nothing else in memory changes.""";

    private final MemoryRecall recall;
    private final EpisodeDrafts drafts;
    private final AuditLogger audit;
    private final String session = "mcp-" + UUID.randomUUID();

    public McpStdioRunner(AgentProperties props, AuditLogger audit) {
        this.recall = new MemoryRecall(props, audit);
        this.drafts = new EpisodeDrafts(props);
        this.audit = audit;
    }

    /** Serves until the client closes stdin, then closes gracefully. */
    public void run() {
        PrintStream frames = System.out;
        System.setOut(new PrintStream(System.err, true));
        CountDownLatch inputClosed = new CountDownLatch(1);
        InputStream messages = jsonLinesOnly(System.in, inputClosed);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), messages, frames);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, version())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .instructions(INSTRUCTIONS)
                .tools(recallTool(), draftTool())
                .prompts(startWithMemory())
                .build();
        try {
            inputClosed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                server.closeGracefully();
            } catch (RuntimeException ignored) {
                server.close();
            }
        }
    }

    private McpServerFeatures.SyncToolSpecification recallTool() {
        Map<String, Object> schema = objectSchema(Map.of(
                "question", Map.of("type", "string", "description", "The task or question, in your own words."),
                "project", Map.of("type", "string", "description",
                        "A project declared in the memory root; omit for this workspace's project.")),
                List.of("question"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("recall")
                .title("Recall the owner's memory")
                .description("The facts, recent episodes and patterns this project may see for the question, "
                        + "filtered by the owner's rules (a public project never gets private cards). Read-only.")
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    String outcome = "error";
                    String project = str(args.get("project"));
                    try {
                        MemoryRecall.Result r = recall.recall(project, str(args.get("question")), "mcp", session);
                        outcome = r.outcome();
                        project = r.project();
                        return McpSchema.CallToolResult.builder().addTextContent(r.text()).isError(r.error()).build();
                    } catch (RuntimeException e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("recall failed: " + e.getClass().getSimpleName()).isError(true).build();
                    } finally {
                        audit.mcpCalled("mcp", session, "recall", project, outcome);
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncToolSpecification draftTool() {
        Map<String, Object> whatWhy = Map.of("type", "array", "items", Map.of("type", "object",
                "properties", Map.of("what", Map.of("type", "string"), "why", Map.of("type", "string")),
                "required", List.of("what", "why")));
        Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project", Map.of("type", "string", "description", "A declared project, or estate."));
        props.put("slug", Map.of("type", "string", "description", "Short name for the file: [a-z0-9-]."));
        props.put("asked", Map.of("type", "string", "description", "What the work was for."));
        props.put("decided", whatWhy);
        props.put("refused", whatWhy);
        props.put("learned", strings);
        props.put("open", strings);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("draft_episode")
                .title("Draft an episode for review")
                .description("Writes ONE new draft episode file in the memory root for the owner to review and "
                        + "commit. Never overwrites, never edits facts or patterns, and refuses any line that "
                        + "looks like a credential: name where a secret lives, never its value.")
                .inputSchema(objectSchema(props, List.of("project", "slug", "asked")))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    String outcome = "error";
                    try {
                        EpisodeDrafts.Result r = drafts.draft(args);
                        outcome = r.written() ? "ok" : "refused";
                        return McpSchema.CallToolResult.builder().addTextContent(r.message()).isError(!r.written()).build();
                    } catch (RuntimeException e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("draft_episode failed: " + e.getClass().getSimpleName()).isError(true).build();
                    } finally {
                        audit.mcpCalled("mcp", session, "draft_episode", str(args.get("project")), outcome);
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncPromptSpecification startWithMemory() {
        McpSchema.Prompt prompt = new McpSchema.Prompt("start-with-memory", "Start from the owner's memory",
                List.of(new McpSchema.PromptArgument("task", "What you are about to do.", false)));
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            Object task = request.arguments() == null ? null : request.arguments().get("task");
            String text = "Before you plan or answer, call the `recall` tool with this task as the question "
                    + "(and the project, if it is not this workspace's). Apply what it returns and say when you "
                    + "depart from it. At the end, propose a record with `draft_episode`.\n\nTask: "
                    + (task == null ? "(not given)" : task);
            return new McpSchema.GetPromptResult("Start from the owner's memory",
                    List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String version() {
        String v = McpStdioRunner.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    /**
     * Reads stdin on its own thread and hands the SDK only lines that parse as JSON. A client must not
     * send anything else (MCP 2025-11-25, basic/transports), and the SDK's reader stops at the first
     * line it cannot parse, which would leave this process running after the client has gone. A bad
     * line is logged to stderr and dropped. End of input closes the pipe and counts down the latch.
     */
    private static InputStream jsonLinesOnly(InputStream in, CountDownLatch closed) {
        java.io.PipedInputStream forSdk = new java.io.PipedInputStream(1 << 20);
        java.io.PipedOutputStream toSdk;
        try {
            toSdk = new java.io.PipedOutputStream(forSdk);
        } catch (IOException e) {
            throw new IllegalStateException("could not open the stdin pipe", e);
        }
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader lines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        json.readTree(line);
                    } catch (IOException notJson) {
                        System.err.println("mcp: dropped a stdin line that is not a JSON-RPC message");
                        continue;
                    }
                    toSdk.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    toSdk.flush();
                }
            } catch (IOException ignored) {
                // stdin or the pipe closed: either way the session is over
            } finally {
                try {
                    toSdk.close();
                } catch (IOException ignored) {
                    // nothing left to close
                }
                closed.countDown();
            }
        }, "mcp-stdin");
        reader.setDaemon(true);
        reader.start();
        return forSdk;
    }
}
