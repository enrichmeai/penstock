#!/usr/bin/env bash
# PreToolUse (matcher: Task), issue #78. Two guards that make the reviewer verdict unavoidable:
#   1. No background agents. The run ends when the turn ends, and a background agent dies
#      with it — five builds in a row (#214 in cistern, #62, #72, #73, #77 here) ended with
#      "reviewer — in progress" and no verdict for exactly this reason. Deny with the reason
#      fed back; the caller re-issues the same Task in the foreground.
#   2. A started review must finish. For subagent_type "reviewer" leave a marker in the git
#      dir; clear-review.sh removes it when the Task returns (PostToolUse), and
#      stop-fast-gates.sh refuses to end the turn while it is present.
# Output follows the other hooks here: a JSON decision on stdout, exit 0; nothing on a plain
# allow. Cases are pinned in test-hooks.sh.
set -uo pipefail
input=$(cat)
tool=$(printf '%s' "$input" | jq -r '.tool_name // "Task"')
[ "$tool" = "Task" ] || exit 0

bg=$(printf '%s' "$input" | jq -r '.tool_input.run_in_background // false')
sub=$(printf '%s' "$input" | jq -r '.tool_input.subagent_type // ""')

if [ "$bg" = "true" ]; then
  jq -n '{hookSpecificOutput:{hookEventName:"PreToolUse", permissionDecision:"deny",
          permissionDecisionReason:"Run agents in the foreground: the run ends when you end your turn, and a background agent dies with it. Call Task again without run_in_background and wait for its result."}}'
  exit 0
fi

if [ "$sub" = "reviewer" ]; then
  root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
  cd "$root" 2>/dev/null || exit 0
  gd=$(git rev-parse --git-dir 2>/dev/null) || exit 0
  : > "$gd/claude-review.pending"
fi
exit 0
