# patterns/

One directory per reusable "how it was built". This is the "how" memory —
part 1 of [#71](https://github.com/enrichmeai/penstock/issues/71).

## Shape

```
patterns/<id>/
  PATTERN.md      when it applies, when it does not, the decision and why, what varies per use
  manifest.yaml   id, version, triggers: [keywords], signatures: [file globs or content markers],
                  inputs: [what the user must supply], verified-against: {tag, date}, supersedes
  skeleton/       the files that were right last time, with <placeholders> where a use differs
  check.sh        exits 0 when the skeleton, applied in a clean directory, still works
```

## Rules

- A pattern enters this directory only with a passing `check.sh` — run it before
  opening the PR and paste the output into the PR body.
- `verified-against:` in `manifest.yaml` names a release tag, not a branch or a
  commit on `main` — a pattern is only as trustworthy as the release it was
  last checked against.
- A pattern or its skeleton is changed only by PR.
- A skeleton never contains a credential value. `check.sh` greps its own
  skeleton for the obvious shapes (`sk-`, `ghp_`, `AKIA`, `-----BEGIN`,
  bare `password:`/`token:`/`secret:` followed by a non-placeholder value) and
  fails if it finds one.

## How a pattern is proposed

A pattern is never typed in by hand from scratch — `scripts/promote-pattern.sh`
scaffolds a draft from a finished task's own branch diff and issue, run only
when the bar in [`.claude/skills/promote-pattern/SKILL.md`](../.claude/skills/promote-pattern/SKILL.md)
is met (the issue carries `pattern-candidate`, or the diff's files match no
existing pattern and a prior merged PR already built the same area once). The
draft still reaches this directory only through that task's own PR, reviewed
like any other file in the diff — the script itself never writes to `main`.

## Episodes

A pattern is the distilled "how"; the "what happened" around it — the task that produced it,
what was decided and refused, what was learned — is an episode under [`episodes/`](../episodes/README.md)
(#86). `scripts/write-episode.sh` drafts one from the task's PR and commits; a `learned` line
there is the usual way a convention first gets written down before it becomes a fact.

## Staleness and use

A catalogue nobody prunes becomes the folklore it replaced (issue #81) — two
mechanisms keep it honest:

- **Staleness.** `scripts/check-cards.sh` runs on every push and PR (`build.yml`),
  and a weekly schedule plus every published release (`.github/workflows/pattern-check.yml`,
  an owner action — see that workflow's own header). It fails the build (`FAIL:`)
  when a manifest's `verified-against.tag` is null, and warns without failing
  (`STALE:`) when that tag is behind the newest release tag reachable from
  `origin/main`. The scheduled/release run opens or updates a single issue
  labelled `pattern-stale` with the failing output, and closes it with a
  comment once a later run is green again — so a pattern that silently stopped
  working, or was last checked against an old release, cannot sit quietly here.
- **Use.** Every turn that loads a pattern into its system prompt audits a
  `pattern.loaded` event (`AuditLogger.patternLoaded`). `GET /api/audit/patterns`
  aggregates those into loads, last-loaded time and versions seen, per pattern
  id — including every id in this directory that has never been loaded, listed
  last. Run `scripts/pattern-digest.sh [base-url]` against a running instance
  (it reads `AGENT_AUTH_USERNAME`/`AGENT_AUTH_PASSWORD` from the environment,
  the same credentials documented in the root `README.md` — never pass the
  password as an argument) to read it as a table instead of raw JSON.

A pattern unused for a quarter, per that digest, is a candidate for removal —
always by a PR that a person reviews, never automatically.

## Review by grant

The store (`requests/`, `references/` and this directory) is mirrored into the owner's own
[Cistern](https://github.com/enrichmeai/cistern) pod under `/memory/` by
`scripts/memory-publish.sh` — no copy leaves the owner's side. A reviewer (a colleague, or a
hosted session) is granted into that pod by `scripts/memory-grant.sh reviewer <webid> --as
<slug>`: **read** on `/memory/patterns/` only — never on `/memory/requests/` or
`/memory/references/`, whose request cards carry the owner's own reasons and refused options —
and **read + write** on their own `/memory/verdicts/<slug>/`, nowhere else. Every read and every
refusal is receipted on the owner's side (`scripts/memory-revoke.sh` prints the query to see
them); `scripts/memory-revoke.sh <webid> --as <slug>` ends the grant in one command.

A verdict is one file per review, at `/memory/verdicts/<slug>/<pattern-id>-<YYYY-MM-DD>.yaml`:

```yaml
pattern: stdio-json-rpc-agent
version: 1              # the manifest version reviewed
verdict: keep           # keep | revise | retire
reasons:
  - one line each, specific
reviewer: https://…/profile#me
date: 2026-10-03
```

**A verdict changes nothing by itself.** `keep` needs no follow-up; `revise` and `retire`
become a PR (or a `/new-issue`) opened by the owner after reading the verdict — the pod holds
the opinion, this repository holds the decision. `scripts/pattern-digest.sh --pod` adds a `last
verdict` and a `reads` column, read with the owner's own token straight from the pod's
receipts, next to the local use digest above.

Reading a pattern through MCP rather than plain HTTP currently gets `PATTERN.md` as text and
`manifest.yaml` only as a byte count, until Cistern's media-type/MCP-text fix
([enrichmeai/cistern#218](https://github.com/enrichmeai/cistern/issues/218)) ships — a reviewer
reading `manifest.yaml` through `curl` or the `pod` tool gets the bytes either way; this is a
caveat for an MCP-based reviewer, not a blocker.
