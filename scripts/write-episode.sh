#!/bin/sh
# write-episode.sh — drafts episodes/<date>-<slug>.yaml from a task's own evidence (issue #86,
# slice 1 of #85): the issue, the pull request, the commits in a range that reference the
# issue, and the markers people left in commit bodies (Decided:/Refused:/Learned:/Open:).
# The owner edits the draft; it reaches main by PR like every other card.
#
# Usage: write-episode.sh --project <p> [--issue <n>] [--pr <n>] [--range <base>..<head>]
#                         [--slug <s>] [--date <YYYY-MM-DD>] [--repo owner/name]
#                         [--dry-run] [--out <file>] [--replace]
#
#   --project   one of the projects episodes/README.md lists (required)
#   --issue     scopes --range to the commits mentioning #<n> (git log --grep, as
#               promote-pattern.sh does) and supplies `asked` from the issue title
#   --pr        the pull request; when omitted and --range is given, the PRs that contain
#               the range's head commit are looked up and the first merged one is used
#   --range     <base>..<head>; falls back to the whole range when no commit mentions the issue
#   --slug      filename slug (default: from the issue or PR title); --date defaults to today
#   --repo      GitHub repository for gh api calls (default enrichmeai/penstock)
#   --dry-run   print the draft, write nothing
#   --out       destination file, confined to this repository's episodes/ folder
#   --replace   allow overwriting an existing episode (never by default)
#
# GitHub reads go through `gh api` only, so the same script runs where gh is the real CLI and
# where it is a thin api-only client; without gh, or offline, the draft is built from git alone
# and says so. Credential shapes are refused before anything is written (exit 2, path and line
# only, via scripts/lib/credential-grep.sh). Exit codes: 0 ok, 1 failure, 2 credential shape,
# 3 destination exists without --replace, 64 usage.
set -eu
cd "$(dirname "$0")/.."
repo_root=$(pwd)

project=""; issue=""; pr=""; range=""; slug=""; date=""; repo="enrichmeai/penstock"
dry_run=0; out=""; replace=0

usage() { sed -n '2,25p' "$0" >&2; }

while [ $# -gt 0 ]; do
    case "$1" in
        --project) project="$2"; shift 2 ;;
        --issue) issue="$2"; shift 2 ;;
        --pr) pr="$2"; shift 2 ;;
        --range) range="$2"; shift 2 ;;
        --slug) slug="$2"; shift 2 ;;
        --date) date="$2"; shift 2 ;;
        --repo) repo="$2"; shift 2 ;;
        --dry-run) dry_run=1; shift ;;
        --out) out="$2"; shift 2 ;;
        --replace) replace=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "write-episode.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

[ -n "$project" ] || { echo "write-episode.sh: --project is required" >&2; exit 64; }
case "$project" in
    penstock|cistern|valuedocs|site|estate) ;;
    *) echo "write-episode.sh: --project must be one of penstock, cistern, valuedocs, site, estate" >&2; exit 64 ;;
esac
if [ -n "$range" ]; then
    case "$range" in
        *..*) ;;
        *) echo "write-episode.sh: --range must be <base>..<head>, got '$range'" >&2; exit 64 ;;
    esac
fi
[ -n "$date" ] || date=$(date -u +%Y-%m-%d)
case "$date" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
    *) echo "write-episode.sh: --date must be YYYY-MM-DD" >&2; exit 64 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

have_gh=0
if command -v gh >/dev/null 2>&1; then have_gh=1; fi

# --- gather: issue, pull request, files ---------------------------------------------------
issue_ref=""; pr_ref=""
if [ -n "$issue" ]; then
    issue_ref="$repo#$issue"
    if [ "$have_gh" -eq 1 ]; then
        gh api "repos/$repo/issues/$issue" >"$work/issue.json" 2>"$work/gh.err" \
            || { echo "write-episode.sh: could not read issue #$issue ($(head -c 200 "$work/gh.err")); continuing without it" >&2; rm -f "$work/issue.json"; }
    else
        echo "write-episode.sh: gh not found; the draft is built from git alone" >&2
    fi
fi

