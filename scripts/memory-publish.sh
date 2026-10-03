#!/bin/sh
# memory-publish.sh — mirrors requests/, references/ and patterns/ into the owner's Cistern
# pod under /memory/ (issue #82, slice 5 of #71): no copy of the store leaves the owner's
# side, a reviewer or a hosted session reads through a grant instead (scripts/memory-grant.sh).
#
# Usage: scripts/memory-publish.sh [--dry-run] [--delete] [--base <url>]
#
#   cistern sync requests   /memory/requests/
#   cistern sync references /memory/references/
#   cistern sync patterns   /memory/patterns/
#
# `cistern sync` is T7.17, landed on Cistern's `main` and not in the pinned `v0.2.0` release —
# CISTERN_CLI_JAR must point at a jar built from a `main` commit that carries it (see this
# repo's README § "Memory store in a pod" for the build-from-source step). This script prints
# the exact jar path it used so a stale build is visible, never silent.
#
# Reads CISTERN_TOKEN (the owner token) and CISTERN_OWNER_WEBID from the environment; neither
# is ever printed. CISTERN_OWNER_WEBID is only used if the very first sync reports the /memory/
# container absent (a REFUSED exit, Cistern's exit code 2) — this script then runs
# `cistern pod create --root /memory/ --owner "$CISTERN_OWNER_WEBID"` once and retries that one
# sync; a re-run against an already-provisioned /memory/ never calls pod create at all, so the
# whole script is idempotent.
#
# Refuses to run if requests/, references/ or patterns/ is missing, and runs
# scripts/check-cards.sh first — never publish a store that fails its own check.
set -eu
cd "$(dirname "$0")/.."

base_url="http://127.0.0.1:3737"
dry_run=0
delete=0

usage() {
    echo "Usage: memory-publish.sh [--dry-run] [--delete] [--base <url>]" >&2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) dry_run=1; shift ;;
        --delete) delete=1; shift ;;
        --base) base_url="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "memory-publish.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

for dir in requests references patterns; do
    if [ ! -d "$dir" ]; then
        echo "memory-publish.sh: $dir/ is missing; refusing to publish an incomplete store" >&2
        exit 64
    fi
done

if [ -z "${CISTERN_CLI_JAR:-}" ] || [ ! -f "$CISTERN_CLI_JAR" ]; then
    echo "memory-publish.sh: CISTERN_CLI_JAR must point at a cistern-cli jar built from a Cistern" \
        "main commit that carries 'cistern sync' (T7.17; not in v0.2.0) — see README" >&2
    exit 64
fi
echo "[memory-publish] using CLI jar: $CISTERN_CLI_JAR"

if [ -z "${CISTERN_TOKEN:-}" ]; then
    echo "memory-publish.sh: CISTERN_TOKEN (the owner token) must be set in the environment" >&2
    exit 64
fi
if [ -z "${CISTERN_OWNER_WEBID:-}" ]; then
    echo "memory-publish.sh: CISTERN_OWNER_WEBID must be set (used only if /memory/ does not exist yet)" >&2
    exit 64
fi

echo "=== memory-publish: scripts/check-cards.sh ==="
if ! sh scripts/check-cards.sh; then
    echo "memory-publish.sh: check-cards.sh failed; refusing to publish a store that fails its own check" >&2
    exit 1
fi

# CISTERN_TOKEN is read by the CLI itself as the default for --token (never passed on argv,
# never echoed by this script); it only needs to already be in this process's environment for
# the java subprocess below to inherit it.
cistern() {
    java -jar "$CISTERN_CLI_JAR" "$@"
}

pod_created=0
provision_root() {
    if [ "$pod_created" -eq 1 ]; then
        return 0
    fi
    echo "[memory-publish] /memory/ reported absent; provisioning it as $CISTERN_OWNER_WEBID"
    cistern pod create --root /memory/ --owner "$CISTERN_OWNER_WEBID" --base "$base_url"
    pod_created=1
}

# Syncs one local folder into one pod container, provisioning /memory/ on the first REFUSED
# (Cistern exit code 2) and retrying exactly once — never in a loop, so a REFUSED that isn't
# about the root container being absent still fails loudly instead of retrying forever.
sync_one() {
    local_dir="$1"
    pod_path="$2"
    attempt=0
    while :; do
        attempt=$((attempt + 1))
        set -- sync "$local_dir" "$pod_path" --base "$base_url"
        [ "$dry_run" -eq 1 ] && set -- "$@" --dry-run
        [ "$delete" -eq 1 ] && set -- "$@" --delete
        echo "[memory-publish] cistern $*"
        status=0
        cistern "$@" || status=$?
        if [ "$status" -eq 0 ]; then
            return 0
        fi
        if [ "$status" -eq 2 ] && [ "$attempt" -eq 1 ]; then
            provision_root
            continue
        fi
        echo "memory-publish.sh: cistern sync $local_dir -> $pod_path exited $status" >&2
        exit "$status"
    done
}

sync_one requests   /memory/requests/
sync_one references /memory/references/
sync_one patterns   /memory/patterns/

echo "[memory-publish] done"
