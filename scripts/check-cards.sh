#!/bin/sh
# Validates the requests/, references/ and patterns/ memory folders (issue #72):
#   1. every YAML card parses
#   2. every id a request references under `references:`/`patterns:` resolves to a real card
#   3. every pattern's own check.sh passes
#   4. every pattern has a verified-against.tag (FAIL, exit 1 — issue #81)
#   5. every pattern behind the newest release tag is flagged (STALE, a warning, exit 0)
#   6. every episode under episodes/ has the shape episodes/README.md states (issue #86): id
#      equals the filename stem, date equals the filename's date, project is one of the
#      known projects, every built[].pr is owner/repo#n, ids are unique, and no learned or
#      decided line carries a credential-shaped value (reported by path, never by value)
#   7. every fact under facts/ has the shape facts/README.md states (issue #87): id equals the
#      filename stem, kind/scope/status from the closed lists, every provenance episode resolves,
#      supersedes/superseded_by are mutually consistent, no two active facts share a subject, and
#      the statement carries no credential-shaped value (reported by path, never by value)
#   8. memory format v1 (#98, docs/memory-format.md): when the repository has a memory.yaml, every
#      card validates against schema/<kind>.schema.json (format, visibility and every field), and a
#      card whose visibility is narrower than the repository's fails: it belongs in the owner's
#      private memory root (#91). A repository with neither
#      memory.yaml nor schema/ has not adopted the format and is skipped; one with memory.yaml and
#      a missing schema or validator fails.
set -eu
# --- which cards: --root <dir>, else MEMORY_ROOT, else this repository (#91) ---------------
# The tools (scripts/lib, schema/) always come from this repository; the cards come from the root.
# A root with a projects/ folder is sectioned (estate/ plus projects/<name>/, docs/memory-root.md):
# the cross-section rules are checked first (estate/ exists, no card folder at the root, project
# names declared in the root's memory.yaml projects: block or else the closed list (#112), a valid
# block (audiences, a public project never using a private one), fact scope matches its folder, ids and active subjects unique across
# sections), then every section in turn against the root's memory.yaml, and every PASS/FAIL/WARN/
# STALE line about a card names the card's path from the root. --section and --memory-yaml are
# internal: the sectioned run passes them to itself.
tooldir=$(cd "$(dirname "$0")/.." && pwd)
root=""; section=""; section_yaml=""
while [ $# -gt 0 ]; do
    case "$1" in
        --root|--section|--memory-yaml)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "check-cards.sh: $1 needs a value" >&2; exit 64; }
            case "$1" in --root) root="$2" ;; --section) section="$2" ;; *) section_yaml="$2" ;; esac
            shift 2 ;;
        -h|--help) awk 'NR == 1 || /^set -eu/ { next } /^#/ { print; next } { exit }' "$0" >&2; exit 0 ;;
        *) echo "check-cards.sh: unknown argument '$1'" >&2; exit 64 ;;
    esac
done
if [ -n "$section" ] && [ -z "$section_yaml" ]; then
    echo "check-cards.sh: --section is internal and needs --memory-yaml" >&2; exit 64
fi
[ -n "$root" ] || root="${MEMORY_ROOT:-$tooldir}"
[ -d "$root" ] || { echo "check-cards.sh: root '$root' is not a directory" >&2; exit 64; }
root=$(cd "$root" && pwd)
# Only values this script sets reach the checks below; nothing is read from the caller's environment.
export CARDS_LIB="$tooldir/scripts/lib"
export SCHEMA_DIR="$tooldir/schema"
unset MEMORY_YAML

if [ -d "$root/projects" ] && [ -z "$section" ]; then
    echo "=== check-cards: sectioned memory root $root ==="
    sectioned_status=0
    ROOT="$root" python3 - <<'PYEOF' || sectioned_status=1
import glob, os, sys, yaml
root = os.environ["ROOT"]
os.chdir(root)
sys.path.insert(0, os.environ["CARDS_LIB"])
import memory_projects
# the root's memory.yaml declares its projects (#112); without a projects: block, the closed list
PROJECTS = set(memory_projects.names("memory.yaml"))
KINDS = ("requests", "references", "patterns", "episodes", "facts")
status = 0
def fail(msg):
    global status
    print(f"FAIL: {msg}"); status = 1