base=""; head=""
if [ -n "$range" ]; then
    base=${range%%..*}; head=${range##*..}
    git rev-parse --verify -q "$base^{commit}" >/dev/null || { echo "write-episode.sh: unknown base '$base'" >&2; exit 64; }
    git rev-parse --verify -q "$head^{commit}" >/dev/null || { echo "write-episode.sh: unknown head '$head'" >&2; exit 64; }
    if [ -n "$issue" ]; then
        git log --format='%H' "$base..$head" --grep="#$issue"'[^0-9]' -E >"$work/shas" || true
        if [ ! -s "$work/shas" ]; then
            echo "write-episode.sh: no commit in $range mentions #$issue; using the whole range" >&2
            git log --format='%H' "$base..$head" >"$work/shas"
        fi
    else
        git log --format='%H' "$base..$head" >"$work/shas"
    fi
    : >"$work/commits.txt"
    while IFS= read -r sha; do
        [ -n "$sha" ] || continue
        git log -1 --format='%H%x09%s%n%b' "$sha" >>"$work/commits.txt"
        printf '\0' >>"$work/commits.txt"
    done <"$work/shas"
fi

if [ -z "$pr" ] && [ -n "$head" ] && [ "$have_gh" -eq 1 ]; then
    head_sha=$(git rev-parse "$head")
    pr=$(gh api "repos/$repo/commits/$head_sha/pulls" 2>/dev/null \
        | python3 -c 'import json,sys
items=json.load(sys.stdin)
merged=[p for p in items if p.get("merged_at")]
print((merged or items or [{}])[0].get("number",""))' 2>/dev/null || true)
fi
if [ -n "$pr" ]; then
    pr_ref="$repo#$pr"
    if [ "$have_gh" -eq 1 ]; then
        gh api "repos/$repo/pulls/$pr" >"$work/pr.json" 2>"$work/gh.err" \
            || { echo "write-episode.sh: could not read PR #$pr; continuing without it" >&2; rm -f "$work/pr.json"; }
        gh api "repos/$repo/pulls/$pr/files?per_page=100" >"$work/pr_files.json" 2>/dev/null || rm -f "$work/pr_files.json"
    fi
fi

# --- slug and id --------------------------------------------------------------------------
if [ -z "$slug" ]; then
    title=""
    [ -f "$work/issue.json" ] && title=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("title",""))' "$work/issue.json")
    [ -z "$title" ] && [ -f "$work/pr.json" ] && title=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("title",""))' "$work/pr.json")
    [ -z "$title" ] && title="episode"
    # the first six words of the title, lower-kebab: long enough to recognise, short enough to type
    slug=$(printf '%s' "$title" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
        | cut -d- -f1-6 | sed -E 's/-+$//')
fi
case "$slug" in
    ''|-*|*[!a-z0-9-]*) echo "write-episode.sh: slug must match [a-z0-9][a-z0-9-]*, got '$slug'" >&2; exit 64 ;;
esac
id="$date-$slug"

python3 - "$work" "$id" "$date" "$project" "$issue_ref" "$pr_ref" <<'PYEOF'
import json, sys
work, id_, date, project, issue_ref, pr_ref = sys.argv[1:7]
json.dump({"id": id_, "date": date, "project": project, "issue_ref": issue_ref, "pr_ref": pr_ref},
          open(f"{work}/meta.json", "w"))
PYEOF

# --- draft, credential grep, write ------------------------------------------------------
mkdir -p "$work/draft"
python3 scripts/lib/write_episode_generate.py "$work" >"$work/draft/$id.yaml"

if hits=$(sh scripts/lib/credential-grep.sh "$work/draft"); then :; else
    rc=$?
    if [ "$rc" -eq 2 ]; then
        echo "write-episode.sh: the draft contains a credential-shaped value; nothing written. Lines (path:line only):" >&2
        printf '%s\n' "$hits" | sed "s|$work/draft/|episodes/|" >&2
        exit 2
    fi
    echo "write-episode.sh: credential-grep.sh exited $rc" >&2; exit 1
fi

if [ "$dry_run" -eq 1 ]; then
    cat "$work/draft/$id.yaml"
    exit 0
fi

[ -n "$out" ] || out="episodes/$id.yaml"
case "$out" in
    /*) out_abs="$out" ;;
    *) out_abs="$repo_root/$out" ;;
esac
out_abs=$(python3 -c 'import os,sys; print(os.path.normpath(sys.argv[1]))' "$out_abs")
# `*` in a case glob also matches `/`, so refuse a sub-folder explicitly: check-cards.sh only
# checks episodes/*.yaml, and a file one level down would never be validated.
case "$out_abs" in
    "$repo_root"/episodes/*/*) echo "write-episode.sh: --out must be directly under episodes/, not a sub-folder, got '$out'" >&2; exit 64 ;;
    "$repo_root"/episodes/*.yaml) ;;
    *) echo "write-episode.sh: --out must be a .yaml file under episodes/ in this repository, got '$out'" >&2; exit 64 ;;
esac
if [ -e "$out_abs" ] && [ "$replace" -eq 0 ]; then
    echo "write-episode.sh: $out already exists; pass --replace to overwrite it deliberately (nothing written)" >&2
    exit 3
fi
mkdir -p "$(dirname "$out_abs")"
cp "$work/draft/$id.yaml" "$out_abs"
echo "write-episode.sh: draft written to $out — edit it, then run scripts/check-cards.sh"
