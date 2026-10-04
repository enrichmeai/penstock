#!/bin/sh
# consolidate.sh — proposes facts from episodes' learned lines (issue #87, slice 2 of #85).
#
# Usage: consolidate.sh [--since <YYYY-MM-DD>] [--write] [--replace]
#
# Dry-run by default: prints one row per proposal, `action | subject | statement | from episode`,
# where action is new (no active fact shares the subject), confirm (one does and says the same;
# only last_confirmed and provenance move) or supersede (one does and says something else: a new
# fact with supersedes: <old>, and the old file gets status: superseded / superseded_by: <new>).
# The script never chooses which of two beliefs is true; the owner decides in the PR.
#
# Before anything is printed, scripts/lib/credential-grep.sh runs over every uncited learned
# line: a hit stops the run with exit 2 naming episodes/<id>.yaml and the learned item number,
# never the value (the dry-run table and a draft's filename both derive from that text).
# --write applies the proposals under facts/ only, after the grep has run again over the drafts;
# an existing id is never overwritten without --replace (exit 3). The kind/subject rules are a small text
# table in scripts/lib/consolidate_generate.py: no model call, no embedding (#85 invariant 4).
# Exit codes: 0 ok (also when there is nothing to propose), 1 failure, 2 credential shape,
# 3 destination exists without --replace, 64 usage.
set -eu
cd "$(dirname "$0")/.."

since=""; write=0; replace=0
while [ $# -gt 0 ]; do
    case "$1" in
        --since) since="$2"; shift 2 ;;
        --write) write=1; shift ;;
        --replace) replace=1; shift ;;
        -h|--help) sed -n '2,21p' "$0" >&2; exit 0 ;;
        *) echo "consolidate.sh: unknown argument '$1'" >&2; exit 64 ;;
    esac
done
case "$since" in ""|[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo "consolidate.sh: --since takes YYYY-MM-DD" >&2; exit 64 ;; esac
[ -d episodes ] || { echo "consolidate.sh: no episodes/ folder; nothing to consolidate"; exit 0; }
[ -f scripts/lib/credential-grep.sh ] || { echo "consolidate.sh: scripts/lib/credential-grep.sh is missing; refusing to run without it" >&2; exit 1; }

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
set -- "$(pwd)"
[ -n "$since" ] && set -- "$@" --since "$since"
python3 scripts/lib/consolidate_generate.py "$@" >"$work/proposals.json"

# --- the raw learned lines go through the credential grep before anything derived from them is shown
# (as .yaml files: the grep's bare-value pass only reads config/doc extensions)
mkdir -p "$work/learned"
python3 - "$work/proposals.json" "$work/learned" <<'PYEOF'
import json, os, sys
for r in json.load(open(sys.argv[1])):
    with open(os.path.join(sys.argv[2], f"{r['episode']}__{r['learned_index']}.yaml"), "w") as f:
        f.write(r["learned"] + "\n")
PYEOF
if hits=$(sh scripts/lib/credential-grep.sh "$work/learned"); then :; else
    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "consolidate.sh: a learned line contains a credential-shaped value; nothing proposed or written. Fix the episode first:" >&2
        printf '%s\n' "$hits" | sed -E "s|^$work/learned/(.*)__([0-9]+)\.yaml:[0-9]+.*|episodes/\1.yaml: learned item \2|" >&2
        exit 2
    fi
    echo "consolidate.sh: credential-grep.sh exited $rc" >&2; exit 1
fi

count=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$work/proposals.json")
if [ "$count" -eq 0 ]; then
    echo "consolidate.sh: every learned line is cited by a fact or listed in facts/rejected/lines.yaml; nothing to propose"
    exit 0
fi

python3 - "$work/proposals.json" <<'PYEOF'
import json, sys
rows = json.load(open(sys.argv[1]))
cols = ["action", "subject", "statement", "from episode"]
data = []
for r in rows:
    f = r["fact"]
    subj = f.get("subject") or (r.get("old") or "")
    stmt = f.get("statement") or f"(confirm {r['old']})"
    extra = f" (supersedes {r['old']})" if r["action"] == "supersede" else ""
    if r["action"] == "confirm" and f.get("visibility"):
        extra += f" (narrows to {f['visibility']}: the episode is {f['visibility']})"
    data.append([r["action"], subj, (stmt[:70] + "…" if len(stmt) > 71 else stmt) + extra, r["episode"]])
w = [max(len(c), *(len(x[i]) for x in data)) for i, c in enumerate(cols)]
print(" | ".join(c.ljust(w[i]) for i, c in enumerate(cols)))
print("-+-".join("-" * x for x in w))
for row in data:
    print(" | ".join(v.ljust(w[i]) for i, v in enumerate(row)))
print(f"\n{len(data)} proposal(s): " + ", ".join(f"{a} {sum(1 for r in rows if r['action']==a)}" for a in ("new","confirm","supersede") if any(r['action']==a for r in rows)))
PYEOF

[ "$write" -eq 1 ] || { echo "(dry run; pass --write to apply under facts/)"; exit 0; }

# --- write: drafts to a scratch dir, credential grep again, then into facts/ ---------------------
mkdir -p "$work/draft" facts
python3 - "$work/proposals.json" "$work/draft" "$replace" <<'PYEOF'
import json, os, sys, yaml, datetime
rows, out, replace = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3] == "1"
def date(s):
    return datetime.date.fromisoformat(str(s))
