#!/bin/sh
# Prints a per-pattern use digest from GET /api/audit/patterns (issue #81): how many turns
# loaded each pattern, when it was last loaded, and which patterns have never been loaded —
# listed last, so pruning patterns/ is a reading of this table, not a memory exercise.
#
# Usage: scripts/pattern-digest.sh [--pod] [base-url]
#   base-url defaults to http://localhost:8080
#   --pod appends, per pattern, the last reviewer verdict and the read-receipt count from the
#         owner's Cistern pod (issue #82) — "last verdict" from the newest file under
#         /memory/verdicts/*/<pattern-id>-*.yaml, "reads" from the ALLOWED READ receipts on
#         /memory/patterns/<pattern-id>/… (GET /memory/patterns/?receipts). Needs
#         CISTERN_BASE_URL and CISTERN_TOKEN (the owner token) in the environment; without
#         them this prints "pod: not configured" and falls back to the local columns only.
#         Without --pod at all, this script is byte-identical to #81's.
#
# Reads the basic-auth credentials from the same env vars README.md documents for a running
# instance (AGENT_AUTH_USERNAME, default "admin"; AGENT_AUTH_PASSWORD, required) — never from
# an argument, so the password never ends up in shell history. curl gets them through a config
# read from its stdin (`-K -`), not through `-u`, so they are not in its argv either.
set -eu

pod_requested=0
base_url=""
for arg in "$@"; do
    case "$arg" in
        --pod) pod_requested=1 ;;
        *) base_url="$arg" ;;
    esac
done
base_url="${base_url:-http://localhost:8080}"

if [ -z "${AGENT_AUTH_PASSWORD:-}" ]; then
    echo "pattern-digest.sh: AGENT_AUTH_PASSWORD must be set (the same env var the running instance reads its password from)" >&2
    exit 64
fi
username="${AGENT_AUTH_USERNAME:-admin}"

# The response is captured first and handed to Python as an argument: with `python3 -`, the
# heredoc *is* stdin, so a pipe into it would never be read.
response=$(printf 'user = "%s:%s"\n' "$username" "$AGENT_AUTH_PASSWORD" \
    | curl -sf -K - "$base_url/api/audit/patterns")

pod_active=0
verdicts_file=""
receipts_file=""

if [ "$pod_requested" -eq 1 ]; then
    if [ -z "${CISTERN_BASE_URL:-}" ] || [ -z "${CISTERN_TOKEN:-}" ]; then
        echo "pod: not configured"
    else
        pod_active=1
        work=$(mktemp -d)
        trap 'rm -rf "$work"' EXIT
        verdicts_file="$work/verdicts.jsonl"
        receipts_file="$work/receipts.ndjson"
        : >"$verdicts_file"
        : >"$receipts_file"

        # --- enumerate /memory/verdicts/<slug>/ and collect every <pattern-id>-*.yaml file ---
        verdicts_root_status=$(curl -s -o "$work/verdicts-root.ttl" -w '%{http_code}' \
            -H "Authorization: Bearer $CISTERN_TOKEN" -H 'Accept: text/turtle' \
            "$CISTERN_BASE_URL/memory/verdicts/") || verdicts_root_status="000"
        : >"$work/verdict-files.txt"
        if [ "$verdicts_root_status" = "200" ]; then
            slugs=$(python3 - "$work/verdicts-root.ttl" <<'PYEOF'
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
# The server writes the predicate as a full IRI and every member as an absolute URL; keep only
# the last path segment of each child container.
contains = r"(?:ldp:contains|<http://www\.w3\.org/ns/ldp#contains>)\s+((?:<[^>]+>\s*,?\s*)+)"
for m in re.finditer(contains, text):
    for uri in re.findall(r"<([^>]+)>", m.group(1)):
        if uri.endswith("/"):
            print(uri.rstrip("/").rsplit("/", 1)[-1])
PYEOF
            )
            # $slugs is a deliberate newline/space-split list of container names, each one
            # already a path segment with no internal whitespace.
            for slug in $slugs; do
                slug_status=$(curl -s -o "$work/slug.ttl" -w '%{http_code}' \
                    -H "Authorization: Bearer $CISTERN_TOKEN" -H 'Accept: text/turtle' \
                    "$CISTERN_BASE_URL/memory/verdicts/$slug/") || slug_status="000"
                if [ "$slug_status" = "200" ]; then
                    python3 - "$work/slug.ttl" "$slug" <<'PYEOF' >>"$work/verdict-files.txt"
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
slug = sys.argv[2]
contains = r"(?:ldp:contains|<http://www\.w3\.org/ns/ldp#contains>)\s+((?:<[^>]+>\s*,?\s*)+)"
for m in re.finditer(contains, text):
    for uri in re.findall(r"<([^>]+)>", m.group(1)):
        if uri.endswith(".yaml"):
            print(f"{slug}\t{uri.rsplit('/', 1)[-1]}")
