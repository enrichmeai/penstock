#!/bin/sh
# memory-publish.sh — mirrors requests/, references/ and patterns/ into the owner's Cistern
# pod under /memory/ (issue #82, slice 5 of #71): no copy of the store leaves the owner's
# side, a reviewer or a hosted session reads through a grant instead (scripts/memory-grant.sh).
#
# Usage: scripts/memory-publish.sh [--dry-run] [--delete] [--base <url>] [--root <dir>]
#
#   cistern sync requests   /memory/requests/
#   cistern sync references /memory/references/
#   cistern sync patterns   /memory/patterns/
#
# --root <dir> (else MEMORY_ROOT, else this repository) chooses the memory root (#91,
# docs/memory-root.md). A sectioned root (estate/ plus projects/<name>/) is mirrored whole: one
# sync per card folder that exists in each section, to the same path under /memory/:
#
#   cistern sync <root>/estate/facts              /memory/estate/facts/
#   cistern sync <root>/projects/valuedocs/facts  /memory/projects/valuedocs/facts/
#   ...
#
# The pod is the owner's own, so a sectioned root's episodes and facts go too, sub-folders
# included (facts/rejected/); a reviewer grant still reaches only one project's patterns
# (scripts/memory-grant.sh --project), but the `agent` grant (/memory/) now reads every project's
# facts and episodes. A flat root publishes requests/, references/ and patterns/ as before.
# --delete prunes inside each container in the plan only: a folder removed locally (a whole
# episodes/, or the old flat /memory/patterns/ after a migration) stays on the pod until removed
# by hand. A folder in a section that is not a card folder is reported (WARN) and not published.
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
caller_dir=$(pwd)   # a relative --root or MEMORY_ROOT means relative to where it was run
tooldir=$(cd "$(dirname "$0")/.." && pwd)
cd "$tooldir"

base_url="http://127.0.0.1:3737"
root=""
dry_run=0
delete=0

usage() {
    echo "Usage: memory-publish.sh [--dry-run] [--delete] [--base <url>] [--root <dir>]" >&2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) dry_run=1; shift ;;
        --delete) delete=1; shift ;;
        --base|--root)
            [ $# -ge 2 ] && [ -n "$2" ] || { echo "memory-publish.sh: $1 needs a value" >&2; usage; exit 64; }
            if [ "$1" = --base ]; then base_url="$2"; else root="$2"; fi
            shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "memory-publish.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

[ -n "$root" ] || root="${MEMORY_ROOT:-$tooldir}"
case "$root" in /*) ;; *) root="$caller_dir/$root" ;; esac
[ -d "$root" ] || { echo "memory-publish.sh: root '$root' is not a directory" >&2; exit 64; }
root=$(cd "$root" && pwd)

# The plan: "<pod container> <local folder relative to the root>" per line; the syncs run from
# the root, so a flat root's calls are exactly what they were before --root existed, every one checked before anything is sent.
if [ -d "$root/projects" ]; then
    layout=sectioned
    [ -d "$root/estate" ] || { echo "memory-publish.sh: $root is sectioned but has no estate/" >&2; exit 64; }
    plan=""
    for sec in "$root/estate" "$root"/projects/*; do
        [ -d "$sec" ] || continue
        rel=${sec#"$root"/}
        case "$rel" in
            estate|projects/penstock|projects/cistern|projects/valuedocs|projects/site) ;;
            *) echo "memory-publish.sh: $rel is not a known section; check-cards.sh fails it too" >&2; exit 64 ;;
        esac
        for kind in requests references patterns episodes facts; do
            [ -d "$sec/$kind" ] && plan="$plan/memory/$rel/$kind/ $rel/$kind
"
        done
        for other in "$sec"/*; do
            [ -d "$other" ] || continue
            case "${other##*/}" in requests|references|patterns|episodes|facts) ;;
                *) echo "WARN: $rel/${other##*/}/ is not a card folder; not published" >&2 ;; esac
        done
    done
    [ -n "$plan" ] || { echo "memory-publish.sh: $root has no card folders to publish" >&2; exit 64; }
else
    layout=flat
    for dir in requests references patterns; do
        if [ ! -d "$root/$dir" ]; then
            echo "memory-publish.sh: $dir/ is missing; refusing to publish an incomplete store" >&2
            exit 64
        fi
    done
    plan="/memory/requests/ requests
/memory/references/ references
/memory/patterns/ patterns
"
fi

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

echo "=== memory-publish: scripts/check-cards.sh --root $root ($layout) ==="
if ! sh "$tooldir/scripts/check-cards.sh" --root "$root"; then
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

cd "$root"
# Not a pipe: a pipe would run the loop in a subshell, where `exit` on a failed sync and the
# pod_created flag would not reach this shell. fd 3, so the java CLI cannot read the plan on stdin.
while read -r pod_path local_dir <&3; do
    [ -n "$pod_path" ] || continue
    sync_one "$local_dir" "$pod_path"
done 3<<PLAN
$plan
PLAN

echo "[memory-publish] done"
