# episodes/

One YAML file per piece of work: what was asked, what was built, what was decided and why,
what was refused and why, what was learned, and what is still open. This is the "what
happened" memory — slice 1 of [#85](https://github.com/enrichmeai/penstock/issues/85). A
human's mental model starts from episodes like these; beliefs (`facts/`, slice 2) are
consolidated **from** them, and a session that reads this folder can say what was built last
week and why without a chat transcript.

## Shape

`episodes/<YYYY-MM-DD>-<slug>.yaml`:

```yaml
id: 2026-10-03-memory-5-review-by-grant     # equals the filename stem
format: 1                                    # memory format v1 (docs/memory-format.md)
visibility: public                           # private | shareable | public
date: 2026-10-03                             # equals the filename's date
project: penstock                            # see § Projects
asked: one sentence, the request as the owner put it
issue: enrichmeai/penstock#82                # optional
built:
  - pr: enrichmeai/penstock#84               # owner/repo#n
    merged: 71b2a27
    files: [scripts/memory-publish.sh, scripts/memory-grant.sh]
decided:
  - what: a reviewer never gets read on request cards
    why: they carry the owner's reasons and refused options
refused:
  - what: counting reads from the container's receipts
    why: a resource's receipts never cover its children
learned:                                     # candidates for facts/ — references only, never a value
  - the verdict container must be created as text/turtle; curl's default type gets a 409
open:
  - the two-different-days measure on a real task is still unrun
patterns: [stdio-json-rpc-agent]             # ids loaded or produced, optional
commits:                                     # optional, what the draft was built from
  - 71b2a27 memory 5: review by grant (#84)
```

## Projects

`estate` (owner-wide, no single project) or a project. A memory root whose `memory.yaml` has a
`projects:` block declares its own projects (#112, `docs/memory-root.md` § Projects and who sees
them), and `scripts/check-cards.sh` and `scripts/write-episode.sh --root` read that list. Without a
block the list is `penstock`, `cistern`, `valuedocs`, `site`. The schema checks only the name's
shape (`[a-z0-9-]`); the checker and `write-episode.sh --project` check the list.
`.claude/hooks/episode-draft.sh` still guesses the project from the checkout's folder name among the
default four only.

## Learned lines become facts

A `learned` line is a fact candidate. `scripts/consolidate.sh` (#87) reads every `learned` line
no fact yet cites and proposes a new fact, a confirmation of an existing one, or a supersede when
the line contradicts an active fact on the same subject; the proposals ride a PR the owner
reviews. Write a `learned` line as the belief you would want recalled next time, by reference:
"the console for X is used as Y", "the secret NAME holds Z", "never do W because V".

## What a turn recalled

With `agent.memory.enabled=true`, a Penstock turn loads the project's latest episodes (asked,
decided, refused, open) into its system prompt before the first model call, and audits each as
`episode.loaded` (#88). `scripts/write-episode.sh --session <id>` (or `--audit <file>`) reads that
session's audit log and writes the loaded pattern ids under `patterns:` and the loaded fact and
episode ids under an optional `recalled:` list (`fact:<id>`, `episode:<id>`), so the episode
says what the session already knew. A fact recalled is not a fact learned.

## Rules

- One episode per piece of work. It is written on the day and not edited afterwards to say
  something different; a later episode supersedes it, and an `open` line is closed by a later
  episode that names it.
- A `learned` line is a fact **by reference**: an account name, a secret's *name* and where it
  lives, a project id, a convention. Never a credential value. `scripts/check-cards.sh` runs
  `scripts/lib/credential-grep.sh` over every `learned`, `decided`, `refused` and `open` line
  and fails the episode by path, never printing the value.
- `check-cards.sh` also fails an episode whose `id` or `date` disagrees with its filename, whose
  `project` is not in the list above, whose `built[].pr` is not `owner/repo#n`, or whose
  `decided`/`refused` entries lack `what:` and `why:`.
- An episode reaches `main` by PR, like every other card.

## How an episode is written

Never from scratch. `scripts/write-episode.sh --project <p> --issue <n> --range <base>..<head>`
drafts it from the task's own evidence: the issue title (`asked`), the pull request that
contains the range's head (`built`, with its merged sha and files), the commits in the range
that mention `#<n>`, and the markers people leave in commit bodies and the PR body:

```
Decided: <what> -- <why>
Refused: <what> -- <why>
Learned: <text>        (or  fact: <text>)
Open: <text>
```

The PR body's `## Known limits` bullets become `refused` entries. The credential grep runs
before anything is written. The draft lands in `episodes/` (`--dry-run` prints it instead); the
owner edits it and commits it in the task's PR. An existing episode is never overwritten
without `--replace`. A Penstock session's audit log becomes an input in slice 3 of #85. The Stop
hook `.claude/hooks/episode-draft.sh` prints the draft command after a session that committed on
a task branch; it never runs the script and never writes. A `Why:` line must sit on the same
line as its `Decided:`/`Refused:` after ` -- `; a `Why:` on its own line is not harvested.