if not os.path.isfile("memory.yaml"):
    fail("memory.yaml is missing at the root of a sectioned memory root")
else:
    for problem in memory_projects.problems("memory.yaml"):
        fail(problem)
if not os.path.isdir("estate"):
    fail("a sectioned memory root needs an estate/ folder")
for kind in KINDS:
    if os.path.exists(kind):
        fail(f"{kind}/ sits at the root of a sectioned memory root; cards live under estate/ or projects/<name>/")
sections = ["estate"] if os.path.isdir("estate") else []
for d in sorted(os.listdir("projects")):
    if not os.path.isdir(os.path.join("projects", d)):
        continue
    if d not in PROJECTS:
        fail(f"projects/{d} is not a known project ({', '.join(sorted(PROJECTS))})")
        continue
    sections.append(f"projects/{d}")
seen_ids, active_subjects = {}, {}
for sec in sections:
    want = "estate" if sec == "estate" else "project:" + sec.split("/", 1)[1]
    for kind in KINDS:
        for path in sorted(glob.glob(f"{sec}/{kind}/*.yaml") + glob.glob(f"{sec}/{kind}/*/manifest.yaml")):
            try:
                data = yaml.safe_load(open(path)) or {}
            except Exception:
                continue  # the section's own check reports a card that does not parse
            if not isinstance(data, dict):
                continue
            cid = str(data.get("id") or "")
            if cid:
                key = (kind, cid)
                if key in seen_ids:
                    fail(f"{kind[:-1]} id '{cid}' is used in both {seen_ids[key]} and {path}")
                else:
                    seen_ids[key] = path
            if kind == "facts":
                scope = str(data.get("scope") or "")
                if scope != want:
                    fail(f"{path} has scope {scope or '(none)'} but lives in {sec}/; it needs scope {want}")
                subj = str(data.get("subject") or "")
                if subj and data.get("status") != "superseded":
                    if subj in active_subjects:
                        fail(f"subject '{subj}' is active in both {active_subjects[subj]} and {path}")
                    else:
                        active_subjects[subj] = path
            if kind == "episodes" and sec != "estate":
                proj = str(data.get("project") or "")
                if proj != sec.split("/", 1)[1]:
                    fail(f"{path} has project {proj or '(none)'} but lives in {sec}/")
sys.exit(status)
PYEOF
    known_projects=$(python3 "$CARDS_LIB/memory_projects.py" names "$root/memory.yaml")
    for sec in "$root/estate" "$root"/projects/*; do
        [ -d "$sec" ] || continue
        rel=${sec#"$root"/}
        if [ "$rel" != estate ] && ! printf '%s\n' "$known_projects" | grep -qxF "${rel#projects/}"; then continue; fi
        echo "### section $rel"
        if out=$(sh "$0" --root "$sec" --section "$rel" --memory-yaml "$root/memory.yaml" 2>&1); then :; else sectioned_status=1; fi
        printf '%s\n' "$out" | awk -v rel="$rel" '
            /^(PASS|FAIL|WARN|STALE): (episodes|facts|patterns|requests|references)\// {
                i = index($0, ": "); print substr($0, 1, i + 1) rel "/" substr($0, i + 2); next }
            { print }'
    done
    exit $sectioned_status
fi
cd "$root"

echo "=== check-cards: YAML + id cross-check ==="
yaml_status=0
python3 - <<'PYEOF' || yaml_status=$?
import glob
import sys

import yaml

status = 0


def load(path):
    global status
    try:
        with open(path) as f:
            return yaml.safe_load(f)
    except Exception as exc:
        print(f"FAIL: {path} did not parse: {exc}")
        status = 1
        return None


reference_ids = set()
for path in sorted(glob.glob("references/*.yaml")):
    data = load(path)
    if data is None:
        continue
    print(f"PASS: {path} parses")
    reference_ids.add(data.get("id"))

pattern_ids = set()
for path in sorted(glob.glob("patterns/*/manifest.yaml")):
    data = load(path)
    if data is None:
        continue
    print(f"PASS: {path} parses")
    pattern_ids.add(data.get("id"))

