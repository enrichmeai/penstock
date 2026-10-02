#!/usr/bin/env bash
# PostToolUse (matcher: Task), issue #78: the reviewer has returned, so the review is no longer
# pending. In the foreground a Task returns only when its agent has finished, which is the
# point of guard-task.sh denying run_in_background. Any other agent returning changes nothing.
set -uo pipefail
input=$(cat)
tool=$(printf '%s' "$input" | jq -r '.tool_name // "Task"')
[ "$tool" = "Task" ] || exit 0
sub=$(printf '%s' "$input" | jq -r '.tool_input.subagent_type // ""')
[ "$sub" = "reviewer" ] || exit 0
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0
gd=$(git rev-parse --git-dir 2>/dev/null) || exit 0
rm -f "$gd/claude-review.pending"
exit 0
