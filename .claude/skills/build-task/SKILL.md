---
name: build-task
description: The autonomous build loop for ONE task — spec → verify docs → red test → implement (hooks check each edit) → reviewer agent → fix (max 3 attempts) → /compound → PR. Use for "build #N", "take the next task", "work on X", or when the owner hands over a task to do unattended. With no argument, takes the top penstock item of the Claude queue on the Build board in enrichmeai/cistern.
---

# /build-task — one task, start to reviewed PR

One task per session: a fresh session per task keeps context small. If the owner asks for a
second task, finish or park this one first.

## 0. Pick and pin the spec
- No argument: take the first `penstock` item of **Claude queue** on the Build board
  (`gh issue list -R enrichmeai/cistern --label board`; `/groom` keeps it for both repos).
  Argument: that issue.
- Run `pr-sweep` first: an open PR may already do this, and this checkout may be shared.
- Write the spec down before anything else: acceptance criteria (from the issue or its
  `Grooming` comment), the packages it touches, the external contracts it relies on, and how a
  test proves each criterion. Missing or ambiguous → comment the question on the issue, stop,
  and report. Do not guess scope.
- Cross-repo task (anything that needs new pod behaviour from Cistern): one session per repo, so
  build only this repo's half. The Cistern half becomes a `/new-issue` in `enrichmeai/cistern`,
  linked both ways, and Cistern lands first. If this half depends on it, stop at the red test and
  say so.

## 1. Set up
A worktree or branch off `origin/main`, one concern per branch. Record the branch you found and
restore it when you finish (CLAUDE.md § Gotchas: shared checkout). Run `./bootstrap.sh` once if
`gradle/wrapper/gradle-wrapper.jar` is missing. In the GitHub Action the wrapper is already
fetched and the checkout is a fresh `claude/` branch: use it as is.

## 2. Verify before writing (CLAUDE.md § "Autonomous build loop")
For every library/API/provider endpoint/config key the change will use: open its entry in
CLAUDE.md § "Pinned docs" with WebFetch, at the version `build.gradle` resolves. Note the URL you
used — the reviewer re-checks it. Not pinned → the vendor's official docs only, then add it to
the pinned list in `/compound`. A provider fixture is captured from the real API, never invented.

## 3. Red, then green
Follow `redgreen-fix`: write the test that proves the first criterion and show it failing for the
right reason (the report under `build/reports/tests/test/`, not just "FAILED"). Then implement.
The PostToolUse hook syntax-checks each edited JSON/YAML/Python/shell file; the Stop hook runs
`compileTestJava` and the Flyway sibling check before the turn ends. Fix what they report at once.

## 4. Gates — a task is not done until these pass
Run the fast gates for what changed (CLAUDE.md § "Autonomous build loop") and quote the result
lines with real totals. The full `./gradlew build` runs in CI: push the branch, open the PR as
**draft**, and read the `build` run — never claim a suite you have not seen green.

## 5. Review
Launch the `reviewer` agent with the spec from step 0, **in the foreground**, and wait for its
verdict: the hooks enforce it (`guard-task.sh` denies `run_in_background`, and the stop hook
refuses to end the turn while a review is pending — issue #78). Fix every BLOCKER/MAJOR and every
UNVERIFIED item; answer MINORs in one line each (fixed / why not). Re-run the reviewer after fixing.

## 6. The 3-attempt cap
An **attempt** is one fix-and-recheck cycle against the same failing gate or the same reviewer
finding. After the 3rd failed attempt, stop changing code and post on the issue (and in your
reply) a **Blocker summary**:
```
Blocked: <gate or finding>
Tried: 1. … 2. … 3. … (what each changed, what it showed)
Evidence: <error lines, doc URLs, run IDs>
Hypothesis: <best guess at the root cause>
Needs: <the decision, access or information that would unblock it>
```
Leave the branch pushed and the PR draft. Do not widen scope to route around the blocker.

## 7. Compound, then hand over
Run the `/compound` skill. Then mark the PR ready and end with: branch, head SHA, files changed,
gates run with their results, reviewer verdict, Compound lines, and anything for the owner.
Commits are `git commit -s`, conventional, with no AI co-author trailer (cistern's `land-pr`
covers this repo too). The owner merges; releases go through `ship-check` and `cut-release`, which are the owner's.