for path in sorted(glob.glob("requests/*.yaml")):
    data = load(path)
    if data is None:
        continue
    print(f"PASS: {path} parses")
    for ref_id in data.get("references") or []:
        if ref_id not in reference_ids:
            print(f"FAIL: {path} references unknown id '{ref_id}' under references:")
            status = 1
    for pat_id in data.get("patterns") or []:
        if pat_id not in pattern_ids:
            print(f"FAIL: {path} references unknown id '{pat_id}' under patterns:")
            status = 1

sys.exit(status)
PYEOF

echo "=== check-cards: episodes ==="
episode_status=0
if [ -d episodes ]; then
    # The credential check reuses scripts/lib/credential-grep.sh on a scratch copy of the
    # episode's learned/decided/refused text, so the rule set is the one every other card uses
    # and the matched value is never printed, only the episode's path.
    MEMORY_PROJECTS_YAML="${section_yaml:-memory.yaml}" python3 - <<'PYEOF' || episode_status=$?
import glob
import os
import re
import subprocess
import sys
import tempfile

import yaml

# Kept in step with episodes/README.md § Projects; a root's memory.yaml may declare its own (#112).
# The path is set on this command alone (a section gets the root's memory.yaml), never inherited.
sys.path.insert(0, os.environ.get("CARDS_LIB", "scripts/lib"))
try:
    import memory_projects
except ImportError:
    print("FAIL: scripts/lib/memory_projects.py is missing; episode projects cannot be checked")
    sys.exit(1)
PROJECTS = set(memory_projects.names(os.environ["MEMORY_PROJECTS_YAML"])) | {"estate"}
PR_SHAPE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#[0-9]+$")
NAME_SHAPE = re.compile(r"^(\d{4}-\d{2}-\d{2})-([a-z0-9][a-z0-9-]*)\.yaml$")

status = 0
seen_ids = {}

# `sh <missing file>` also exits 2, which would read as "credential found": check first.
GREP = os.path.join(os.environ.get("CARDS_LIB", "scripts/lib"), "credential-grep.sh")
if not os.path.isfile(GREP):
    print(f"FAIL: {GREP} is missing; episodes cannot be checked for credential-shaped values")
    sys.exit(1)


def fail(path, message):
    global status
    print(f"FAIL: {path} {message}")
    status = 1


def text_lines(data):
    # asked is included because a turn renders it into the system prompt (#88)
    out = []
    if data.get("asked") is not None:
        out.append(str(data.get("asked")))
    for key in ("learned", "decided", "refused", "open"):
        for item in data.get(key) or []:
            if isinstance(item, dict):
                out.extend(str(v) for v in item.values())
            else:
                out.append(str(item))
    return out


for path in sorted(glob.glob("episodes/*.yaml")):
    name = os.path.basename(path)
    m = NAME_SHAPE.match(name)
    if not m:
        fail(path, "is not named <YYYY-MM-DD>-<slug>.yaml")
        continue
    file_date, _slug = m.group(1), m.group(2)
    stem = name[: -len(".yaml")]
    try:
        with open(path) as f:
            data = yaml.safe_load(f) or {}
    except Exception as exc:
        fail(path, f"did not parse: {exc}")
        continue
    if not isinstance(data, dict):
        fail(path, "is not a mapping")
        continue
    ok = True
    if data.get("id") != stem:
        fail(path, f"id {data.get('id')!r} does not match its filename")
        ok = False
    if str(data.get("date")) != file_date:
        fail(path, f"date {data.get('date')} does not match its filename")
        ok = False
    if data.get("project") not in PROJECTS:
        fail(path, f"project {data.get('project')!r} is not one of {sorted(PROJECTS)}")
        ok = False
    if not str(data.get("asked") or "").strip():
        fail(path, "has no asked: line")
        ok = False
    for entry in data.get("built") or []:
        pr = entry.get("pr") if isinstance(entry, dict) else None
        if not pr or not PR_SHAPE.match(str(pr)):
            fail(path, f"built entry has no owner/repo#n pr: {entry!r}")
            ok = False
    for key in ("decided", "refused"):
        for entry in data.get(key) or []:
            if not isinstance(entry, dict) or "what" not in entry or "why" not in entry:
                fail(path, f"{key} entry needs what: and why: {entry!r}")
                ok = False
    if stem in seen_ids:
        fail(path, f"id duplicates {seen_ids[stem]}")
        ok = False
    seen_ids[stem] = path

    with tempfile.TemporaryDirectory() as scratch:
        probe = os.path.join(scratch, "episode-text.yaml")
        with open(probe, "w") as f:
            f.write("\n".join(text_lines(data)) + "\n")
        rc = subprocess.run(["sh", GREP, scratch], capture_output=True, text=True).returncode
    if rc == 2:
        fail(path, "has a credential-shaped value in asked/learned/decided/refused/open (a reference, never a value)")
        ok = False
    elif rc not in (0, 2):
        fail(path, f"credential grep exited {rc}")
        ok = False

    if ok:
        print(f"PASS: {path}")

