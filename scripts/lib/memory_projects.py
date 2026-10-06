"""The projects a memory root declares (#112), read from its memory.yaml.

    memory_projects.py names <memory.yaml>   one project name per line: the root's `projects:`
                                             block, or the default list when there is none
    memory_projects.py check <memory.yaml>   FAIL lines for a malformed block; exit 1 if any

A project entry is `<name>: { audience: public|private, personal: all|shareable|none, uses: [...] }`.
A project may use only a declared project whose audience is at least as wide as its own: a public
project's output is seen by everyone, so it never uses a private one. Penstock enforces the same
rule when it loads cards (PatternCatalog); this check stops the mistake before then.
"""
import re
import sys

import yaml

# The projects a root has with no `projects:` block: the closed list of #91.
DEFAULT = ["penstock", "cistern", "valuedocs", "site"]
NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
AUDIENCES = ("public", "private")
PERSONAL = ("all", "shareable", "none")


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            cfg = yaml.safe_load(fh) or {}
    except (OSError, yaml.YAMLError):
        # a missing or malformed memory.yaml declares nothing; check-cards reports the parse error
        return None
    return cfg.get("projects") if isinstance(cfg, dict) else None


def names(path):
    block = load(path)
    return list(block) if isinstance(block, dict) and block else list(DEFAULT)


def problems(path):
    block = load(path)
    if block is None:
        return []
    if not isinstance(block, dict) or not block:
        return ["memory.yaml projects: must be a mapping of project name to { audience: ... }"]
    out = []
    for name, entry in block.items():
        name = str(name)
        if not NAME.match(name) or name == "estate":
            out.append(f"memory.yaml project '{name}' is not a plain folder name ([a-z0-9-], not estate)")
            continue
        if not isinstance(entry, dict):
            out.append(f"memory.yaml project '{name}' needs {{ audience: public|private }}")
            continue
        unknown = sorted(set(entry) - {"audience", "personal", "uses"})
        if unknown:
            out.append(f"memory.yaml project '{name}' has unknown keys {unknown}")
        if entry.get("audience") not in AUDIENCES:
            out.append(f"memory.yaml project '{name}' audience must be one of {list(AUDIENCES)}, got {entry.get('audience')!r}")
        if "personal" in entry and entry["personal"] not in PERSONAL:
            out.append(f"memory.yaml project '{name}' personal must be one of {list(PERSONAL)}, got {entry['personal']!r}")
        uses = entry.get("uses", [])
        if not isinstance(uses, list):
            out.append(f"memory.yaml project '{name}' uses must be a list of project names")
            continue
        for used in uses:
            used = str(used)
            target = block.get(used)
            if used == name:
                out.append(f"memory.yaml project '{name}' uses itself")
            elif not isinstance(target, dict):
                out.append(f"memory.yaml project '{name}' uses '{used}', which is not declared")
            elif entry.get("audience") == "public" and target.get("audience") == "private":
                out.append(f"memory.yaml project '{name}' is public but uses '{used}', which is private: "
                           f"its cards would reach everyone who sees '{name}'")
    return out


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("names", "check"):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(64)
    if sys.argv[1] == "names":
        print("\n".join(names(sys.argv[2])))
        sys.exit(0)
    found = problems(sys.argv[2])
    for p in found:
        print(f"FAIL: {p}")
    sys.exit(1 if found else 0)
