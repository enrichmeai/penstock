#!/bin/sh
# fact.sh — asks the record (issue #87): prints the active facts whose statement, subject or
# triggers contain every word given (case-insensitive), with scope, status, confidence,
# last_confirmed and the first provenance. --all includes superseded facts with their successor.
# Exit 0 with matches, 1 with none (so a session can test "do we already know this" before
# asking the owner), 64 for usage. Words are matched literally (no globbing); a word that starts
# with '-' cannot be searched.
# --root <dir> (else MEMORY_ROOT, else this repository) chooses the memory root (#91); a sectioned
# root (estate/ plus projects/<name>/) is searched in every section, and each match names its section.
set -euf
root=""; all=0; words=""; want_root=0
for a in "$@"; do
    if [ "$want_root" -eq 1 ]; then root="$a"; want_root=0; continue; fi
    case "$a" in
        --root) want_root=1 ;;
        --all) all=1 ;;
        -h|--help) awk 'NR == 1 || /^set -eu/ { next } /^#/ { print; next } { exit }' "$0" >&2; exit 0 ;;
        -*) echo "fact.sh: unknown option '$a'" >&2; exit 64 ;;
        *) words="$words $a" ;;
    esac
done
[ "$want_root" -eq 0 ] || { echo "fact.sh: --root needs a directory" >&2; exit 64; }
[ -n "$(printf '%s' "$words" | tr -d ' ')" ] || { echo "usage: fact.sh <word> [<word>…] [--all] [--root <dir>]" >&2; exit 64; }
[ -n "$root" ] || root="${MEMORY_ROOT:-$(dirname "$0")/..}"
[ -d "$root" ] || { echo "fact.sh: root '$root' is not a directory" >&2; exit 64; }
cd "$root"
if [ -d projects ]; then folders="estate/facts projects/*/facts"; else folders="facts"; fi
set +f; found=0; for d in $folders; do [ -d "$d" ] && found=1; done; set -f
[ "$found" -eq 1 ] || { echo "fact.sh: no facts/ folder under $root" >&2; exit 1; }
# shellcheck disable=SC2086
python3 - "$all" $words <<'PYEOF'
import glob, os, sys, yaml
show_all = sys.argv[1] == "1"
words = [w.lower() for w in sys.argv[2:]]
hits = []
paths = sorted(glob.glob("estate/facts/*.yaml") + glob.glob("projects/*/facts/*.yaml")) if os.path.isdir("projects") else sorted(glob.glob("facts/*.yaml"))
for p in paths:
    d = yaml.safe_load(open(p)) or {}
    d["_section"] = os.path.dirname(os.path.dirname(p))
    if d.get("status") == "superseded" and not show_all:
        continue
    hay = " ".join([str(d.get("statement", "")), str(d.get("subject", "")), " ".join(d.get("triggers") or [])]).lower()
    if all(w in hay for w in words):
        hits.append(d)
if not hits:
    print("no fact matches: " + " ".join(words), file=sys.stderr)
    sys.exit(1)
for d in hits:
    prov = (d.get("provenance") or [{}])[0]
    src = f"episode {prov['episode']}" if "episode" in prov else f"owner {prov.get('owner', '?')}"
    tail = f" → superseded by {d.get('superseded_by')}" if d.get("status") == "superseded" else ""
    where = f"{d['_section']}  " if d.get("_section") else ""
    print(f"{where}{d.get('id')}  [{d.get('kind')} · {d.get('scope')} · {d.get('status')} {d.get('confidence')} · confirmed {d.get('last_confirmed')}]{tail}")
    print(f"  {d.get('statement')}")
    print(f"  from: {src}")
PYEOF
