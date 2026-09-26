#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): the fast checks for the one file just edited, so an error
# reaches Claude on the edit that caused it rather than at the end of the task.
#
# Exit 2 + stderr = the output is fed back to Claude (docs: code.claude.com/docs/en/hooks).
# Only the edited file is judged: an error elsewhere in the tree is not this edit's, and blocking on
# it would stall every edit. Java is deliberately absent — compiling is too slow per edit; the Stop
# hook (stop-fast-gates.sh) runs test-compile for the touched Maven modules once per turn. Full
# suites and the conformance job run in CI.
set -uo pipefail

file=$(jq -r '.tool_input.file_path // empty')
[ -z "$file" ] || [ ! -f "$file" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(git -C "$(dirname "$file")" rev-parse --show-toplevel 2>/dev/null)}"
rel="${file#"$root"/}"
out=""
fail=0

case "$rel" in
  *.py)
    if ! o=$(python3 -m py_compile "$file" 2>&1); then out+=$'python syntax:\n'"$o"$'\n'; fail=1; fi
    ;;
  *.sh)
    if ! o=$(bash -n "$file" 2>&1); then out+=$'shell syntax (bash -n):\n'"$o"$'\n'; fail=1; fi
    ;;
  *.json)
    if ! o=$(jq empty "$file" 2>&1); then out+=$'invalid JSON:\n'"$o"$'\n'; fail=1; fi
    ;;
  *.yml|*.yaml)
    if python3 -c 'import yaml' 2>/dev/null; then
      if ! o=$(python3 -c 'import sys,yaml; list(yaml.safe_load_all(open(sys.argv[1])))' "$file" 2>&1); then
        out+=$'invalid YAML:\n'"$o"$'\n'; fail=1
      fi
    fi
    ;;
esac

if [ "$fail" -ne 0 ]; then
  printf '%s\nFix these in %s before moving on.\n' "$out" "$rel" | head -c 8000 >&2
  exit 2
fi
exit 0