sys.exit(status)
PYEOF
else
    echo "no episodes/ folder"
fi

echo "=== check-cards: facts ==="
fact_status=0
if [ -d facts ]; then
    python3 - <<'PYEOF' || fact_status=$?
import glob
import os
import re
import subprocess
import sys
import tempfile

import yaml

# Kept in step with facts/README.md.
KINDS = {"account", "identifier", "location-of-secret", "convention", "decision", "principle"}
STATUSES = {"asserted", "inferred", "superseded"}
SCOPE = re.compile(r"^(estate|project:[a-z0-9-]+|repo:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)$")
ID_SHAPE = re.compile(r"^[a-z0-9][a-z0-9-]*$")
GREP = os.path.join(os.environ.get("CARDS_LIB", "scripts/lib"), "credential-grep.sh")

if not os.path.isfile(GREP):
    print(f"FAIL: {GREP} is missing; facts cannot be checked for credential-shaped values")
    sys.exit(1)

episode_ids = {os.path.basename(p)[:-5] for p in glob.glob("episodes/*.yaml")}


def fold(s):
    return " ".join(str(s).split())


def episode_learned(eid):
    try:
        with open(f"episodes/{eid}.yaml", encoding="utf-8") as f:
            ep = yaml.safe_load(f) or {}
    except Exception:
        return set()
    out = set()
    for line in ep.get("learned") or []:
        if isinstance(line, dict):
            line = "; ".join(str(v) for v in line.values() if v is not None)
        out.add(fold(line))
    return out
status = 0
facts = {}


def fail(path, message):
    global status
    print(f"FAIL: {path} {message}")
    status = 1


# pass 1: load and check each file on its own
for path in sorted(glob.glob("facts/*.yaml")):
    stem = os.path.basename(path)[:-5]
    try:
        with open(path) as f:
            data = yaml.safe_load(f) or {}
    except Exception as exc:
        fail(path, f"did not parse: {exc}")
        continue
    if not isinstance(data, dict):
        fail(path, "is not a mapping")
        continue
    ok = True
    if not ID_SHAPE.match(stem):
        fail(path, "is not named <kebab-id>.yaml"); ok = False
    if data.get("id") != stem:
        fail(path, f"id {data.get('id')!r} does not match its filename"); ok = False
    if not str(data.get("statement") or "").strip():
        fail(path, "has no statement:"); ok = False
    if data.get("kind") not in KINDS:
        fail(path, f"kind {data.get('kind')!r} is not one of {sorted(KINDS)}"); ok = False
    if not str(data.get("subject") or "").strip():
        fail(path, "has no subject:"); ok = False
    if not SCOPE.match(str(data.get("scope") or "")):
        fail(path, f"scope {data.get('scope')!r} is not estate, project:<name> or repo:<owner/name>"); ok = False
    if data.get("status") not in STATUSES:
        fail(path, f"status {data.get('status')!r} is not one of {sorted(STATUSES)}"); ok = False
    try:
        c = float(data.get("confidence"))
        if not 0.0 <= c <= 1.0:
            raise ValueError
    except (TypeError, ValueError):
        fail(path, f"confidence {data.get('confidence')!r} is not a number in [0, 1]"); ok = False
    prov = data.get("provenance") or []
    if not isinstance(prov, list) or not prov:
        fail(path, "has no provenance: list"); ok = False
    else:
        for entry in prov:
            if not isinstance(entry, dict) or not ({"owner", "episode"} & set(entry)):
                fail(path, f"provenance entry needs owner: <date> or episode: <id>: {entry!r}"); ok = False
            elif "episode" in entry and entry["episode"] not in episode_ids:
                fail(path, f"provenance episode {entry['episode']!r} is not a file under episodes/"); ok = False
            elif "episode" in entry and not entry.get("learned"):
                fail(path, f"provenance episode {entry['episode']} needs the learned: line it came from"); ok = False
            elif "episode" in entry and fold(entry["learned"]) not in episode_learned(entry["episode"]):
                fail(path, f"provenance learned text is not a learned line of episodes/{entry['episode']}.yaml"); ok = False
    for key in ("first_seen", "last_confirmed"):
        if not re.match(r"^\d{4}-\d{2}-\d{2}$", str(data.get(key))):
            fail(path, f"{key} {data.get(key)!r} is not YYYY-MM-DD"); ok = False
    if data.get("status") == "superseded" and not data.get("superseded_by"):
        fail(path, "is superseded but names no superseded_by"); ok = False
    if data.get("status") != "superseded" and data.get("superseded_by"):
        fail(path, f"names superseded_by {data.get('superseded_by')} but its status is not superseded"); ok = False

    with tempfile.TemporaryDirectory() as scratch:
        with open(os.path.join(scratch, "fact-text.yaml"), "w") as f:
            f.write(str(data.get("statement", "")) + "\n" + str(data.get("subject", "")) + "\n")
        rc = subprocess.run(["sh", GREP, scratch], capture_output=True, text=True).returncode
    if rc == 2:
        fail(path, "has a credential-shaped value in statement/subject (a reference, never a value)"); ok = False
    elif rc != 0:
        fail(path, f"credential grep exited {rc}"); ok = False

    facts[stem] = (path, data, ok)

