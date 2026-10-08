package com.example.agent.mcp;

import com.example.agent.config.AgentProperties;
import com.example.agent.memory.ContextAssembler;
import com.example.agent.memory.PatternCatalog;
import com.example.agent.service.AuditLogger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * The MCP {@code recall} tool (#116): the "Owner's memory" block a Penstock turn would put in front
 * of its model for this question, built by the same {@link PatternCatalog} and
 * {@link ContextAssembler}, so the project's audience, uses and personal rules (#112) apply
 * unchanged. A fresh catalogue per call picks up edited cards and a different project's rules.
 */
public class MemoryRecall {

    private final AgentProperties base;
    private final AuditLogger audit;

    public MemoryRecall(AgentProperties base, AuditLogger audit) {
        this.base = base;
        this.audit = audit;
    }

    /** The block, or a sentence saying why there is none; {@code error} when the request was refused. */
    public record Result(boolean error, String project, String text, String outcome) {
        static Result refused(String project, String why) {
            return new Result(true, project, why, "refused");
        }
    }

    public Result recall(String requestedProject, String question, String user, String session) {
        if (!base.getMemory().isEnabled()) {
            return Result.refused("", "Memory is off for this server (AGENT_MEMORY_ENABLED=false).");
        }
        if (question == null || question.isBlank()) {
            return Result.refused("", "question is required.");
        }
        String project = requestedProject == null || requestedProject.isBlank()
                ? configuredProject() : requestedProject.trim();
        if (!project.isEmpty() && !EpisodeDrafts.NAME.matcher(project).matches()) {
            return Result.refused(project, "project must be a project name ([a-z0-9-]), never a path.");
        }
        AgentProperties forProject = copyFor(project);
        PatternCatalog catalog = PatternCatalog.loadedNow(forProject);
        Path workspace = base.getWorkspace() == null ? null : Paths.get(base.getWorkspace()).toAbsolutePath().normalize();
        ContextAssembler assembler = new ContextAssembler(catalog, workspace, forProject);

        for (PatternCatalog.SectionDecision d : catalog.sectionDecisions()) {
            audit.sectionDecided(user, session, d.section(), d.loaded(), d.reason());
        }
        Optional<ContextAssembler.Assembled> assembled = assembler.assemble(question);
        if (assembled.isEmpty()) {
            return new Result(false, assembler.project(), "No card in this project's memory matched the question.", "empty");
        }
        ContextAssembler.Assembled a = assembled.get();
        a.loadedFacts().forEach(f -> audit.factLoaded(user, session, f.id(), f.status(), f.confidence()));
        a.loadedEpisodes().forEach(e -> audit.episodeLoaded(user, session, e.id(), e.date()));
        a.loadedPatterns().forEach(p -> audit.patternLoaded(user, session, p.id(), p.version()));
        return new Result(false, assembler.project(), a.block(), "ok");
    }

    private String configuredProject() {
        Path workspace = base.getWorkspace() == null ? null : Paths.get(base.getWorkspace()).toAbsolutePath().normalize();
        return ContextAssembler.resolveProject(workspace, base);
    }

    /** The configured memory settings, with the project replaced when one is given. */
    private AgentProperties copyFor(String project) {
        AgentProperties p = new AgentProperties();
        p.setWorkspace(base.getWorkspace());
        AgentProperties.Memory m = p.getMemory();
        AgentProperties.Memory b = base.getMemory();
        m.setEnabled(b.isEnabled());
        m.setRoot(b.getRoot());
        m.setSignatureDepth(b.getSignatureDepth());
        m.setMaxFacts(b.getMaxFacts());
        m.setMaxEpisodes(b.getMaxEpisodes());
        m.setProjectAliases(b.getProjectAliases());
        m.setProject(project == null ? b.getProject() : project);
        return p;
    }
}
