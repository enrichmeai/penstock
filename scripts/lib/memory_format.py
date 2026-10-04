#!/usr/bin/env python3
"""Memory format v1 (#98, designed by Joseph Antony Aruja): validate every card against its
schema/<kind>.schema.json and report cards whose visibility is narrower than the repository's.

A small validator over exactly the JSON Schema keywords the schemas use, so the check needs no
dependency beyond PyYAML; the schemas themselves are standard draft 2020-12 and any validator
can read them. SUPPORTED lists the keywords; test-check-cards.sh fails if a schema uses one
outside it, so a schema can never silently ask for a rule this checker skips.

Usage: memory_format.py <repo-root>      prints PASS/FAIL/WARN lines; exit 1 on any FAIL
       memory_format.py --keywords <dir> prints any schema keyword not in SUPPORTED; exit 1 if any
"""
import datetime
import glob
import json
import os
import re
import sys

import yaml

SUPPORTED = {"type", "required", "properties", "additionalProperties", "enum", "const", "pattern",
             "minimum", "maximum", "items", "minItems"}
ANNOTATIONS = {"$schema", "$id", "title", "description", "$comment"}
KINDS = {  # kind -> glob of its cards, relative to the repo root
    "episode": "episodes/*.yaml",
    "fact": "facts/*.yaml",
    "request": "requests/*.yaml",
    "reference": "references/*.yaml",
    "pattern": "patterns/*/manifest.yaml",
}
RANK = {"private": 0, "shareable": 1, "public": 2}
PY_TYPES = {"string": str, "integer": int, "number": (int, float), "boolean": bool,
            "array": list, "object": dict, "null": type(None)}


def plain(v):
    """YAML gives dates as date objects; the format states them as YYYY-MM-DD strings. A datetime
    keeps its time, so it fails a date pattern exactly as a full validator over the JSON would."""
    if isinstance(v, datetime.datetime):
        return v.isoformat()
    if isinstance(v, datetime.date):
        return v.isoformat()
    if isinstance(v, dict):
        return {str(k): plain(x) for k, x in v.items()}
    if isinstance(v, list):
        return [plain(x) for x in v]
    return v


def is_type(v, t):
    if t in ("integer", "number") and isinstance(v, bool):
        return False
    return isinstance(v, PY_TYPES[t])


def same(a, b):
    """JSON equality: true is not 1, and 1 is not "1"."""
    return type(a) is type(b) and a == b or (
        isinstance(a, (int, float)) and isinstance(b, (int, float))
        and not isinstance(a, bool) and not isinstance(b, bool) and a == b)


def validate(v, s, path="$"):
    errs = []
    if "const" in s and not same(v, s["const"]):
        errs.append(f"{path} must be {s['const']!r}, got {v!r}")
    if "enum" in s and not any(same(v, e) for e in s["enum"]):
        errs.append(f"{path} must be one of {s['enum']}, got {v!r}")
    if "type" in s:
        types = s["type"] if isinstance(s["type"], list) else [s["type"]]
        if not any(is_type(v, t) for t in types):
            return errs + [f"{path} must be {' or '.join(types)}, got {type(v).__name__}"]
    if isinstance(v, str) and "pattern" in s and not re.search(s["pattern"], v):
        errs.append(f"{path} {v!r} does not match {s['pattern']}")
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if "minimum" in s and v < s["minimum"]:
            errs.append(f"{path} {v} is below {s['minimum']}")
        if "maximum" in s and v > s["maximum"]:
            errs.append(f"{path} {v} is above {s['maximum']}")
    if isinstance(v, list):
        if "minItems" in s and len(v) < s["minItems"]:
            errs.append(f"{path} needs at least {s['minItems']} item(s)")
        if "items" in s:
            for i, x in enumerate(v):
                errs += validate(x, s["items"], f"{path}[{i}]")
    if isinstance(v, dict):
        for k in s.get("required", []):
            if k not in v:
                errs.append(f"{path} is missing required '{k}'")
        props = s.get("properties", {})
        for k, x in v.items():
            if k in props:
                errs += validate(x, props[k], f"{path}.{k}")
            elif s.get("additionalProperties") is False:
                errs.append(f"{path} has '{k}', which the format does not define")
    return errs


