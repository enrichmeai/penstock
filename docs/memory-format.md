# Memory format, version 1

**Design: Joseph Antony Aruja, 2026-10-04.** Specified and built in [#98](https://github.com/enrichmeai/penstock/issues/98), a slice of [#85](https://github.com/enrichmeai/penstock/issues/85).

> We need db/file storage for permanent memory and permissions, how the human brain builds
> boundaries, but we also need the LLM as brain. Should we also define how we store what we
> need as a memory so that it is not LLM dependent, and a transformation layer can always build
> the context you need? (the designer, 2026-10-04)

## The idea

Three parts, kept apart on purpose:

| Part | What it is | Where it lives |
|---|---|---|
| **Memory** | What happened, what is believed and why, how things are done, what was trusted, what was wanted | Cards: plain YAML files the person owns |
| **Boundaries** | Who may see each card | A `visibility` field on every card, checked by `check-cards.sh` today; enforcement by the store (Cistern) and by renderers is planned (see below) |
| **Thinking** | Reasoning over what is in front of it | Any LLM, hosted or local |

Between memory and thinking sits a **transformation layer**: renderers that turn the same cards
into whatever context a given model, tool or person needs. The memory never depends on a model,
so a model can be replaced without losing anything the person has built. The record is the
durable thing; tools and models are replaceable.

## Card kinds

Each kind has a JSON Schema (draft 2020-12) under [`schema/`](../schema/). Every card carries
`id`, `format` and `visibility`; nothing else is allowed beyond the fields its schema defines.

| Kind | Path | Schema | Holds |
|---|---|---|---|
| episode | `episodes/<YYYY-MM-DD>-<slug>.yaml` | [`episode.schema.json`](../schema/episode.schema.json) | asked, built, decided, refused, learned, open ([episodes/README.md](../episodes/README.md)) |
| fact | `facts/<id>.yaml` | [`fact.schema.json`](../schema/fact.schema.json) | one belief, with kind, subject, scope, status, confidence, provenance, supersede links ([facts/README.md](../facts/README.md)) |
| pattern | `patterns/<id>/manifest.yaml` | [`pattern.schema.json`](../schema/pattern.schema.json) | how something was built, its triggers and the release it was verified against ([patterns/README.md](../patterns/README.md)) |
| reference | `references/<id>.yaml` | [`reference.schema.json`](../schema/reference.schema.json) | a trusted source, what it holds, when it was read, or, while it cannot be reached, `unreachable:` saying why ([references/README.md](../references/README.md)) |
| request | `requests/<id>.yaml` | [`request.schema.json`](../schema/request.schema.json) | what was wanted and why, what done looks like, what was ruled out ([requests/README.md](../requests/README.md)) |

`facts/rejected/lines.yaml` (learned lines reviewed and judged not to be beliefs) is a ledger,
not a card; `check-cards.sh` checks it separately.

## The two fields every card carries

```yaml
id: shell-python-heredoc-is-stdin
format: 1          # the major version of this format
visibility: public # private | shareable | public
```

- **`format`** is the major version, an integer. A card carries no minor version, so the rules are:
  - **Validation** is always against the *current* schema of that major (the files under
    `schema/` on `main`). An addition within a major (a new optional field, a new enum value)
    updates the schema and is valid from then on; cards and schema move together in one PR.
  - **A reader that only renders** (a renderer, a tool reading a shared card) ignores fields it
    does not know, so an older reader keeps working on a newer card of the same major.
  - Removing or renaming a field, or changing its meaning, is a new major. A checker refuses a
    major it does not know, by path, rather than guess; the spec for the new major says how to read
    the old one.
- **`visibility`** is the card's boundary:
  - `private`: the owner only.
  - `shareable`: people or agents the owner grants, each read under the reader's own identity
    and receipted (Cistern's access control, [#84](https://github.com/enrichmeai/penstock/issues/84)).
  - `public`: anyone.

  A store or repository declares its own visibility in `memory.yaml`. A card narrower than its
  container fails `check-cards.sh` (since [#91](https://github.com/enrichmeai/penstock/issues/91)
  gave private cards a home: the owner's private `enrichmeai/memory` repository).
  **A fact is never wider than an episode it was learned from**: `check-cards.sh` fails a public
  fact that cites a private episode. Writers default to `private` for anything outside the
  container's own project (and for everything when no `memory.yaml` is declared); a fact also
  takes the narrowest of its scope default and its source episode. The owner widens by hand.

  `facts/rejected/lines.yaml` is a ledger, not a card, and carries no visibility. It quotes
  learned lines verbatim, so an entry quoting a private episode lives with that episode in the
  private root.

## What a card may contain

A card states **what happened, what is believed, and why**, in plain language, with ids, dates,
provenance and scope. It never contains:

- prompt wording, or instructions addressed to a model ("you are…", "always answer…");
- text tuned to one model's quirks or token budget;
- a credential, token or password value. A card may name *where* a secret lives, never what it
  is. Today `check-cards.sh` greps episodes (asked, learned, decided, refused, open) and facts
  (statement, subject) and fails them by path; references, requests and pattern manifests are not
  yet grepped (planned with #91).

Prompt wording, ordering and truncation belong to a renderer. That is what keeps the memory
model-neutral.

## Dates, ids and references

- Dates are `YYYY-MM-DD`. YAML may parse them as dates; readers treat them as those strings.
- Ids are lower-kebab (a reference id may also carry dots, for a version: `acp-java-sdk-0.18.0`); an episode id is its date plus a slug, and equals its filename stem.
- A card refers to another by id (`patterns: [stdio-json-rpc-agent]`, `supersedes: <fact id>`,
  `provenance: [{episode: <id>, learned: <the line verbatim>}]`), never by copying its content.

## The transformation layer: the renderer contract

A renderer is a function:

```
render(cards, budget, profile) -> context
```

- **cards**: the selected cards. Selection is deterministic (trigger words, scope, recency),
  never an embedding or a model call (#85 invariant 4).
- **budget**: the most the context may hold (characters today).
- **profile**: the target.

| Profile | For | Renderer |
|---|---|---|
| `large-hosted` | a hosted model with a large context | `ContextAssembler` (Java, #73/#88): header, then facts, recent episodes, patterns and references; one cap over the block, whole entries dropped from the end so patterns go first |
| `small-local` | a local model with a small context | not built: facts only, shortest statements first |
| `mcp-client` | an MCP client reading the pod | not built: the cards themselves, as files, under the reader's grant |
| `human-journal` | the person, reading what happened | not built: `journal.sh` ([#90](https://github.com/enrichmeai/penstock/issues/90)) |

Every renderer must obey four rules. Where the one built renderer does not yet, it says so:
1. It reads cards and writes context; it never changes a card. *(`ContextAssembler`: true.)*
2. It never includes a card the reader may not see (`visibility` and the grant decide).
   *(`ContextAssembler`: **not yet**. It serves the owner's own session and does not read
   `visibility`, so a `private` card can reach the hosted model the owner chose. A profile rule
   for which visibilities each target may receive is planned with #91 and #93.)*
3. It drops whole entries to meet the budget, never truncating one mid-way. *(`ContextAssembler`: true.)*
4. It reports what it included, so an episode can later say what a session already knew.
   *(`ContextAssembler`: `fact.loaded`, `episode.loaded` and `pattern.loaded` audit events; the
   references a pattern brings in are not yet reported.)*

A new model needs a new profile, never new memory.

## Checking it

`bash scripts/check-cards.sh` validates every card against its schema when the repository has a
`memory.yaml`, and fails a YAML file in a memory folder that matches no card kind. It uses `scripts/lib/memory_format.py`, a small validator over exactly the
keywords the schemas use, so the check needs nothing beyond PyYAML. The schemas are standard
JSON Schema, so any other tool can validate with a full validator. `test-check-cards.sh` fails if
a schema ever uses a keyword the small validator does not implement. **The schemas
are necessary, not sufficient:** cross-file rules (an id equals its filename, provenance resolves
and quotes its episode, supersede pairs agree, one active fact per subject, the boundary rule
above, the credential grep) live in `check-cards.sh`. A new card kind needs a schema, an entry in
`scripts/lib/memory_format.py`, and a section here. 
