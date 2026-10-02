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
