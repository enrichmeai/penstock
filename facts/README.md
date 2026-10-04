# facts/

One YAML file per durable belief: a fact about the owner's estate or taste, with where it came
from, how sure it is, what it replaced and when it was last confirmed. This is the "beliefs"
memory — slice 2 of [#85](https://github.com/enrichmeai/penstock/issues/85). Beliefs are
consolidated **from** episodes (`episodes/`, #86) by a step the owner reviews; a session that
reads this folder can act on them without re-reading history.

## Shape

```yaml
id: gcp-console-account-valuedocs-legal-bld     # equals the filename stem, kebab-case
statement: The GCP console for project valuedocs-legal-bld is used as joseph@valuedocs.co.in.
kind: account            # account | identifier | location-of-secret | convention | decision | principle
subject: gcp-console/valuedocs-legal-bld        # the key two facts collide on; free-form kebab path
scope: project:valuedocs                        # estate | project:<name> | repo:<owner/repo>
status: asserted         # asserted (by the owner) | inferred (by consolidate.sh) | superseded
confidence: 1.0          # 1.0 for asserted; inferred defaults to 0.7
provenance:
  - episode: 2026-10-03-valuedocs-cloud-sql-studio-walkthrough
    learned: the GCP console for project valuedocs-legal-bld is used as joseph@valuedocs.co.in   # the line it came from
  - owner: 2026-10-03
first_seen: 2026-10-03
last_confirmed: 2026-10-03
supersedes: ~            # id of the fact this replaced, or ~
superseded_by: ~         # set on the OLD fact when a new one replaces it; its status becomes superseded
triggers: [gcp, console, valuedocs-legal-bld, google account]   # keywords for recall
```

## Rules

- One belief per file. A `location-of-secret` fact names **where** a secret lives (a Secret
  Manager name, an environment variable name), never its value: `scripts/check-cards.sh` runs
  the credential grep over `statement` and `subject` and fails the fact by path.
- A fact is never edited to say something different. It is **superseded**: the new file names
  the old one under `supersedes`, the old file keeps its text with `status: superseded` and
  `superseded_by: <new id>`. `check-cards.sh` fails a one-sided pair and two active facts that
  share a `subject`. So the companion can say what it used to believe and when that changed.
- `asserted` is set only by the owner (a PR the owner opens or edits). `inferred` is what
  `scripts/consolidate.sh` writes; the owner promotes it by editing `status` in the same PR, or
  leaves it inferred.
- `last_confirmed` moves forward when a later episode's `learned` line restates an active fact;
  `consolidate.sh` proposes that bump.
- Every `provenance.episode` must be a file under `episodes/`, with a `learned:` beside it that
  equals (whitespace folded) one of that episode's `learned` lines; `check-cards.sh` fails
  either. That is how `consolidate.sh` knows a line is already a fact.
- PR-only, like every card.

## How a fact is proposed

Never typed in from scratch when an episode already says it:

```
scripts/consolidate.sh [--since <YYYY-MM-DD>] [--write] [--replace]
```

Dry-run by default. It collects every `learned` line of every episode (or those since `--since`)
that no fact's provenance cites and `facts/rejected/lines.yaml` does not list, and for each proposes one of:

| action | when | what it writes with `--write` |
|---|---|---|
| `new` | no active fact shares the subject | a new `inferred` fact (`confidence: 0.7`) |
| `confirm` | an active fact shares the subject and says the same thing | `last_confirmed` bumped, the episode added to `provenance` |
| `supersede` | an active fact shares the subject and says something else | a new fact with `supersedes: <old>`; the old file gets `status: superseded`, `superseded_by: <new>` |

`kind` and `subject` come from a small text rule table in `scripts/lib/consolidate_generate.py`
(an `@` → `account`; secret/password/token/key near a name → `location-of-secret`; an
identifier shape → `identifier`; never/always/must → `convention`; else `decision`); no model
call, no embedding. The script never decides which of two conflicting beliefs is true: a
`supersede` is a proposal printed as a pair, and the owner decides in the PR. The credential
grep runs over the raw `learned` lines before anything is printed and over every draft before
anything is written (exit 2, naming the episode and item or the draft and line, never the value);
writes stay under `facts/`; an existing id is never overwritten without `--replace`. A `confirm`
or `supersede` rewrites the old file with `yaml.safe_dump`, so a comment above the YAML (the seed
header) does not survive: the record is the fields, not the comment.

## Lines that are not beliefs

Some `learned` lines are events or one-off measurements ("the builder hit its turn cap on that
run"), pending tasks, or turn out to be wrong. The owner records each such decision once in
`facts/rejected/lines.yaml` (`episode`, the `learned` line verbatim, `reason`, `owner: <date>`), and
`consolidate.sh` never proposes that line again. `check-cards.sh` fails an entry whose episode is
missing or whose text is no longer one of that episode's learned lines. The file sits in a
sub-folder so nothing that reads `facts/*.yaml` mistakes it for a fact.

## Asking the record

```
scripts/fact.sh <word> [<word>…] [--all]
```

Prints the active facts whose `statement`, `subject` or `triggers` contain every word
(case-insensitive), with scope, status, confidence, `last_confirmed` and the first provenance;
`--all` includes superseded facts with their successor. Exits 1 when nothing matches, so a
session can test "do we already know this" before asking the owner.
