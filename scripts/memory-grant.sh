#!/bin/sh
# memory-grant.sh — grants one reviewer or one agent access into the owner's /memory/ pod
# (issue #82, slice 5 of #71). Two forms:
#
#   scripts/memory-grant.sh reviewer <webid> --as <slug> [--project <name>] [--client <uri>]... [--base <url>]
#     read  /memory/patterns/                 (or, with --project, /memory/projects/<name>/patterns/
#                                              and nothing above it: a sectioned root, #91)
#     read + write /memory/verdicts/<slug>/   (created first, owner PUT, If-None-Match: *)
#
#   scripts/memory-grant.sh agent <webid> [--base <url>]
#     read  /memory/                          (the owner's own agent, e.g. a hosted session).
#                                              After a sectioned root is published (#91) that is
#                                              every project's facts and episodes too: grant it
#                                              only to an agent that acts as the owner.
#
# A reviewer never gets read on /memory/requests/ or /memory/references/ — those request cards
# carry the owner's own reasons and refused options, the owner's to show, not the grant's
# default.
#
# --client <uri> (repeatable) passes through to `cistern grant` unchanged, narrowing the grant
# to requests through a named client (ADR 0004 delegation) — this is a Cistern `main`-only CLI
# option, not in the pinned v0.2.0 release (same CISTERN_CLI_JAR constraint as
# scripts/memory-publish.sh's `sync`); applied to both grant calls in the reviewer form.
# --until is not built here: Cistern #92 (expiry) has not shipped, so there is nothing to pass
# through yet.
#
# Reads CISTERN_TOKEN (the owner token) from the environment; never printed or passed on argv.
set -eu
cd "$(dirname "$0")/.."

base_url="http://127.0.0.1:3737"
clients=""

usage() {
    cat <<'USAGE' >&2
Usage: memory-grant.sh reviewer <webid> --as <slug> [--project <name>] [--client <uri>]... [--base <url>]
       memory-grant.sh agent    <webid> [--base <url>]

--client narrows the grant to requests through a named client (ADR 0004; Cistern main-only).
--until is not built here (Cistern #92, expiry, has not shipped).
USAGE
}

if [ $# -lt 2 ]; then
    usage
    exit 64
fi

form="$1"
webid="$2"
shift 2

slug=""
project=""
while [ $# -gt 0 ]; do
    case "$1" in
        --as|--client|--base|--project)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "memory-grant.sh: $1 needs a value" >&2; usage; exit 64; }
            case "$1" in
                --as) slug="$2" ;;
                --client) clients="$clients $2" ;;
                --base) base_url="$2" ;;
                *) project="$2" ;;
            esac
            shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "memory-grant.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

case "$form" in
    reviewer|agent) ;;
    *) echo "memory-grant.sh: form must be 'reviewer' or 'agent', got '$form'" >&2; usage; exit 64 ;;
esac

if [ -z "${CISTERN_CLI_JAR:-}" ] || [ ! -f "$CISTERN_CLI_JAR" ]; then
    echo "memory-grant.sh: CISTERN_CLI_JAR must point at a built cistern-cli jar" >&2
    exit 64
fi
if [ -z "${CISTERN_TOKEN:-}" ]; then
    echo "memory-grant.sh: CISTERN_TOKEN (the owner token) must be set in the environment" >&2
    exit 64
fi

cistern() {
    java -jar "$CISTERN_CLI_JAR" "$@"
}

grant_with_clients() {
    # $clients is a deliberate word-split list of --client values, each one already a URI
    # with no internal whitespace.
    set -- grant "$webid" "$@" --base "$base_url"
    for c in $clients; do
        set -- "$@" --client "$c"
    done
    echo "[memory-grant] cistern $*"
    cistern "$@"
}

case "$form" in
    agent)
        if [ -n "$slug" ] || [ -n "$project" ]; then
            echo "memory-grant.sh: --as and --project are only for the 'reviewer' form" >&2
            exit 64
        fi
        grant_with_clients --read /memory/
        echo "[memory-grant] granted: $webid may read /memory/"
        exit 0
        ;;
esac

# --- reviewer form from here ---
if [ -z "$slug" ]; then
    echo "memory-grant.sh: reviewer form requires --as <slug>" >&2
    usage
    exit 64
fi
case "$slug" in
    *[!a-z0-9-]*|'')
        echo "memory-grant.sh: --as slug must match [a-z0-9-]+, got '$slug'" >&2
        exit 64
        ;;
esac
patterns_path="/memory/patterns/"
if [ -n "$project" ]; then
    case "$project" in
        penstock|cistern|valuedocs|site) patterns_path="/memory/projects/$project/patterns/" ;;
        *) echo "memory-grant.sh: --project must be one of penstock, cistern, valuedocs, site; got '$project'" >&2; exit 64 ;;
    esac
fi

verdicts_path="/memory/verdicts/$slug/"

echo "[memory-grant] creating $verdicts_path (owner PUT, If-None-Match: *)"
# A container's representation is an RDF source (Solid Protocol §4.2), so the empty body is
# declared as Turtle: without the header curl sends application/x-www-form-urlencoded and the
# server answers 409. Solid §5.3 has the server create the missing /memory/verdicts/ parent.
# The owner token reaches curl through -K - (its config on stdin), never as -H on argv.
http_status=$(printf 'header = "Authorization: Bearer %s"\n' "$CISTERN_TOKEN" \
    | curl -s -K - -o /dev/null -w '%{http_code}' -X PUT \
    -H 'If-None-Match: *' \
    -H 'Content-Type: text/turtle' \
    --data-binary '' \
    "$base_url$verdicts_path")
case "$http_status" in
    201) echo "[memory-grant] created $verdicts_path" ;;
    412) echo "[memory-grant] $verdicts_path already exists, left as it is" ;;
    *)
        echo "memory-grant.sh: creating $verdicts_path returned HTTP $http_status" >&2
        exit 1
        ;;
esac

grant_with_clients --read "$patterns_path"
echo "[memory-grant] granted: $webid may read $patterns_path"

status=0
grant_with_clients --read --write "$verdicts_path" || status=$?
if [ "$status" -ne 0 ]; then
    echo "memory-grant.sh: granting read+write on $verdicts_path failed (exit $status) after the" \
        "$patterns_path read grant already succeeded. To undo that grant:" >&2
    echo "  java -jar \"\$CISTERN_CLI_JAR\" revoke $webid $patterns_path --base $base_url" >&2
    exit "$status"
fi
echo "[memory-grant] granted: $webid may read and write $verdicts_path"
