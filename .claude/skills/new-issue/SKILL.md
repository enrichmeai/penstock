---
name: new-issue
description: Turn a rough idea, bug report or feature request into ready-to-build GitHub issue(s) in the right repo (cistern or penstock), in the claude-task template's shape, de-duplicated, split to S/M size and labelled. Use for "new issue", "log this", "create a task/feature for …", "turn this into issues", or when the owner describes work in a sentence.
---

# /new-issue — from a sentence to ready issue(s)

The definition of **ready** is the `claude-task` template (`.github/ISSUE_TEMPLATE/claude-task.md`,
the same in both repos): Goal · Spec · Measure · Evidence of done · Risk · Surface · Out of scope ·
Stop and ask if. `/groom` and `/build-task` both rely on it, so every issue this skill writes fills
every section — or says in the section why it cannot yet. Run `pr-sweep` first: several sessions
share this repo, and an open PR may already do it. This skill is the same in `enrichmeai/cistern`,
where `/groom` keeps the one Build board for both repos.

## 1. De-duplicate first
Search both repos, open AND closed:
`gh search issues --repo enrichmeai/cistern --repo enrichmeai/penstock "<key words>" --limit 20`
plus `gh issue list -R <repo> --search "<words> in:title,body" --state all`, and for a cistern
change grep cistern's `docs/BACKLOG.md` for a T-ticket that already covers it.
- An open near-duplicate → add the new information as a comment there; do not create.
- A closed one → read why it closed. Fixed and regressed → new issue that links it. Declined by a
  ruling → tell the owner, do not create.

## 2. Place it
- The pod: LDP, RDF, storage, WAC, Solid-OIDC, the MCP server, the CLI, the starter, CTH →
  `cistern`.
- The agent: the tool loop, LLM providers, tools (including `CisternTool`, the `pod` tool),
  sessions, auth in front of the agent, releases of the Penstock image → `penstock`.
- Both → one issue per repo, each linking the other. Cistern's half lands first when Penstock
  consumes a new pod behaviour; Penstock's half lands first when it is a pure consumer fix.

## 3. Size and split
- **S/M** (≤2 days, one concern): one issue.
- **Bigger:** a parent issue (goal + ordered checklist) plus sub-issues, each S/M, each
  independently shippable and testable, in build order.
- **A new capability with open design questions:** write the design down first (a
  `docs/adr/` note or `ROADMAP.md` entry for Penstock; the BMad planning skills in cistern), get
  the owner's ruling, then come back here once per story.

## 4. Write it (claude-task template)
- **Goal** — one sentence, the behaviour that will be true.
- **Spec** — the spec sections and CTH features (cistern), or "none".
- **Measure** — metric, baseline with the command that measured it (or why none).
- **Evidence of done** — the red test/guard that fails on main today, named concretely; the
  green proof; the re-measure.
- **Risk** — exactly one of docs / code / infra / release.
- **Surface** — modules and files, found by grepping the code, not guessed.
- **Out of scope** and **Stop and ask if** — including every question you could not answer from
  the code, the spec or an owner ruling. Never invent an answer to fill a section.

## 5. Label, never dispatch
`wave:W1|W2|W3` (W1 only if the next release needs it — say why in one line), the owner
(`claude-ready` / `founder` / `mac-session`), and existing labels (`gh label list`), such as
cistern's `phase-*` and `ticket`. **Never** add the `claude` label: that starts a paid build and
is the owner's call.

## 6. Draft or create
Default: show the drafts and wait for "go". When the owner said "create" (or "just log it"):
`gh issue create -R enrichmeai/<repo> --title … --body-file … --label …`, sub-issues linked to
the parent. Reply with the links, the wave/owner each got, and anything under "Stop and ask if".