PYEOF
                fi
            done
        else
            echo "pattern-digest.sh: GET /memory/verdicts/ returned $verdicts_root_status; no verdicts shown" >&2
        fi

        # --- fetch every verdict file found; newest-per-pattern selection happens in Python ---
        while IFS="$(printf '\t')" read -r slug file; do
            [ -n "${file:-}" ] || continue
            content_status=$(curl -s -o "$work/content.yaml" -w '%{http_code}' \
                -H "Authorization: Bearer $CISTERN_TOKEN" \
                "$CISTERN_BASE_URL/memory/verdicts/$slug/$file") || content_status="000"
            if [ "$content_status" = "200" ]; then
                python3 - "$file" "$work/content.yaml" <<'PYEOF' >>"$verdicts_file"
import json
import sys
import yaml
filename, content_path = sys.argv[1], sys.argv[2]
data = yaml.safe_load(open(content_path, encoding="utf-8")) or {}
# YAML turns `date: 2026-10-03` into a date object; default=str keeps it as the text it was.
print(json.dumps({"filename": filename, **data}, default=str))
PYEOF
            fi
        done <"$work/verdict-files.txt"

        # --- receipts per pattern file: count + last ALLOWED READ per pattern id ---
        # A resource's ?receipts lists decisions about exactly that resource, never its
        # children (DecisionQuery), so the container query says nothing about the files a
        # reviewer read. The files are known locally — the same tree memory-publish.sh
        # mirrored — so each one is asked in turn. The owner's own reads are left out when
        # CISTERN_OWNER_WEBID is set: the column is about who else read the pattern.
        cd "$(dirname "$0")/.."
        find patterns -type f ! -name '.cistern-sync.json' | sort | while IFS= read -r rel; do
            file_status=$(curl -s -o "$work/one.ndjson" -w '%{http_code}' \
                -H "Authorization: Bearer $CISTERN_TOKEN" \
                "$CISTERN_BASE_URL/memory/$rel?receipts") || file_status="000"
            if [ "$file_status" = "200" ]; then
                cat "$work/one.ndjson" >>"$receipts_file"
            elif [ "$file_status" != "404" ]; then
                echo "pattern-digest.sh: GET /memory/$rel?receipts returned $file_status; its reads are not counted" >&2
            fi
        done
    fi
fi

python3 - "$response" "$pod_active" "$verdicts_file" "$receipts_file" "${CISTERN_OWNER_WEBID:-}" <<'PYEOF'
import json
import sys

data = json.loads(sys.argv[1])
pod_active = sys.argv[2] == "1"
verdicts_path = sys.argv[3]
receipts_path = sys.argv[4]
owner_webid = sys.argv[5]

loaded = [p for p in data if p.get("loads", 0) > 0]
never = [p for p in data if p.get("loads", 0) == 0]

columns = ["pattern", "version(s)", "loads", "last loaded"]
if pod_active:
    columns = columns + ["last verdict", "reads"]


def last_verdict_for(pattern_id):
    best = None
    with open(verdicts_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            # Match on the verdict's own `pattern` field, not the filename prefix: a pattern
            # whose id extends another's (`x-v2` next to `x`) would otherwise be attributed
            # to both, and could win "newest" for the wrong one.
            if rec.get("pattern") != pattern_id:
                continue
            filename = rec.get("filename", "")
            if best is None or filename > best.get("filename", ""):
                best = rec
    if best is None:
        return "—"
    return f"{best.get('verdict', '?')} ({best.get('date', '?')})"


def reads_for(pattern_id):
    count = 0
    last_at = None
    prefix = f"/memory/patterns/{pattern_id}/"
    with open(receipts_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            if rec.get("required") != "READ" or rec.get("outcome") != "ALLOWED":
                continue
            if owner_webid and rec.get("agent") == owner_webid:
                continue
            target = rec.get("target", "")
            if prefix not in target:
                continue
            count += 1
            at = rec.get("at")
            if at and (last_at is None or at > last_at):
                last_at = at
    if count == 0:
        return "0"
    return f"{count} (last {last_at})"


def row_of(p):
    row = [
        p.get("patternId", ""),
        ", ".join(p.get("versions") or []) or "—",
        str(p.get("loads", 0)),
        p.get("lastLoadedAt") or "never",
    ]
    if pod_active:
        pid = p.get("patternId", "")
        row = row + [last_verdict_for(pid), reads_for(pid)]
    return row


rows = [row_of(p) for p in loaded + never]
widths = [len(c) for c in columns]
for row in rows:
    widths = [max(w, len(v)) for w, v in zip(widths, row)]


def line(values):
    return " | ".join(v.ljust(w) for v, w in zip(values, widths))


print(line(columns))
print("-+-".join("-" * w for w in widths))
for row in rows:
    print(line(row))
PYEOF