def load_fact(fid):
    # a fact proposed earlier in this same run lives in the draft dir, not yet under facts/
    for base in (out, "facts"):
        p = os.path.join(base, fid + ".yaml")
        if os.path.exists(p):
            return yaml.safe_load(open(p))
    sys.exit(f"consolidate.sh: fact {fid} named by a proposal was not found (nothing written)")
def dump(path, d):
    for k in ("first_seen", "last_confirmed"):
        if k in d: d[k] = date(d[k])
    for e in d.get("provenance") or []:
        if "owner" in e: e["owner"] = date(e["owner"])
    with open(path, "w") as f:
        yaml.safe_dump(d, f, sort_keys=False, allow_unicode=True, width=100, default_flow_style=False)
plan = []
for r in rows:
    f = r["fact"]
    if r["action"] in ("new", "supersede"):
        dest = f"facts/{f['id']}.yaml"
        if os.path.exists(dest) and not replace:
            print(f"consolidate.sh: {dest} already exists; pass --replace to overwrite it deliberately (nothing written)", file=sys.stderr)
            sys.exit(3)
        dump(os.path.join(out, f["id"] + ".yaml"), dict(f))
        plan.append(("put", f["id"] + ".yaml"))
        if r["action"] == "supersede":
            old = load_fact(r["old"])
            old["status"] = "superseded"; old["superseded_by"] = f["id"]
            dump(os.path.join(out, r["old"] + ".yaml"), old)
            plan.append(("put", r["old"] + ".yaml"))
    else:  # confirm
        old = load_fact(r["old"])
        old["last_confirmed"] = f["last_confirmed"]
        old.setdefault("provenance", []).append(f["provenance_add"])
        if f.get("visibility"):
            old["visibility"] = f["visibility"]
        dump(os.path.join(out, r["old"] + ".yaml"), old)
        plan.append(("put", r["old"] + ".yaml"))
json.dump(plan, open(os.path.join(out, "..", "plan.json"), "w"))
PYEOF
rc=$?; [ "$rc" -eq 0 ] || exit "$rc"

if hits=$(sh scripts/lib/credential-grep.sh "$work/draft"); then :; else
    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "consolidate.sh: a draft contains a credential-shaped value; nothing written. Lines (path:line only):" >&2
        printf '%s\n' "$hits" | sed -E "s|^$work/draft/(.*):([0-9]+).*|draft \1:\2|" >&2
        exit 2
    fi
    echo "consolidate.sh: credential-grep.sh exited $rc" >&2; exit 1
fi

for f in "$work"/draft/*.yaml; do
    cp "$f" "facts/$(basename "$f")"
done
echo "consolidate.sh: wrote $(ls "$work/draft" | wc -l | tr -d ' ') file(s) under facts/ — review them, then run scripts/check-cards.sh"
