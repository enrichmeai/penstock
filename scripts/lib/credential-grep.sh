#!/bin/sh
# credential-grep.sh <dir> — exits 2 and prints "path:line" (never the matched value) for
# every credential-shaped line found under <dir>; exits 0 silently when none are found.
#
# Factored out of promote-pattern.sh so scripts/test-promote-pattern.sh can exercise the
# credential-refusal rule directly against the static fixture in scripts/test/, without
# needing a real git range/issue to drive it through.
#
# Three passes, not one regex, because a candidate skeleton routinely includes whole Java
# source files, not just config snippets: a bare "key: value" rule broad enough to catch an
# unquoted YAML secret also fires on ordinary Java (`token == null`, `this.password =
# password;`, `private String token = "";`) — see penstock#77's PR body for the false
# positives this caught on application.yml/AgentProperties.java/SecurityConfig.java before
# this split existed.
#   1. the well-known prefixes, anywhere (case-sensitive — these are fixed-case formats);
#   2. a quoted literal of real length assigned via `:`/`=`, anywhere, case-insensitively
#      (catches `API_TOKEN = "..."` and `apiSecret = "..."` too, not just lowercase
#      `token`/`secret`) — without matching `= "";` or `== "x"`;
#   3. the original bare-value rule (patterns/stdio-json-rpc-agent/check.sh), case-
#      insensitively, but only in config/doc file types, where an unquoted literal is how a
#      real secret would appear; a `${VAR:default}` Spring property reference and a `{}` log
#      placeholder are treated as placeholders, same as `<placeholder>`.
set -eu

dir="${1:?usage: credential-grep.sh <dir>}"

report=$(mktemp)
trap 'rm -f "$report"' EXIT

grep -rEn '(sk-[A-Za-z0-9]{10,}|ghp_[A-Za-z0-9]{10,}|AKIA[0-9A-Z]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' \
    "$dir" >>"$report" 2>/dev/null || true
grep -rEni '(password|token|secret)[[:space:]]*[:=][[:space:]]*"[^"[:space:]]{6,}"' \
    "$dir" >>"$report" 2>/dev/null || true
grep -rEni --include='*.yml' --include='*.yaml' --include='*.properties' \
    --include='*.xml' --include='*.md' --include='*.json' --include='*.conf' \
    '(password|token|secret)[[:space:]]*[:=][[:space:]]*[^<$"{[:space:]]' \
    "$dir" >>"$report" 2>/dev/null || true

if [ -s "$report" ]; then
    cut -d: -f1,2 "$report" | sort -u
    exit 2
fi
exit 0