# pass 2: cross-file rules
active_by_subject = {}
for stem, (path, data, ok) in sorted(facts.items()):
    sup = data.get("supersedes")
    if sup:
        if sup not in facts:
            fail(path, f"supersedes {sup}, which is not a fact"); ok = False
        else:
            old_path, old, _ = facts[sup]
            if old.get("superseded_by") != stem or old.get("status") != "superseded":
                fail(path, f"supersedes {sup}, but {sup} is not superseded_by {stem} with status superseded"); ok = False
    by = data.get("superseded_by")
    if by:
        if by not in facts:
            fail(path, f"is superseded_by {by}, which is not a fact"); ok = False
        elif facts[by][1].get("supersedes") != stem:
            fail(path, f"is superseded_by {by}, but {by} does not name it under supersedes"); ok = False
    if data.get("status") != "superseded":
        subj = str(data.get("subject") or "")
        if subj in active_by_subject:
            fail(path, f"subject '{subj}' is also active in {active_by_subject[subj]}"); ok = False
        else:
            active_by_subject[subj] = path
    facts[stem] = (path, data, ok)

for stem, (path, data, ok) in sorted(facts.items()):
    if ok:
        print(f"PASS: {path}")

# facts/rejected/lines.yaml: learned lines the owner reviewed and decided are not beliefs, so
# consolidate.sh never proposes them again. Each must cite an episode that exists and quote one
# of its learned lines verbatim, with a reason and the owner's date.
REJ = "facts/rejected/lines.yaml"
cited_by_facts = {}
for _stem, (_path, _data, _ok) in facts.items():
    for _e in (_data.get("provenance") or []) if isinstance(_data, dict) else []:
        if isinstance(_e, dict) and _e.get("episode") and _e.get("learned"):
            cited_by_facts[(_e["episode"], fold(_e["learned"]))] = _path
