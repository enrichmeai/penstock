#!/bin/sh
# consolidate.sh — proposes facts from episodes' learned lines (issue #87, slice 2 of #85).
#
# Usage: consolidate.sh [--since <YYYY-MM-DD>] [--write] [--replace] [--root <dir>]
#
# --root <dir> (else MEMORY_ROOT, else this repository) chooses the memory root (#91,
# docs/memory-root.md). A sectioned root is consolidated one section at a time: a section's
# episodes propose facts into that section's facts/, scoped project:<name> (or estate under
# estate/), with the root's memory.yaml setting visibility. --section and --memory-yaml are internal.
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
caller_dir=$(pwd)   # a relative --root or MEMORY_ROOT means relative to where it was run
tooldir=$(cd "$(dirname "$0")/.." && pwd)

since=""; write=0; replace=0; root=""; section=""; section_yaml=""
while [ $# -gt 0 ]; do
    case "$1" in
        --since) since="$2"; shift 2 ;;
        --write) write=1; shift ;;
        --replace) replace=1; shift ;;
        --root|--section|--memory-yaml)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "consolidate.sh: $1 needs a value" >&2; exit 64; }
            case "$1" in --root) root="$2" ;; --section) section="$2" ;; *) section_yaml="$2" ;; esac
            shift 2 ;;
        -h|--help) awk 'NR == 1 || /^set -eu/ { next } /^#/ { print; next } { exit }' "$0" >&2; exit 0 ;;
        *) echo "consolidate.sh: unknown argument '$1'" >&2; exit 64 ;;
    esac
done
case "$since" in ""|[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo "consolidate.sh: --since takes YYYY-MM-DD" >&2; exit 64 ;; esac
if [ -n "$section" ] && [ -z "$section_yaml" ]; then echo "consolidate.sh: --section is internal and needs --memory-yaml" >&2; exit 64; fi
[ -n "$root" ] || root="${MEMORY_ROOT:-$tooldir}"
case "$root" in /*) ;; *) root="$caller_dir/$root" ;; esac
[ -d "$root" ] || { echo "consolidate.sh: root '$root' is not a directory" >&2; exit 64; }
root=$(cd "$root" && pwd)

if [ -d "$root/projects" ] && [ -z "$section" ]; then
    # one section at a time; with --write, every section is first checked without writing, so a
    # credential-shaped line in one section stops the run before any section is written (exit 2)
    run_sections() { # writes when $write_pass is 1
        sectioned_status=0
        for sec in "$root/estate" "$root"/projects/*; do
            [ -d "$sec/episodes" ] || continue
            rel=${sec#"$root"/}
            case "$rel" in estate|projects/penstock|projects/cistern|projects/valuedocs|projects/site) ;;
                *) echo "consolidate.sh: $rel is not a known section; skipped (check-cards.sh fails it)" >&2; continue ;; esac
            echo "### section $rel"
            set -- --root "$sec" --section "$rel" --memory-yaml "$root/memory.yaml"
            [ -n "$since" ] && set -- "$@" --since "$since"
            [ "$write_pass" -eq 1 ] && set -- "$@" --write
            [ "$replace" -eq 1 ] && set -- "$@" --replace
            rc=0; sh "$0" "$@" || rc=$?
            [ "$rc" -eq 0 ] || [ "$sectioned_status" -ne 0 ] || sectioned_status=$rc
        done
    }
    if [ "$write" -eq 1 ]; then
        write_pass=0; run_sections >/dev/null
        if [ "$sectioned_status" -eq 2 ]; then
            echo "consolidate.sh: a credential-shaped learned line in a section (named above); nothing written in any section" >&2
            exit 2
        fi
    fi
    write_pass=$write; run_sections
    exit "$sectioned_status"
fi
cd "$root"

[ -d episodes ] || { echo "consolidate.sh: no episodes/ folder; nothing to consolidate"; exit 0; }
[ -f "$tooldir/scripts/lib/credential-grep.sh" ] || { echo "consolidate.sh: scripts/lib/credential-grep.sh is missing; refusing to run without it" >&2; exit 1; }

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
set -- "$(pwd)"
[ -n "$since" ] && set -- "$@" --since "$since"
[ -n "$section_yaml" ] && set -- "$@" --memory-yaml "$section_yaml"
[ "$section" = estate ] && set -- "$@" --estate
python3 "$tooldir/scripts/lib/consolidate_generate.py" "$@" >"$work/proposals.json"

# --- the raw learned lines go through the credential grep before anything derived from them is shown
# (as .yaml files: the grep's bare-value pass only reads config/doc extensions)
mkdir -p "$work/learned"
python3 - "$work/proposals.json" "$work/learned" <<'PYEOF'
import json, os, sys
for r in json.load(open(sys.argv[1])):
    with open(os.path.join(sys.argv[2], f"{r['episode']}__{r['learned_index']}.yaml"), "w") as f:
        f.write(r["learned"] + "\n")
PYEOF
if hits=$(sh "$tooldir/scripts/lib/credential-grep.sh" "$work/learned"); then :; else
    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "consolidate.sh: a learned line contains a credential-shaped value; nothing proposed or written. Fix the episode first:" >&2
        printf '%s\n' "$hits" | sed -E "s|^$work/learned/(.*)__([0-9]+)\.yaml:[0-9]+.*|${section:+$section/}episodes/\1.yaml: learned item \2|" >&2
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

if hits=$(sh "$tooldir/scripts/lib/credential-grep.sh" "$work/draft"); then :; else
    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "consolidate.sh: a draft contains a credential-shaped value; nothing written. Lines (path:line only):" >&2
        printf '%s\n' "$hits" | sed -E "s|^$work/draft/(.*):([0-9]+).*|draft ${section:+$section/}facts/\1:\2|" >&2
        exit 2
    fi
    echo "consolidate.sh: credential-grep.sh exited $rc" >&2; exit 1
fi

for f in "$work"/draft/*.yaml; do
    cp "$f" "facts/$(basename "$f")"
done
echo "consolidate.sh: wrote $(ls "$work/draft" | wc -l | tr -d ' ') file(s) under ${section:+$section/}facts/ — review them, then run scripts/check-cards.sh"
