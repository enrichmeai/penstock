#!/bin/sh
# Prints a per-pattern use digest from GET /api/audit/patterns (issue #81): how many turns
# loaded each pattern, when it was last loaded, and which patterns have never been loaded —
# listed last, so pruning patterns/ is a reading of this table, not a memory exercise.
#
# Usage: scripts/pattern-digest.sh [base-url]
#   base-url defaults to http://localhost:8080
#
# Reads the basic-auth credentials from the same env vars README.md documents for a running
# instance (AGENT_AUTH_USERNAME, default "admin"; AGENT_AUTH_PASSWORD, required) — never from
# an argument, so the password never ends up in shell history. curl gets them through a config
# read from its stdin (`-K -`), not through `-u`, so they are not in its argv either.
set -eu

base_url="${1:-http://localhost:8080}"

if [ -z "${AGENT_AUTH_PASSWORD:-}" ]; then
    echo "pattern-digest.sh: AGENT_AUTH_PASSWORD must be set (the same env var the running instance reads its password from)" >&2
    exit 64
fi
username="${AGENT_AUTH_USERNAME:-admin}"

# The response is captured first and handed to Python as an argument: with `python3 -`, the
# heredoc *is* stdin, so a pipe into it would never be read.
response=$(printf 'user = "%s:%s"\n' "$username" "$AGENT_AUTH_PASSWORD" \
    | curl -sf -K - "$base_url/api/audit/patterns")

python3 - "$response" <<'PYEOF'
import json
import sys

data = json.loads(sys.argv[1])

loaded = [p for p in data if p.get("loads", 0) > 0]
never = [p for p in data if p.get("loads", 0) == 0]

columns = ["pattern", "version(s)", "loads", "last loaded"]


def row_of(p):
    return [
        p.get("patternId", ""),
        ", ".join(p.get("versions") or []) or "—",
        str(p.get("loads", 0)),
        p.get("lastLoadedAt") or "never",
    ]


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
