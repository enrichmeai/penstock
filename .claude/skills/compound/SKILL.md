---
name: compound
description: Turn what went wrong (or was learned) in the task just finished into a permanent guard, so the next task cannot repeat it. Use as the last step of every task before opening the PR — after the reviewer's verdict — and whenever the owner corrects you. Part of the /build-task loop.
---

# Compound: every mistake becomes a guard

A task is not finished when it works; it is finished when the next task is easier. This step is
short — usually one small addition, sometimes nothing — but it is never skipped silently.

## 1. Collect the lessons of this task
From this session only, list each of:
- every **reviewer finding** (BLOCKER/MAJOR/MINOR, UNVERIFIED usage, missing test);
- every **hook failure** (syntax, compile) that took more than one attempt;
- every **wrong assumption** — an API, flag, spec reading or version you had to correct;
- every **owner correction** in this session;
- anything that took **3 attempts** or ended BLOCKED.

## 2. For each lesson, pick the strongest guard that fits — in this order
1. **A test or an existing guard test extended** (an IT across the real boundary such as
   `IdentityPropagationIT` or `MemoryModeStartupIT`, a `RelaxedEnvBindingTest` case, a tool-layer
   traversal case) — the lesson becomes impossible to repeat. Prove it RED first (`redgreen-fix`).
2. **A hook check** in `.claude/hooks/` (`guard-destructive.sh`, `stop-fast-gates.sh`) — if a
   fast mechanical check would have caught it. Add its case to `test-hooks.sh`.
3. **A reviewer checklist line** in `.claude/agents/reviewer.md` — if only judgement catches it.
4. **A pinned doc URL** in CLAUDE.md § "Pinned docs" — if you had to search for the right doc.
5. **A CLAUDE.md rule** — one or two lines, usually under § Gotchas, with the date and the
   issue/PR number. Last resort, because prose is the weakest guard.
Skip a lesson only if an existing guard already covers it — name that guard.

## 3. Keep the guards lean
- Before adding a CLAUDE.md line, grep for an existing rule that says the same thing; sharpen it
  rather than adding a second one. Delete any rule this lesson proves wrong.
- A guard that changes process (reviewer, hooks, CLAUDE.md) goes in the **same PR** as the fix
  when small; otherwise its own PR titled `chore(compound): …`.

## 4. Record it
Add a `## Compound` section to the PR body: `lesson → guard added (file:line)` per lesson, or
`none — <why>` if the task went clean. The `/groom` board counts these, so the owner can see
the system getting stricter over time.
