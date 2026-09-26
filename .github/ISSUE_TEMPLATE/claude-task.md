---
name: Claude task
about: A task for the Claude workflow. Creating it does not start anything. Review it, then add the `claude` label (owner's account only).
labels: []
---

<!-- enrichmeai/cistern uses the same sections, shaped to that repo. -->

## Goal

<!-- One sentence: the behaviour that will be true when this is done. -->

## Spec

<!-- The external contracts this relies on: an LLM provider's API (Anthropic, OpenAI, Copilot,
     Ollama), the Cistern pod surface CisternTool calls, Flyway, Spring Security. Write "none"
     when it touches only Penstock's own code. -->

## Measure

<!-- Every task names its number, or says why it has none: a test count, a Trivy total, tokens
     per turn from agent-validate, a metric from /actuator/prometheus. -->
- Metric:
- Baseline, measured on main (command or run ID):
- Target:
- How it is read after merge (test, CTH run, CI job):

## Evidence of done

<!-- Each line is checkable by someone who only reads the PR and its CI runs. -->
- [ ] Red proof: the test or guard that fails on main, quoted
- [ ] Green proof: the same test passing on the branch, quoted
- [ ] The metric re-measured on the branch, beside its baseline
- [ ] The sandbox invariants hold (WorkspacePath, the shell block-list, GitTool's whitelist)

## Risk

<!-- Exactly one. -->
- [ ] docs: only *.md, not CLAUDE.md
- [ ] code: application code and tests
- [ ] infra: Dockerfile, compose or deploy config (Claude prepares; the owner runs it)
- [ ] release: versions, publishing, tags (Claude prepares; the owner tags)

## Surface

<!-- Modules and files expected to change. -->

## Out of scope

<!-- What must NOT change in this task. -->

## Stop and ask if

<!-- Conditions where Claude replies with a question instead of continuing. -->
- the sandbox would have to loosen (WorkspacePath, the shell block-list, GitTool)
- a test would have to be relaxed or a permission widened
- the change needs a real provider key, a running pod, a release or a tag
- the surface grows beyond what is listed above
