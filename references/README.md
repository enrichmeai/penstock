# references/

One YAML file per external source a request or pattern leaned on. This is the
"what was trusted" memory — part 1 of
[#71](https://github.com/enrichmeai/penstock/issues/71).

## Shape

```yaml
id: <kebab-case, unique>
source: <URL, or a path + ref (branch/tag/commit) for an in-repo or sibling-repo source>
read: <date the card was written or last confirmed, YYYY-MM-DD>
version: <tag or commit the card was read at, if the source has one>
trust: primary | secondary
holds: [<one-line fact that mattered for the request/pattern citing this card>, ...]
unreachable: <optional — set when the source could not be read, and from where; omit once it has been read>
```

`holds:` is deliberately short and narrow: only the facts actually used
elsewhere in this repo, not a summary of the whole source. A card that turns
out to assert something the source doesn't say is a bug — fix the card, don't
work around it.

## Rules

- A card is added or changed only by PR.
- `read:` is updated whenever the card is re-confirmed against the source, even
  if `holds:` doesn't change.
- `unreachable:` is temporary. The first run that can reach the source removes
  it and fills in `read:`.

Every card here also carries `format: 1` and `visibility` after its `id`, and validates against its schema under `schema/` ([memory format v1](../docs/memory-format.md)).