if os.path.isfile(REJ):
    try:
        with open(REJ, encoding="utf-8") as f:
            rej = yaml.safe_load(f) or {}
    except Exception as exc:
        fail(REJ, f"did not parse: {exc}")
        rej = None
    if rej is not None:
        entries = rej.get("rejected") if isinstance(rej, dict) else None
        if not isinstance(entries, list):
            fail(REJ, "needs a top-level rejected: list")
            entries = []
        rej_ok = True
        for i, e in enumerate(entries, 1):
            if not isinstance(e, dict) or not e.get("episode") or not e.get("learned") or not e.get("reason"):
                fail(REJ, f"entry {i} needs episode:, learned: and reason:"); rej_ok = False
            elif e["episode"] not in episode_ids:
                fail(REJ, f"entry {i} names episode {e['episode']!r}, which is not a file under episodes/"); rej_ok = False
            elif fold(e["learned"]) not in episode_learned(e["episode"]):
                fail(REJ, f"entry {i} learned text is not a learned line of episodes/{e['episode']}.yaml"); rej_ok = False
            elif not re.match(r"^\d{4}-\d{2}-\d{2}$", str(e.get("owner"))):
                fail(REJ, f"entry {i} owner {e.get('owner')!r} is not YYYY-MM-DD"); rej_ok = False
            elif (e["episode"], fold(e["learned"])) in cited_by_facts:
                fail(REJ, f"entry {i} is also cited by {cited_by_facts[(e['episode'], fold(e['learned']))]}: a line cannot be both a fact and rejected"); rej_ok = False
        if rej_ok:
            print(f"PASS: {REJ} ({len(entries)} rejected line(s))")

sys.exit(status)
PYEOF
else
    echo "no facts/ folder"
fi

echo "=== check-cards: pattern check.sh ==="
pattern_status=0
for manifest in patterns/*/manifest.yaml; do
    [ -e "$manifest" ] || continue
    dir=$(dirname "$manifest")
    check="$dir/check.sh"
    if [ ! -f "$check" ]; then
        echo "FAIL: $dir has no check.sh"
        pattern_status=1
        continue
    fi
    echo "--- $check ---"
    if bash "$check"; then
        echo "PASS: $check"
    else
        echo "FAIL: $check"
        pattern_status=1
    fi
done

echo "=== check-cards: staleness ==="
staleness_status=0
# Patterns are verified against this repository's releases, wherever the cards live.
latest_tag=$(git -C "$tooldir" describe --tags --abbrev=0 origin/main 2>/dev/null) || latest_tag=""
if [ -z "$latest_tag" ]; then
    echo "not run: no tags"
fi

for manifest in patterns/*/manifest.yaml; do
    [ -e "$manifest" ] || continue
    # Guarded with `if !` rather than a bare assignment: under `set -e`, a bare
    # `info=$(...)` would abort the whole script on a malformed manifest (already reported
    # by the YAML cross-check above) instead of reporting it here and checking the rest.
    if ! info=$(python3 - "$manifest" <<'PYEOF'
import sys

import yaml

data = yaml.safe_load(open(sys.argv[1])) or {}
verified = data.get("verified-against") or {}
tag = verified.get("tag")
print(f"{data.get('id', '')}\t{tag or ''}")
PYEOF
    ); then
        echo "FAIL: $manifest could not be read for staleness checking"
        staleness_status=1
        continue
    fi
    id=$(printf '%s' "$info" | cut -f1)
    tag=$(printf '%s' "$info" | cut -f2)

    if [ -z "$tag" ]; then
        echo "FAIL: $id has no verified-against.tag"
        staleness_status=1
        continue
    fi

    if [ -n "$latest_tag" ] && [ "$tag" != "$latest_tag" ]; then
        echo "STALE: $id verified against $tag, latest release is $latest_tag"
    fi
done

echo "=== check-cards: memory format v1 ==="
format_status=0
memory_yaml="${section_yaml:-memory.yaml}"
if [ -f "$memory_yaml" ]; then
    if [ ! -f "$CARDS_LIB/memory_format.py" ]; then
        echo "FAIL: memory.yaml is present but scripts/lib/memory_format.py is missing"
        format_status=1
    else
        MEMORY_YAML="$memory_yaml" python3 "$CARDS_LIB/memory_format.py" . || format_status=$?
    fi
elif [ -d schema ]; then
    echo "FAIL: schema/ is present but memory.yaml is not; the repository's boundary is undeclared"
    format_status=1
else
    echo "no memory.yaml or schema/; this repository has not adopted the memory format"
fi

status=0
[ "$yaml_status" -eq 0 ] || status=1
[ "$pattern_status" -eq 0 ] || status=1
[ "$staleness_status" -eq 0 ] || status=1
[ "$episode_status" -eq 0 ] || status=1
[ "$fact_status" -eq 0 ] || status=1
[ "$format_status" -eq 0 ] || status=1
exit $status