def keywords(s, found):
    if isinstance(s, dict):
        for k, x in s.items():
            found.add(k)
            if k == "additionalProperties" and not isinstance(x, bool):
                found.add("additionalProperties (a schema value; only true/false is implemented)")
            if k == "properties":
                for sub in x.values():
                    keywords(sub, found)
            elif k in ("items",):
                keywords(x, found)
    return found


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--keywords":
        bad = set()
        for p in sorted(glob.glob(os.path.join(sys.argv[2], "*.schema.json"))):
            bad |= keywords(json.load(open(p)), set()) - SUPPORTED - ANNOTATIONS
        for k in sorted(bad):
            print(f"unsupported schema keyword: {k}")
        sys.exit(1 if bad else 0)

    root = sys.argv[1]
    status = 0
    cfg_path = os.path.join(root, "memory.yaml")
    try:
        cfg = yaml.safe_load(open(cfg_path, encoding="utf-8"))
    except Exception as exc:
        print(f"FAIL: memory.yaml did not parse: {str(exc).splitlines()[0]}")
        sys.exit(1)
    if not isinstance(cfg, dict):
        print("FAIL: memory.yaml must be a mapping with format, project and visibility")
        sys.exit(1)
    if not same(cfg.get("format"), 1):
        print(f"FAIL: memory.yaml is format {cfg.get('format')!r}; this checker knows format 1")
        sys.exit(1)
    repo_vis = cfg.get("visibility")
    if repo_vis not in RANK:
        print(f"FAIL: memory.yaml visibility must be one of {sorted(RANK)}, got {repo_vis!r}")
        sys.exit(1)
    # A YAML file in a memory folder that is no card kind would escape every check.
    for folder in ("episodes", "facts", "requests", "references", "patterns"):
        for p in sorted(glob.glob(os.path.join(root, folder, "*.yml")) +
                        (glob.glob(os.path.join(root, folder, "*.yaml")) if folder == "patterns" else [])):
            print(f"FAIL: {os.path.relpath(p, root)} is under a memory folder but matches no card kind "
                  f"(cards are .yaml; patterns live in patterns/<id>/manifest.yaml)")
            status = 1
    valid = {}  # kind -> {id: card}, for the cross-card boundary rule
    for kind, pattern in KINDS.items():
        schema_path = os.path.join(root, "schema", f"{kind}.schema.json")
        if not os.path.isfile(schema_path):
            print(f"FAIL: schema/{kind}.schema.json is missing")
            status = 1
            continue
        schema = json.load(open(schema_path))
        for path in sorted(glob.glob(os.path.join(root, pattern))):
            rel = os.path.relpath(path, root)
            try:
                card = plain(yaml.safe_load(open(path, encoding="utf-8")) or {})
            except Exception as exc:
                print(f"FAIL: {rel} did not parse: {exc}")
                status = 1
                continue
            if isinstance(card, dict) and "format" in card and not same(card["format"], 1):
                print(f"FAIL: {rel} is format {card['format']!r}; this checker knows format 1")
                status = 1
                continue
            errs = validate(card, schema)
            if errs:
                status = 1
                for e in errs:
                    print(f"FAIL: {rel} {e}")
                continue
            valid.setdefault(kind, {})[str(card["id"])] = (rel, card)
            print(f"PASS: {rel} ({kind}, format 1, {card['visibility']})")
            if RANK[card["visibility"]] < RANK[repo_vis]:
                print(f"WARN: {rel} is {card['visibility']} but this repository is {repo_vis}; "
                      f"it moves to the private memory root with #91")
    # A fact may be no wider than any episode it was learned from: a private episode's line must
    # not come back as a public belief (the leak #94/#95 showed).
    episodes = valid.get("episode", {})
    for fid, (rel, fact) in sorted(valid.get("fact", {}).items()):
        for e in fact.get("provenance") or []:
            src = episodes.get(str(e.get("episode"))) if isinstance(e, dict) else None
            if src and RANK[fact["visibility"]] > RANK[src[1]["visibility"]]:
                print(f"FAIL: {rel} is {fact['visibility']} but cites episode {e['episode']}, "
                      f"which is {src[1]['visibility']}; a fact is never wider than its source")
                status = 1
    sys.exit(status)


if __name__ == "__main__":
    main()
