#!/bin/sh
# memory-revoke.sh — revokes a grant made by scripts/memory-grant.sh (issue #82, slice 5 of
# #71). Revoking stops the *next* request; it does not undo anything already read or written,
# and does not touch /memory/verdicts/<slug>/'s contents — a verdict already written stays.
#
#   scripts/memory-revoke.sh <webid> --as <slug> [--project <name>]   # the reviewer form: both
#                                                   grants undone (--project as it was granted)
#   scripts/memory-revoke.sh <webid>                # the agent form: /memory/ undone
#
# Reads CISTERN_TOKEN (the owner token) from the environment; never printed or passed on argv.
set -eu
cd "$(dirname "$0")/.."

base_url="http://127.0.0.1:3737"
slug=""
project=""

usage() {
    echo "Usage: memory-revoke.sh <webid> [--as <slug> [--project <name>]] [--base <url>]" >&2
}

if [ $# -lt 1 ]; then
    usage
    exit 64
fi
webid="$1"
shift

while [ $# -gt 0 ]; do
    case "$1" in
        --as|--base|--project)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "memory-revoke.sh: $1 needs a value" >&2; usage; exit 64; }
            case "$1" in --as) slug="$2" ;; --base) base_url="$2" ;; *) project="$2" ;; esac
            shift 2 ;;
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
    patterns_path="/memory/patterns/"
    if [ -n "$project" ]; then
        case "$project" in
            penstock|cistern|valuedocs|site) patterns_path="/memory/projects/$project/patterns/" ;;
            *) echo "memory-revoke.sh: --project must be one of penstock, cistern, valuedocs, site; got '$project'" >&2; exit 64 ;;
        esac
    fi
    echo "[memory-revoke] cistern revoke $webid $patterns_path"
    cistern revoke "$webid" "$patterns_path" --base "$base_url"
    echo "[memory-revoke] cistern revoke $webid /memory/verdicts/$slug/"
    cistern revoke "$webid" "/memory/verdicts/$slug/" --base "$base_url"
else
    [ -z "$project" ] || { echo "memory-revoke.sh: --project needs --as (the reviewer form)" >&2; exit 64; }
    echo "[memory-revoke] cistern revoke $webid /memory/"
    cistern revoke "$webid" /memory/ --base "$base_url"
fi

echo "[memory-revoke] revoked. To see what $webid did while granted, as the owner:"
encoded_webid=$(printf '%s' "$webid" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))')
echo "  printf 'header = \"Authorization: Bearer %s\"\\n' \"\$CISTERN_TOKEN\" | curl -K - \"$base_url/?receipts&agent=$encoded_webid\""
