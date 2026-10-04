# requests/

One YAML file per thing that was asked for. This is the "what was wanted" memory —
part 1 of [#71](https://github.com/enrichmeai/penstock/issues/71); retrieval (part 2)
and promotion (part 3) are later work.

## Shape

```yaml
id: <kebab-case, unique>
want: <one sentence — the behaviour that was asked for>
why: <one sentence — the motivation>
done-when: [<checkable condition>, ...]
not: [<checkable condition that must stay false>, ...]
references: [<ids from references/>]
patterns: [<ids from patterns/>]
outcome: <filled in once shipped: where, and the date>
issue: <issue number this came from>
```

`references` and `patterns` are ids, not paths — resolve them against
`references/<id>.yaml` and `patterns/<id>/manifest.yaml`.

## Rules

- A card is added or changed only by PR, same as any other file in this repo.
- Every id a card lists under `references:` or `patterns:` must resolve to a real
  file — `scripts/check-cards.sh` checks this and fails the build if it doesn't.
- `outcome:` stays empty until the work actually shipped; don't pre-fill it.

Every card here also carries `format: 1` and `visibility` after its `id`, and validates against its schema under `schema/` ([memory format v1](../docs/memory-format.md)).
