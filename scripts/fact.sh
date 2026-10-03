#!/bin/sh
# fact.sh — asks the record (issue #87): prints the active facts whose statement, subject or
# triggers contain every word given (case-insensitive), with scope, status, confidence,
# last_confirmed and the first provenance. --all includes superseded facts with their successor.
# Exit 0 with matches, 1 with none (so a session can test "do we already know this" before
# asking the owner), 64 for usage. Words are matched literally (no globbing); a word that starts
# with '-' cannot be searched.
set -euf
cd "$(dirname "$0")/.."
all=0; words=""
for a in "$@"; do
    case "$a" in
        --all) all=1 ;;
        -h|--help) sed -n '2,8p' "$0" >&2; exit 0 ;;
        -*) echo "fact.sh: unknown option '$a'" >&2; exit 64 ;;
        *) words="$words $a" ;;
    esac
done
[ -n "$(printf '%s' "$words" | tr -d ' ')" ] || { echo "usage: fact.sh <word> [<word>…] [--all]" >&2; exit 64; }
[ -d facts ] || { echo "fact.sh: no facts/ folder" >&2; exit 1; }
# shellcheck disable=SC2086
python3 - "$all" $words <<'PYEOF'
import glob, sys, yaml
show_all = sys.argv[1] == "1"
words = [w.lower() for w in sys.argv[2:]]
hits = []
for p in sorted(glob.glob("facts/*.yaml")):
    d = yaml.safe_load(open(p)) or {}
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
    print(f"{d.get('id')}  [{d.get('kind')} · {d.get('scope')} · {d.get('status')} {d.get('confidence')} · confirmed {d.get('last_confirmed')}]{tail}")
    print(f"  {d.get('statement')}")
    print(f"  from: {src}")
PYEOF
