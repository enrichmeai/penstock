# The memory root

[#91](https://github.com/enrichmeai/penstock/issues/91), slice 5 of [#85](https://github.com/enrichmeai/penstock/issues/85).
The cards follow [memory format v1](memory-format.md).

A memory root is a folder of cards. There are two layouts:

| Layout | When | Sections |
|---|---|---|
| **flat** | the root has no `projects/` folder (this repository) | the root itself: `requests/`, `references/`, `patterns/`, `episodes/`, `facts/` |
| **sectioned** | the root has `projects/` (the owner's private `enrichmeai/memory`) | `estate/` (owner-wide) plus one `projects/<name>/` per project, each holding the same five folders |

```
<root>/
  memory.yaml        format: 1, visibility (private for the owner's root)
  estate/            facts/ references/ …        owner-wide: accounts, conventions, principles
  projects/<name>/   requests/ references/ patterns/ episodes/ facts/
```

The layout is decided by the folders alone: a root with `projects/` is sectioned. A `layout:` line
in `memory.yaml` is informational and no tool reads it.

A card's folder follows its scope: a fact scoped `project:valuedocs` lives in
`projects/valuedocs/facts/`, an estate-wide one in `estate/facts/`. The checker enforces this,
because recall loads a project's folder by name: a valuedocs fact under `projects/cistern/` would
reach cistern sessions.

## Choosing the root

Every tool reads the cards from, in order:

1. `--root <dir>` on the command line;
2. the `MEMORY_ROOT` environment variable;
3. this repository (unchanged behaviour for anyone who does not opt in).

The tools themselves (`scripts/lib/`, `schema/`) always come from penstock; only the cards come
from the root.

## What reads it today

| Tool | Sectioned root |
|---|---|
| `scripts/check-cards.sh` | First the rules across sections, each a FAIL: `memory.yaml` and `estate/` exist; no card folder sits at the root; every `projects/<name>` is one of penstock, cistern, valuedocs, site; a fact's scope matches its folder (`estate`, or `project:<name>`); an episode under `projects/<name>/` has `project: <name>`; ids (per kind) and active subjects are unique across sections. Then `estate/` and each `projects/<name>/` in turn, against the root's `memory.yaml`, under a `### section <path>` header; every PASS/FAIL/WARN/STALE line about a card gives the card's path from the root (`FAIL: projects/cistern/facts/x.yaml …`). Cross-references (provenance, supersede, rejected lines) resolve within one section. Staleness compares patterns with penstock's latest tag. |
| `scripts/fact.sh` | Searches `estate/facts/` and every `projects/*/facts/`; each match names its section. |
| `scripts/memory-publish.sh` | Mirrors the whole root to the pod after `check-cards.sh --root` passes: one `cistern sync` per card folder in `estate/` and each known `projects/<name>/`, to the same path under `/memory/` (`estate/facts` → `/memory/estate/facts/`). The pod is the owner's, so episodes and facts go too, sub-folders included (`facts/rejected/lines.yaml`). A re-run with nothing changed sends nothing. A folder in a section that is not a card folder is reported and skipped. `--delete` prunes inside the published containers only: a folder deleted locally stays on the pod until removed by hand. |
| `scripts/memory-grant.sh`, `memory-revoke.sh` | `reviewer … --project <name>` grants read on `/memory/projects/<name>/patterns/` and nothing above it (another project, the estate, facts and episodes stay closed), plus read and write on `/memory/verdicts/<slug>/`. Revoke takes the same `--project`. The `agent` form still grants `/memory/`, which after a sectioned publish is **every project's facts and episodes**: grant it only to an agent that acts as you. |
| `PatternCatalog` (recall in the turn, #88) | Loads `estate/` plus `projects/<project>/` for the workspace's project (`agent.memory.project`, or the workspace directory's name), never another project's section. With no project resolved, the estate only. Point `agent.memory.root` at the clone. |

## Writing into a root

| Tool | Sectioned root |
|---|---|
| `scripts/write-episode.sh --root` | The draft goes to `projects/<project>/episodes/` (or `estate/episodes/` for `--project estate`) and takes the root's `memory.yaml` visibility. `--out` is confined to that folder. Git and `gh` still read this repository, so an episode about another repository's work is drafted from its PR and issue, or filled in by hand. |
| `scripts/consolidate.sh --root` | One section at a time: a section's episodes propose facts into that section's `facts/`, scoped `project:<name>`, or `estate` under `estate/` (whatever project the episode names), with the root's `memory.yaml` setting visibility. With `--write`, every section is checked first: a credential-shaped learned line in any section stops the run (exit 2, naming `projects/<name>/episodes/<file>`) before any section is written. Otherwise the first failing section's exit code is kept. |

A relative `--root` or `MEMORY_ROOT` is resolved from the directory the command runs in.

## Not built yet (the rest of #91)

- `promote-pattern.sh` still writes into this repository: its draft is built from this
  repository's git history and request cards, and splitting those from the destination is its own
  change.
- The second-machine publish: a fresh clone has no `.cistern-sync.json`, and `cistern sync` on
  Cistern `main` has no `--adopt` yet (enrichmeai/cistern#219), so it cannot publish over a pod that
  already holds the cards.
- A fact in `estate/` cannot yet cite an episode in a project section: provenance resolves within
  one section.
- `pattern-digest.sh` and `test-memory-pod.sh` still read this repository's layout.
- **Open decision before `memory-migrate.sh`:** penstock's own `facts/` holds `repo:enrichmeai/penstock`
  and `project:cistern` facts. The checker requires `estate` or `project:<name>` by folder, so a
  migration must either place each fact by its scope or the checker must accept `repo:` scopes under
  the matching project. Until then it fails closed.
- `memory-migrate.sh` (moving cards between this repository and a root) and a template for a new
  memory root.
- A `MemoryRetrievalIT` case on a sectioned fixture (`PatternCatalogTest` covers the sections today).
- A CLAUDE.md pointer in each product repository ("your memory is `enrichmeai/memory`; check it
  before asking the owner"), raised as an issue in each repository.

## Using it from a checkout

```bash
git clone https://github.com/enrichmeai/memory ../memory      # private: the owner's access
export MEMORY_ROOT=../memory
scripts/fact.sh console account          # what do I already know?
scripts/check-cards.sh                   # is every card valid?
# and for Penstock's recall in the turn:
AGENT_MEMORY_ENABLED=true AGENT_MEMORY_ROOT=../memory AGENT_MEMORY_PROJECT=valuedocs ./gradlew bootRun
```
