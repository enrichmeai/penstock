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
set -eu
cd "$(dirname "$0")/.."

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
    python3 - <<'PYEOF' || episode_status=$?
import glob
import os
import re
import subprocess
import sys
import tempfile

import yaml

# Kept in step with episodes/README.md § Projects.
PROJECTS = {"penstock", "cistern", "valuedocs", "site", "estate"}
PR_SHAPE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#[0-9]+$")
NAME_SHAPE = re.compile(r"^(\d{4}-\d{2}-\d{2})-([a-z0-9][a-z0-9-]*)\.yaml$")

status = 0
seen_ids = {}

# `sh <missing file>` also exits 2, which would read as "credential found": check first.
GREP = "scripts/lib/credential-grep.sh"
if not os.path.isfile(GREP):
    print(f"FAIL: {GREP} is missing; episodes cannot be checked for credential-shaped values")
    sys.exit(1)


def fail(path, message):
    global status
    print(f"FAIL: {path} {message}")
    status = 1


def text_lines(data):
    out = []
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
        fail(path, "has a credential-shaped value in learned/decided/refused/open (a reference, never a value)")
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
latest_tag=$(git describe --tags --abbrev=0 origin/main 2>/dev/null) || latest_tag=""
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

status=0
[ "$yaml_status" -eq 0 ] || status=1
[ "$pattern_status" -eq 0 ] || status=1
[ "$staleness_status" -eq 0 ] || status=1
[ "$episode_status" -eq 0 ] || status=1
exit $status
