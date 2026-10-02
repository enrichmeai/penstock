#!/bin/sh
# Validates the requests/, references/ and patterns/ memory folders (issue #72):
#   1. every YAML card parses
#   2. every id a request references under `references:`/`patterns:` resolves to a real card
#   3. every pattern's own check.sh passes
#   4. every pattern has a verified-against.tag (FAIL, exit 1 — issue #81)
#   5. every pattern behind the newest release tag is flagged (STALE, a warning, exit 0)
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
    info=$(python3 - "$manifest" <<'PYEOF'
import sys

import yaml

data = yaml.safe_load(open(sys.argv[1])) or {}
verified = data.get("verified-against") or {}
tag = verified.get("tag")
print(f"{data.get('id', '')}\t{tag or ''}")
PYEOF
    )
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
exit $status
