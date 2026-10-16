#!/bin/sh
# journal.sh — where things stand, from the record alone (issue #90): what was built when, what
# was decided and refused, which beliefs changed, and what is still open. Plain text to paste
# into an issue; --json emits the same sections as one object.
#   scripts/journal.sh [<project>] [--since YYYY-MM-DD] [--json] [--root <dir>]
# <project> keeps to one project (on a sectioned root, its projects/<name>/ section); default all.
# --since defaults to 30 days ago. --root (else MEMORY_ROOT, else this repository) chooses the
# memory root (#91): a flat root's episodes/ and facts/, or a sectioned root's estate/ and every
# projects/<name>/. A section whose folder is absent is one line saying so.
# <project> also keeps Beliefs changed to that project's facts and the estate's (on a flat root, a
# fact whose scope is project:<name>, estate, or unset).
# Open loops come from the whole record, not only the window: an open line stays open until a LATER
# episode lists the same text (case and spacing ignored) under decided or built (an entry's `what`
# when it is a mapping), or lists `closes: [<episode-id>#<n>]`, n counting that episode's open
# lines from 1. A later episode that repeats the text under open carries the loop forward; it is
# still listed once, under the episode that first opened it; two episodes opening the same text
# share that one loop. "Later" is by date, then by id within a day. Exit 0, or 64 for usage.
set -euf
root=""; since=""; project=""; json=0; want=""
for a in "$@"; do
    if [ -n "$want" ]; then
        case "$want" in root) root="$a" ;; since) since="$a" ;; esac
        want=""; continue
    fi
    case "$a" in
        --root) want=root ;;
        --since) want=since ;;
        --json) json=1 ;;
        -h|--help) awk 'NR == 1 || /^set -eu/ { next } /^#/ { print; next } { exit }' "$0" >&2; exit 0 ;;
        -*) echo "journal.sh: unknown option '$a'" >&2; exit 64 ;;
        *) [ -z "$project" ] || { echo "journal.sh: one project at most, got '$project' and '$a'" >&2; exit 64; }
           project="$a" ;;
    esac
done
[ -z "$want" ] || { echo "journal.sh: --$want needs a value" >&2; exit 64; }
case "$project" in ''|[a-z0-9]*) ;; *) echo "journal.sh: '$project' is not a project name" >&2; exit 64 ;; esac
[ -n "$root" ] || root="${MEMORY_ROOT:-$(dirname "$0")/..}"
[ -d "$root" ] || { echo "journal.sh: root '$root' is not a directory" >&2; exit 64; }
cd "$root"
exec python3 - "$since" "$project" "$json" <<'PYEOF'
import datetime, glob, json, os, re, sys
import yaml

since_arg, project, as_json = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
if project and not re.fullmatch(r"[a-z0-9][a-z0-9-]*", project):
    print(f"journal.sh: '{project}' is not a project name", file=sys.stderr); sys.exit(64)
if since_arg:
    if not DATE.match(since_arg):
        print(f"journal.sh: --since wants YYYY-MM-DD, got '{since_arg}'", file=sys.stderr); sys.exit(64)
    try:
        since = datetime.date.fromisoformat(since_arg)
    except ValueError:
        print(f"journal.sh: --since '{since_arg}' is not a date", file=sys.stderr); sys.exit(64)
else:
    since = datetime.date.today() - datetime.timedelta(days=30)

sectioned = os.path.isdir("projects")
def folders(kind, with_estate=False):
    """(section, folder) pairs for a card kind, honouring the project filter."""
    if not sectioned:
        return [("", kind)]
    if project:
        mine = [(f"projects/{project}", f"projects/{project}/{kind}")]
        return ([("estate", f"estate/{kind}")] if with_estate else []) + mine
    return [("estate", f"estate/{kind}")] + [(os.path.dirname(d), d) for d in sorted(glob.glob(f"projects/*/{kind}"))]

def as_date(v):
    if isinstance(v, datetime.date):
        return v
    try:
        return datetime.date.fromisoformat(str(v))
    except (TypeError, ValueError):
        return None

def load(kind, with_estate=False):
    found, cards = False, []
    for section, d in folders(kind, with_estate):
        if not os.path.isdir(d):
            continue
        found = True
        for p in sorted(glob.glob(f"{d}/*.yaml")):
            try:
                data = yaml.safe_load(open(p)) or {}
            except Exception as e:
                print(f"journal.sh: skipping {p}: {e}", file=sys.stderr); continue
            if isinstance(data, dict):
                data["_path"], data["_section"] = p, section
                cards.append(data)
    return found, cards

def norm(text):
    return " ".join(str(text).split()).lower()

def as_list(v):
    """A field the schema wants as a list: None is empty, a lone scalar or mapping is one entry."""
    if v is None:
        return []
    return v if isinstance(v, list) else [v]

def texts(v):
    """The text of each entry in decided or built: a mapping's `what`, else the entry itself."""
    out = []
    for x in as_list(v):
        if isinstance(x, dict):
            if x.get("what") is not None:
                out.append(str(x["what"]))
        elif x is not None:
            out.append(str(x))
    return out

