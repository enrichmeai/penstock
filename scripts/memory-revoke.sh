#!/bin/sh
# memory-revoke.sh — revokes a grant made by scripts/memory-grant.sh (issue #82, slice 5 of
# #71). Revoking stops the *next* request; it does not undo anything already read or written,
# and does not touch /memory/verdicts/<slug>/'s contents — a verdict already written stays.
#
#   scripts/memory-revoke.sh <webid> --as <slug>   # the reviewer form: both grants undone
#   scripts/memory-revoke.sh <webid>                # the agent form: /memory/ undone
#
# Reads CISTERN_TOKEN (the owner token) from the environment; never printed or passed on argv.
set -eu
cd "$(dirname "$0")/.."

base_url="http://127.0.0.1:3737"
slug=""

usage() {
    echo "Usage: memory-revoke.sh <webid> [--as <slug>] [--base <url>]" >&2
}

if [ $# -lt 1 ]; then
    usage
    exit 64
fi
webid="$1"
shift

while [ $# -gt 0 ]; do
    case "$1" in
        --as) slug="$2"; shift 2 ;;
        --base) base_url="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "memory-revoke.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

if [ -z "${CISTERN_CLI_JAR:-}" ] || [ ! -f "$CISTERN_CLI_JAR" ]; then
    echo "memory-revoke.sh: CISTERN_CLI_JAR must point at a built cistern-cli jar" >&2
    exit 64
fi
if [ -z "${CISTERN_TOKEN:-}" ]; then
    echo "memory-revoke.sh: CISTERN_TOKEN (the owner token) must be set in the environment" >&2
    exit 64
fi

cistern() {
    java -jar "$CISTERN_CLI_JAR" "$@"
}

if [ -n "$slug" ]; then
    case "$slug" in
        *[!a-z0-9-]*|'')
            echo "memory-revoke.sh: --as slug must match [a-z0-9-]+, got '$slug'" >&2
            exit 64
            ;;
    esac
    echo "[memory-revoke] cistern revoke $webid /memory/patterns/"
    cistern revoke "$webid" /memory/patterns/ --base "$base_url"
    echo "[memory-revoke] cistern revoke $webid /memory/verdicts/$slug/"
    cistern revoke "$webid" "/memory/verdicts/$slug/" --base "$base_url"
else
    echo "[memory-revoke] cistern revoke $webid /memory/"
    cistern revoke "$webid" /memory/ --base "$base_url"
fi

echo "[memory-revoke] revoked. To see what $webid did while granted, as the owner:"
encoded_webid=$(printf '%s' "$webid" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))')
echo "  printf 'header = \"Authorization: Bearer %s\"\\n' \"\$CISTERN_TOKEN\" | curl -K - \"$base_url/?receipts&agent=$encoded_webid\""
