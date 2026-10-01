package com.example.agent.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.example.agent.tools.CisternTool;
import com.example.agent.tools.EditFileTool;
import com.example.agent.tools.GitTool;
import com.example.agent.tools.ShellTool;
import com.example.agent.tools.WorkspacePath;
import com.example.agent.tools.WriteFileTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Wraps the five mutating tools reachable in ACP mode with {@link AcpGatedTool}, so each call
 * goes through {@link PermissionGate} before it runs. Only active on the {@code acp} profile;
 * web mode never sees these beans, and {@code ToolRegistry} (see {@link PermissionGatedTool})
 * always prefers them over the always-registered raw tool bean when both are present.
 *
 * <p>{@code title} is the tool name plus its main argument (a path or a command), truncated —
 * CLAUDE.md issue #62 "Surface". {@code locations} is set only for the file tools (an absolute
 * path under {@code agent.workspace}); {@code shell}/{@code git}/{@code pod} act on things that
 * are not a single workspace file, so their {@code locations} is empty.
 */
@Configuration
@Profile("acp")
class AcpToolPermissionConfig {

    private static final int TITLE_MAX = 80;

    @Bean
    AcpGatedTool acpGatedWriteFileTool(WriteFileTool delegate, PermissionGate gate, Path agentWorkspace) {
        return new AcpGatedTool(delegate, gate, AcpSchema.ToolKind.EDIT, args -> true,
                args -> title("write_file", pathOf(args)),
                args -> locationsFor(agentWorkspace, pathOf(args)));
    }

    @Bean
    AcpGatedTool acpGatedEditFileTool(EditFileTool delegate, PermissionGate gate, Path agentWorkspace) {
        return new AcpGatedTool(delegate, gate, AcpSchema.ToolKind.EDIT, args -> true,
                args -> title("edit_file", pathOf(args)),
                args -> locationsFor(agentWorkspace, pathOf(args)));
    }

    @Bean
    AcpGatedTool acpGatedShellTool(ShellTool delegate, PermissionGate gate) {
        return new AcpGatedTool(delegate, gate, AcpSchema.ToolKind.EXECUTE, args -> true,
                args -> title("shell", stringOf(args, "command")),
                args -> List.of());
    }

    @Bean
    AcpGatedTool acpGatedGitTool(GitTool delegate, PermissionGate gate) {
        return new AcpGatedTool(delegate, gate, AcpSchema.ToolKind.EXECUTE, args -> true,
                args -> title("git", gitArgSummary(args)),
                args -> List.of());
    }

    /** Only the {@code write} operation is gated — reads stay unprompted, as in part 1. */
    @Bean
    AcpGatedTool acpGatedCisternTool(CisternTool delegate, PermissionGate gate) {
        return new AcpGatedTool(delegate, gate, AcpSchema.ToolKind.EDIT,
                args -> "write".equals(stringOf(args, "type")),
                args -> title("pod", pathOf(args)),
                args -> List.of());
    }

    private static String pathOf(Map<String, Object> args) {
        return stringOf(args, "path");
    }

    private static String stringOf(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : v.toString();
    }

    private static String gitArgSummary(Map<String, Object> args) {
        String sub = stringOf(args, "subcommand");
        Object extra = args.get("args");
        return extra instanceof List<?> list && !list.isEmpty() ? sub + " " + list : sub;
    }

    private static String title(String toolName, String mainArgument) {
        return truncate(toolName + " " + mainArgument, TITLE_MAX);
    }

    private static List<AcpSchema.ToolCallLocation> locationsFor(Path workspace, String path) {
        if (path == null || path.isBlank()) return List.of();
        try {
            Path resolved = WorkspacePath.resolve(workspace, path);
            return List.of(new AcpSchema.ToolCallLocation(resolved.toString(), null));
        } catch (RuntimeException e) {
            // Permission is still asked with a best-effort title; the real tool call (if
            // permitted) repeats this same resolution and reports the error properly.
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