have_episodes, episodes = load("episodes")
for e in [e for e in episodes if not as_date(e.get("date"))]:
    print(f"journal.sh: skipping {e['_path']}: no valid date", file=sys.stderr)
episodes = [e for e in episodes if as_date(e.get("date"))]
if project and not sectioned:
    episodes = [e for e in episodes if str(e.get("project", "")) == project]
episodes.sort(key=lambda e: (as_date(e["date"]), str(e.get("id", ""))))
window = [e for e in episodes if as_date(e["date"]) >= since]

def built_line(e):
    out = []
    for b in as_list(e.get("built")):
        if isinstance(b, dict):
            out.append(" ".join(str(b[k]) for k in ("pr", "merged") if b.get(k)))
        else:
            out.append(str(b))
    return "; ".join(x for x in out if x)

timeline = [{"id": str(e.get("id")), "date": str(as_date(e["date"])), "project": str(e.get("project", "")),
             "section": e["_section"], "asked": str(e.get("asked", "")).strip(), "built": built_line(e)}
            for e in reversed(window)]

def what_why(items):
    out = []
    for i in as_list(items):
        if isinstance(i, dict):
            out.append({"what": str(i.get("what", "")).strip(), "why": str(i.get("why", "")).strip()})
        else:
            out.append({"what": str(i).strip(), "why": ""})
    return out
decided = [{"id": str(e.get("id")), "decided": what_why(e.get("decided")), "refused": what_why(e.get("refused"))}
           for e in reversed(window) if as_list(e.get("decided")) or as_list(e.get("refused"))]

# Open loops, oldest episode first across the whole record: each episode first closes what it
# names (so a marker can only close a loop an EARLIER episode opened), then opens its own lines.
loops, by_ref = {}, {}
for e in episodes:
    for t in texts(e.get("decided")) + texts(e.get("built")):
        loops.pop(norm(t), None)
    for marker in as_list(e.get("closes")):
        key = by_ref.get(str(marker).strip())
        if key is not None:
            loops.pop(key, None)
    for n, line in enumerate(as_list(e.get("open")), start=1):
        if line is None:
            continue
        key = norm(line)
        by_ref[f"{e.get('id')}#{n}"] = key
        loops.setdefault(key, {"text": str(line).strip(), "opened_by": str(e.get("id")), "date": str(as_date(e["date"]))})
open_loops = list(loops.values())  # insertion order is opening order, oldest first

have_facts, facts = load("facts", with_estate=True)
if project and not sectioned:
    facts = [f for f in facts if str(f.get("scope") or "estate") in ("estate", f"project:{project}")]
by_id = {str(f.get("id")): f for f in facts}
beliefs = []
for f in facts:
    first, confirmed = as_date(f.get("first_seen")), as_date(f.get("last_confirmed"))
    if f.get("status") == "superseded":
        continue  # shown only as the old half of a pair, never as current
    old = by_id.get(str(f.get("supersedes"))) if f.get("supersedes") else None
    if old is not None and first and first >= since:
        beliefs.append({"change": "superseded", "old": str(old.get("id")), "old_statement": str(old.get("statement", "")).strip(),
                        "new": str(f.get("id")), "statement": str(f.get("statement", "")).strip(), "date": str(first)})
    elif first and first >= since:
        beliefs.append({"change": "new", "new": str(f.get("id")), "statement": str(f.get("statement", "")).strip(), "date": str(first)})
    elif confirmed and confirmed >= since:
        beliefs.append({"change": "confirmed", "new": str(f.get("id")), "statement": str(f.get("statement", "")).strip(), "date": str(confirmed)})
beliefs.sort(key=lambda b: b["date"], reverse=True)

if as_json:
    print(json.dumps({"since": str(since), "project": project or None, "timeline": timeline, "decided": decided,
                      "beliefs": beliefs if have_facts else None, "open": open_loops}, indent=2))
    sys.exit(0)

scope = project or "all projects"
print(f"# Journal: {scope}, since {since}\n")
print("## Timeline")
if not have_episodes:
    print("(no episodes/ folder in this root)")
elif not timeline:
    print("(no episode in the window)")
for t in timeline:
    built = f"  [{t['built']}]" if t["built"] else ""
    print(f"- {t['date']}  {t['project']}  {t['asked']}{built}")
print("\n## Decided / refused")
if not decided:
    print("(nothing decided or refused in the window)")
for d in decided:
    print(f"- {d['id']}")
    for x in d["decided"]:
        print(f"    decided: {x['what']}" + (f" — {x['why']}" if x["why"] else ""))
    for x in d["refused"]:
        print(f"    refused: {x['what']}" + (f" — {x['why']}" if x["why"] else ""))
print("\n## Beliefs changed")
if not have_facts:
    print("(no facts/ folder in this root)")
elif not beliefs:
    print("(no belief changed in the window)")
for b in beliefs:
    if b["change"] == "superseded":
        print(f"- {b['date']}  {b['old']} → {b['new']}: {b['old_statement']} → {b['statement']}")
    else:
        print(f"- {b['date']}  {b['change']}  {b['new']}: {b['statement']}")
print("\n## Open loops")
if not open_loops:
    print("(no open loop)")
for o in open_loops:
    print(f"- {o['text']}  (opened by {o['opened_by']})")
PYEOF
