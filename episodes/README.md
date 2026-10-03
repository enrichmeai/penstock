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

`penstock`, `cistern`, `valuedocs`, `site`, `estate` (owner-wide, no single project). The list
is closed and mirrored in `scripts/check-cards.sh`; adding one is a PR that changes both.

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
