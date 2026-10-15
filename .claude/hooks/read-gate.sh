#!/usr/bin/env bash
# PreToolUse(Read|Bash): refuse a whole read of a large text file, and say how to read it instead.
#
# A file read into the session stays in the context and is re-sent on every later turn, so one
# 1,400-line read costs for the rest of the session. A slice (Read with offset/limit), a search
# (grep -n, then a slice) or a bounded command (head, sed -n 'a,bp') reads what the task needs.
# The same rule written in CLAUDE.md is advisory; a hook is applied every time.
#
# Denied: Read with neither offset nor limit, and a bare `cat <file>` (nothing piped or redirected),
# of a text file over CLAUDE_READ_GATE_LINES lines (default 350). Everything else passes: a slice,
# a small file, a binary (images and PDFs are read differently), a missing file, `cat x | grep`.
# Turn it off for one session with CLAUDE_SKIP_READ_GATE=1. The `cat` match is best-effort (one file,
# nothing after it); a form it does not recognise passes, so it never blocks wrongly.
set -uo pipefail

[ "${CLAUDE_SKIP_READ_GATE:-}" = 1 ] && exit 0
limit=${CLAUDE_READ_GATE_LINES:-350}

input=$(cat)
tool=$(jq -r '.tool_name // empty' <<<"$input" 2>/dev/null)
cwd=$(jq -r '.cwd // empty' <<<"$input" 2>/dev/null); [ -d "$cwd" ] || cwd=$PWD

file=""; how=""
case "$tool" in
  Read)
    # A slice (offset or limit) is the point: let it through whatever the file's size.
    [ "$(jq -r '(.tool_input.offset // .tool_input.limit // empty) | tostring' <<<"$input")" != "" ] && exit 0
    file=$(jq -r '.tool_input.file_path // empty' <<<"$input")
    how='Read it with offset and limit, or find the part you need first with grep -n'
    ;;
  Bash)
    cmd=$(jq -r '.tool_input.command // empty' <<<"$input")
    # Only a bare `cat [-flags] <one file>`: a pipe, a redirect or a second command means the
    # output is filtered or stored, not read into the context whole.
    re='^[[:space:]]*cat([[:space:]]+-[A-Za-z]+)*([[:space:]]+--)?[[:space:]]+("[^"]+"|'"'"'[^'"'"']+'"'"'|[^[:space:]|;&<>`$()]+)[[:space:]]*$'
    [[ "$cmd" =~ $re ]] || exit 0
    file=${BASH_REMATCH[3]}; file=${file#[\"\']}; file=${file%[\"\']}
    case "$file" in "~/"*) file="$HOME/${file#\~/}" ;; esac
    how="Use the Read tool with offset and limit, sed -n 'a,bp', or grep -n first"
    ;;
  *) exit 0 ;;
esac

[ -n "$file" ] || exit 0
case "$file" in /*) path=$file ;; *) path="$cwd/$file" ;; esac
[ -f "$path" ] || exit 0
grep -Iq . "$path" 2>/dev/null || exit 0          # binary or empty: not this gate's business
lines=$(grep -c '' "$path")                    # counts a last line with no newline too
[ "$lines" -gt "$limit" ] || exit 0

jq -n --arg r "Read gate: $file has $lines lines (limit $limit for a whole read). $how; a whole read stays in the context for the rest of the session. (.claude/hooks/read-gate.sh; CLAUDE_SKIP_READ_GATE=1 turns it off)" '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    permissionDecision: "deny",
    permissionDecisionReason: $r
  }
}'
exit 0
