#!/usr/bin/env bash
# Stop: before Claude ends a turn, run the fast gates for what this branch touches, and feed any
# failure back. The Gradle counterpart of the Maven hook in enrichmeai/cistern.
#   - Flyway: every migration this branch adds or changes under db/migration/sqlite has a sibling
#     with the same version under db/migration/postgres, and the reverse (CLAUDE.md: "any new
#     migration needs a sibling in both directories");
#   - Java: `./gradlew compileTestJava` (main + test sources, no tests run) when a *.java or
#     *.gradle file changed.
# Scoped on purpose: the full `./gradlew build` belongs in CI.
#   - change set = diff vs the merge-base with origin/main, plus uncommitted and untracked files;
#   - skipped when that change set is identical to the last one that passed;
#   - one retry only: if a stop was already blocked (stop_hook_active) and it still fails, the stop
#     is allowed with a warning to the owner, so a broken build can never loop forever.
#   - Review pending (issue #78): guard-task.sh leaves <git-dir>/claude-review.pending when
#     the reviewer agent is launched and clear-review.sh removes it when the Task returns; while
#     it is present the stop is blocked once, then allowed with a warning (same one-retry rule),
#     so a stuck marker can never loop forever. Checked before the other gates and even when
#     nothing changed.
# Opt out for one session: CLAUDE_SKIP_STOP_GATES=1 (CLAUDE_SKIP_STOP_COMPILE=1 still works).
set -uo pipefail

[ "${CLAUDE_SKIP_STOP_GATES:-}" = "1" ] && exit 0
[ "${CLAUDE_SKIP_STOP_COMPILE:-}" = "1" ] && exit 0
input=$(cat)
active=$(printf '%s' "$input" | jq -r '.stop_hook_active // false')

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" || exit 0

gitdir=$(git rev-parse --git-dir 2>/dev/null || true)
if [ -n "$gitdir" ] && [ -f "$gitdir/claude-review.pending" ]; then
  if [ "$active" = "true" ]; then
    rm -f "$gitdir/claude-review.pending"
    jq -n '{systemMessage: "Stop allowed, but the reviewer was started and never returned its verdict — treat this turn as BLOCKED and say so in the reply (CLAUDE.md § \"Autonomous build loop\")."}'
    exit 0
  fi
  jq -n '{decision: "block", reason: "The reviewer was started and has not returned its verdict; wait for it (or re-run it in the foreground, without run_in_background) before ending the turn."}'
  exit 0
fi

base=$(git merge-base HEAD origin/main 2>/dev/null || echo HEAD)
all_changed=$( { git diff --name-only "$base" 2>/dev/null; git ls-files --others --exclude-standard 2>/dev/null; } | sort -u)
[ -z "$all_changed" ] && exit 0

mig=src/main/resources/db/migration
java_changed=$(printf '%s\n' "$all_changed" | grep -E '\.java$|\.gradle(\.kts)?$' || true)
mig_changed=$(printf '%s\n' "$all_changed" | grep -E "^$mig/(sqlite|postgres)/V[^/]*\.sql$" || true)
[ -z "$java_changed$mig_changed" ] && exit 0

stamp_file="$(git rev-parse --git-dir)/claude-stop-check.stamp"
stamp=$( { for f in $java_changed $mig_changed; do echo "$f"; [ -f "$f" ] && cat "$f"; done; } | sha256sum | cut -d' ' -f1)
[ -f "$stamp_file" ] && [ "$(cat "$stamp_file")" = "$stamp" ] && exit 0

msg=""
notrun=""

for f in $mig_changed; do
  [ -f "$f" ] || continue   # a deleted migration is the reviewer's to question, not this check's
  vendor=${f#"$mig"/}; vendor=${vendor%%/*}
  other=$([ "$vendor" = sqlite ] && echo postgres || echo sqlite)
  version=$(basename "$f" | sed -E 's/^(V[^_]+)__.*/\1/')
  if ! ls "$mig/$other/${version}__"*.sql >/dev/null 2>&1; then
    msg+="Flyway: $f has no sibling ${version}__*.sql in $mig/$other/ — add it (CLAUDE.md § Commands)."$'\n'
  fi
done

if [ -n "$java_changed" ]; then
  if [ ! -x ./gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    notrun="Java compile gate did NOT run (no gradle-wrapper.jar; run ./bootstrap.sh) — report it as not run."
  elif ! out=$(./gradlew -q --console=plain compileTestJava 2>&1); then
    if printf '%s' "$out" | grep -qE 'Could not resolve|Could not GET|Could not HEAD|Connection (refused|reset|timed out)|UnknownHostException|Unsupported class file major version'; then
      # The environment, not the code: say the gate did not run — never that it passed.
      notrun="Java compile gate did NOT run (dependency download failed, or the JDK is wrong: CLAUDE.md § Commands) — report it as not run."
    else
      msg+=$(printf 'compileTestJava failed:\n%s\n' "$(printf '%s' "$out" | grep -E -A3 'error:|What went wrong' | grep -v -E '^\* Try:|^--$' | head -60)")
    fi
  fi
fi

if [ -z "$msg" ]; then
  if [ -n "$notrun" ]; then jq -n --arg m "$notrun" '{systemMessage: $m}'; exit 0; fi
  echo "$stamp" > "$stamp_file"
  exit 0
fi
[ -n "$notrun" ] && msg+=$'\n'"$notrun"
if [ "$active" = "true" ]; then
  jq -n --arg m "$msg" '{systemMessage: ("Stop allowed, but the fast gates still fail — treat this turn as BLOCKED.\n" + $m)}'
  exit 0
fi
jq -n --arg m "$msg" '{decision: "block", reason: ($m + "\nFix these, or if this is attempt 3, stop and write the Blocker summary (CLAUDE.md § \"Autonomous build loop\").")}'
exit 0
