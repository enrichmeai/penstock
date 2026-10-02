---
name: promote-pattern
description: Propose a reusable pattern from a task that just succeeded, by running scripts/promote-pattern.sh and carrying its draft in the task's own PR. Use as part of /compound when the bar below is met — never to merge a pattern directly.
---

# Promote a pattern (Penstock)

Part 3 of [#71](https://github.com/enrichmeai/penstock/issues/71). The script that does the
work is `scripts/promote-pattern.sh`; this skill is only "when to run it, and what to do with
what it prints." The draft it writes is **never** merged on its own — it rides in the task's
own PR, and the owner reviews it exactly like any other file in that diff.

## The bar — check this before running the script

Run `scripts/promote-pattern.sh` only when one of these holds, and say which in the PR body:

1. the task's issue carries the label `pattern-candidate`; or
2. the diff touches files that match **no** existing pattern's `signatures:` **and** `git log`
   on `main` shows a prior merged PR whose files overlap the same directories by at least half
   (the "built twice" test).

The script's own output ends with a `--- bar diagnostics ---` block that prints both of these
— the label check and a `built-twice test: N/M signature directories (P%)` line — but it does
**not** enforce the bar itself; it always generates a draft when asked. Reading those
diagnostics and deciding whether condition 1 or 2 actually held is this skill's job, not the
script's. An agent that proposes a pattern after every task trains the owner to stop reading
the proposals — only run this when the bar is met, and say so.

## Running it

```bash
bash scripts/promote-pattern.sh --id <id> --range <base>..<head> --issue <n> --dry-run
```

- `--id` — the pattern's directory name under `patterns/`, kebab-case.
- `--range` — almost always `<the branch's merge-base with main>..HEAD` for a task just
  finished; the `v0.2.0..v0.3.0` form in the measure below is only for regenerating a pattern
  that already shipped.
- `--issue` — the task's issue number. Drives the commit-scoping (only commits in `--range`
  whose message references `#<issue>` are used — see the script's own header comment for why)
  and, when `--request` is omitted, the request-card lookup: an existing `requests/*.yaml` card
  whose `issue:` field matches is reused; only when none exists does the script draft a new
  `requests/issue-<n>.yaml`.
- `--dry-run` first, always: it runs the same credential grep and clean-copy `check.sh` as a
  real run and prints the manifest, but writes nothing under `patterns/` or `requests/`.

Drop `--dry-run` to actually write the draft once its output looks right. The script itself
refuses to leave a bad draft in place — a credential-shaped value anywhere in the candidate
skeleton exits 2 before anything is written (CLAUDE.md § Gotchas: a secret printed is worse
than a secret never found), and a failing `check.sh` in a clean copy exits 1 unless `--keep`.

## What to put in the PR body

A `## Pattern proposed` section:

```
## Pattern proposed

<which bar condition held, and why>

<the script's own output, from --id through the bar diagnostics>
```

Then review the draft like any other file in the diff before asking the owner to look —
in particular:

- Every `TODO(owner)` the script left (in `manifest.yaml`'s `inputs:`, `PATTERN.md`'s
  sections, or `check.sh`'s behavioural-check line) is either filled in or explicitly flagged
  for the owner in the PR body. The mechanical draft cannot know which of its own
  placeholders are load-bearing.
- `manifest.yaml`'s `verified-against.tag` — the script only sets it when `--range`'s head is
  already on a tagged commit; otherwise it's `null` and the script says so. A pattern's
  `check.sh` is only as trustworthy as the release it was checked against
  (`patterns/README.md`), so a `null` tag is an owner decision before merge, not a detail to
  silently carry forward.
- `check.sh`'s javac step may print `SKIP (not run): skeleton references other com.example.*
  classes...` — that means the mechanical skeleton is coupled to the rest of this app (routine
  for a task that wired a new package into existing plumbing, not a sign of a broken draft).
  Trim the skeleton to what's genuinely standalone, or note in `PATTERN.md` that the coupling
  is intentional.

## Measure

```bash
bash scripts/promote-pattern.sh --id stdio-json-rpc-agent --range v0.2.0..v0.3.0 --issue 59 --dry-run
```

should print a draft whose `manifest.yaml` triggers and signatures each overlap the hand-built
`patterns/stdio-json-rpc-agent/` seed's by at least two-thirds, and whose generated `check.sh`
passes in a clean copy. `scripts/test-promote-pattern.sh` pins this, plus the credential-fixture
refusal under `scripts/test/credential-fixture/` — run it directly to check the script still
behaves after a change here:

```bash
bash scripts/test-promote-pattern.sh
```

## Never

- Never write to `patterns/` or `requests/` on `main` directly — the draft reaches `main` only
  through the task's own PR.
- Never run this on every task "just in case" — the bar above is the point.
- Never hand-edit a credential-shaped value out of a refused draft and re-run with `--keep` to
  force it through; fix the source file the skeleton was drawn from, or drop that file from the
  pattern.
