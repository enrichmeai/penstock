#!/usr/bin/env bash
# Stop: after a session that committed on a task branch, print the write-episode.sh command that
# drafts this work's episode (issue #86). It never writes a file and never blocks the stop: the
# draft is the owner's to make and edit. Silent when the branch is main, has no commits beyond
# origin/main, or the issue number cannot be read from the branch name.
#   branch shapes: claude/issue-<n>-..., feat/<n>-..., fix/<n>-..., chore/<n>-..., site/<n>-..., docs/<n>-...
set -uo pipefail
[ "${CLAUDE_SKIP_STOP_GATES:-}" = "1" ] && exit 0
input=$(cat)
active=$(printf '%s' "$input" | jq -r '.stop_hook_active // false')
[ "$active" = "true" ] && exit 0            # never repeat the hint on the retry pass

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" || exit 0
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0
case "$branch" in
  main|HEAD|"") exit 0 ;;
esac
git rev-parse --verify -q origin/main >/dev/null 2>&1 || exit 0
base=$(git merge-base origin/main HEAD 2>/dev/null) || exit 0
count=$(git rev-list --count "$base..HEAD" 2>/dev/null || echo 0)
[ "$count" -gt 0 ] || exit 0

issue=$(printf '%s' "$branch" | sed -nE 's#^(claude/issue-|feat/|fix/|chore/|site/|docs/)([0-9]+).*#\2#p')
[ -n "$issue" ] || exit 0
project=$(basename "$root"); case "$project" in enrichmeai.github.io) project=site ;; esac
case "$project" in penstock|cistern|valuedocs|site) ;; *) project=penstock ;; esac
short=$(git rev-parse --short "$base")

jq -n --arg m "This branch has $count commit(s) for #$issue and no episode yet. Draft it: scripts/write-episode.sh --project $project --issue $issue --range $short..HEAD   (edit the draft, then scripts/check-cards.sh; see episodes/README.md)" '{systemMessage: $m}'
exit 0
