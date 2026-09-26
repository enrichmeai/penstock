#!/usr/bin/env bash
# PreToolUse(Bash): force a permission prompt for destructive or production-touching commands.
# Destruction, cloud and publishing. The same guard runs in enrichmeai/cistern, with Maven in
# place of Gradle.
#
# Why a hook as well as `ask` rules in settings.json: a Bash rule matches the command as written,
# so `Bash(git push *)` does not match `git -C . push --force` (docs: code.claude.com/docs/en/permissions,
# "what a Bash rule doesn't match"). This matches on the words anywhere in the command instead.
#
# It never denies and never allows: it only turns a would-be silent run into a prompt ("ask"). In a
# headless run (the GitHub Action) nobody can answer the prompt, so the command does not run.
set -uo pipefail

input=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<<"$input" 2>/dev/null)
[ -z "$cmd" ] && exit 0

reason=""
check() { # $1 = extended regex, $2 = reason
  if [ -z "$reason" ] && printf '%s' "$cmd" | grep -Eiq -- "$1"; then reason="$2"; fi
}

# A program name, optionally invoked by path (/usr/bin/gcloud) or through env/command/exec.
P='(^|[;&|(`[:space:]])(env[[:space:]]+|command[[:space:]]+|exec[[:space:]]+)?([^[:space:];&|]*/)?'

check 'git[^|;&]*[[:space:]]push[^|;&]*([[:space:]]--force|[[:space:]]-[a-z]*f[a-z]*([[:space:]]|$)|[[:space:]]\+[^[:space:]]+)' 'force push'
check 'git[^|;&]*[[:space:]]push[^|;&]*[[:space:]](origin[[:space:]]+)?([^[:space:]]*:)?(refs/heads/)?main([[:space:]]|$)' 'push to main'
check 'git[^|;&]*[[:space:]]push[^|;&]*(--delete|[[:space:]]:[^[:space:]]+|--tags|--mirror|--all)' 'push that deletes refs or pushes tags'
check 'git[^|;&]*[[:space:]](reset[[:space:]]+--hard|clean[[:space:]]+-[a-z]*f|branch[[:space:]]+-D|tag[[:space:]]+(-d|-f)|filter-branch|filter-repo|update-ref[[:space:]]+-d|checkout[[:space:]]+(--[[:space:]]+)?\.([[:space:]]|$)|restore[[:space:]]+(--[a-z]+[[:space:]]+)*\.([[:space:]]|$))' 'history or working-tree destruction'
check "${P}rm[[:space:]]+([^;&|]*[[:space:]])?(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)([[:space:]]|$)" 'recursive delete'
check "${P}terraform([[:space:]]+-[^[:space:]]+)*[[:space:]]+(apply|destroy|import|taint|untaint|state[[:space:]]+(rm|mv|push)|force-unlock)" 'terraform state change'
check "${P}gcloud[[:space:]]" 'gcloud (production project access)'
check "${P}(gsutil|bq|kubectl)[[:space:]]" 'direct cloud data/infra CLI'
check "${P}(psql|pg_restore|dropdb|liquibase)[[:space:]]" 'direct database client'
check '(drop[[:space:]]+(table|schema|database|view|index)|truncate[[:space:]]+(table[[:space:]]+)?[a-z_"]|delete[[:space:]]+from[[:space:]])' 'destructive SQL'
# Gradle tasks that run the app or publish (release.yml owns publishing).
check "(gradlew|${P}gradle)[^|;&]*([[:space:]:](bootRun|bootBuildImage|publish[a-zA-Z]*|flywayClean|flywayMigrate)([[:space:]]|$))" 'Gradle task that runs the app, a migration or a publish'
check "${P}docker[[:space:]]+(push|login|buildx[[:space:]]+build[^|;&]*--push)" 'container registry push'
# A v* tag starts release.yml; pushing one by name is a release.
check 'git[^|;&]*[[:space:]]push[^|;&]*[[:space:]](refs/tags/)?v[0-9][^[:space:]]*([[:space:]]|$)' 'push of a release tag'
check "${P}gh[[:space:]]+(release|repo[[:space:]]+(delete|edit|archive|rename)|workflow[[:space:]]+(run|enable|disable)|secret|variable|api[^|;&]*(-X|--method)[[:space:]=]*(DELETE|PUT|PATCH|POST))" 'GitHub admin, release or dispatch'
check "${P}pip3?[[:space:]]+install[^|;&]*(git\+|https?://|[[:space:]]-i[[:space:]]|--index-url|--extra-index-url)" 'pip install from a URL or another index'

# A push with no refspec pushes the current branch: when that is main, it is a push to main.
if [ -z "$reason" ] && printf '%s' "$cmd" | grep -Eq 'git[^|;&]*[[:space:]]push([[:space:]]|$)'; then
  dir=$(jq -r '.cwd // empty' <<<"$input"); [ -d "$dir" ] || dir=.
  if [ "$(git -C "$dir" rev-parse --abbrev-ref HEAD 2>/dev/null)" = "main" ]; then reason='push while on main'; fi
fi

[ -z "$reason" ] && exit 0

jq -n --arg r "Guard hook: $reason — needs the owner's approval (.claude/hooks/guard-destructive.sh)." '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    permissionDecision: "ask",
    permissionDecisionReason: $r
  }
}'
exit 0
