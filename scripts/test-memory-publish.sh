#!/bin/sh
# test-memory-publish.sh — offline checks for memory-publish.sh, memory-grant.sh and
# memory-revoke.sh on both memory-root layouts (#91). No pod is needed: a fake `java` and `curl`
# on PATH record every call, so this proves the plan (which folder goes to which container), the
# exit codes and the grant paths; scripts/test-memory-pod.sh is the live check against Cistern.
set -u
cd "$(dirname "$0")/.."
status=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; status=1; }

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
real_java=$(command -v java)
# fake java: a call to the CLI jar is recorded (its argv after "-jar <jar>", one call per line)
# instead of run; any other java call (a pattern's own check.sh) goes to the real java; FAKE_EXIT_ON="<n>:<code>"
# makes call n exit with code (call numbers count only `sync`/`grant`/`revoke`/`pod` calls).
cat > "$T/bin/java" <<FAKE
#!/bin/sh
if [ "\$1" != -jar ] || [ "\$2" != "\$CISTERN_CLI_JAR" ]; then exec "$real_java" "\$@"; fi
FAKE
cat >> "$T/bin/java" <<'FAKE'
shift 2
n=$(( $(cat "$FAKE_DIR/n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE_DIR/n"
echo "$*" >> "$FAKE_DIR/calls"
for rule in ${FAKE_EXIT_ON:-}; do [ "${rule%%:*}" = "$n" ] && exit "${rule#*:}"; done
exit 0
FAKE
cat > "$T/bin/curl" <<'FAKE'
#!/bin/sh
echo "curl" >> "$FAKE_DIR/calls"; printf '201'
FAKE
chmod +x "$T/bin/java" "$T/bin/curl"
: > "$T/cli.jar"
run() { # $1 = label dir; rest = command. Fresh call log per run.
    d="$T/$1"; shift; mkdir -p "$d"; : > "$d/calls"; rm -f "$d/n"
    PATH="$T/bin:$PATH" FAKE_DIR="$d" CISTERN_CLI_JAR="$T/cli.jar" CISTERN_TOKEN=test-token \
        CISTERN_OWNER_WEBID='https://owner.example/profile/card#me' "$@" > "$d/out" 2>&1
}
calls() { grep -c "$2" "$T/$1/calls"; }

S=scripts/test/cards/sectioned/valid
run sect sh scripts/memory-publish.sh --root "$S"; rc=$?
if [ $rc -eq 0 ] && grep -qx "sync estate/facts /memory/estate/facts/ --base http://127.0.0.1:3737" "$T/sect/calls" \
   && grep -qx "sync projects/valuedocs/facts /memory/projects/valuedocs/facts/ --base http://127.0.0.1:3737" "$T/sect/calls" \
   && [ "$(calls sect '^sync')" -eq 2 ]; then pass "a sectioned root syncs each section's card folders to the same path under /memory/"
else fail "sectioned plan (exit $rc)"; cat "$T/sect/out" "$T/sect/calls"; fi

run flat sh scripts/memory-publish.sh; rc=$?
printf 'sync %s /memory/%s/ --base http://127.0.0.1:3737\n' requests requests references references patterns patterns > "$T/flat-expected"
if [ $rc -eq 0 ] && cmp -s "$T/flat-expected" "$T/flat/calls"; then
    pass "the flat default (this repository) makes exactly the calls it made before --root"
else fail "flat plan (exit $rc)"; cat "$T/flat/out"; fi

FAKE_EXIT_ON="2:5" run failing sh scripts/memory-publish.sh --root "$S"; rc=$?
if [ $rc -eq 5 ]; then pass "a failed sync stops the publish with its exit code"
else fail "a failed sync exited $rc, not 5"; cat "$T/failing/out"; fi

FAKE_EXIT_ON="1:2" run refused sh scripts/memory-publish.sh --root "$S"; rc=$?
if [ $rc -eq 0 ] && [ "$(calls refused '^pod create')" -eq 1 ] && [ "$(calls refused '^sync')" -eq 3 ]; then
    pass "an absent /memory/ is provisioned once, then the sync is retried"
else fail "REFUSED handling (exit $rc)"; cat "$T/refused/out" "$T/refused/calls"; fi

cp -R "$S" "$T/with space"
run spaced sh scripts/memory-publish.sh --root "$T/with space"; rc=$?
if [ $rc -eq 0 ] && [ "$(calls spaced '^sync')" -eq 2 ] && grep -q "^sync estate/facts /memory/estate/facts/" "$T/spaced/calls"; then
    pass "a root path with spaces survives the plan"
else fail "root with spaces (exit $rc)"; cat "$T/spaced/out" "$T/spaced/calls"; fi

run broken sh scripts/memory-publish.sh --root scripts/test/cards/sectioned/broken; rc=$?
if [ $rc -ne 0 ] && [ "$(calls broken '^sync')" -eq 0 ]; then pass "a root that fails check-cards is never published"
else fail "broken root (exit $rc, $(calls broken '^sync') syncs)"; fi

run noval sh scripts/memory-publish.sh --root; rc=$?
[ $rc -eq 64 ] && pass "--root without a value exits 64" || fail "--root without a value exited $rc"

run grantp sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice --project valuedocs; rc=$?
if [ $rc -eq 0 ] && grep -q "^grant https://r.example/#me --read /memory/projects/valuedocs/patterns/ " "$T/grantp/calls" \
   && ! grep -q "/memory/patterns/" "$T/grantp/calls"; then pass "a reviewer grant with --project reaches that project's patterns only"
else fail "grant --project (exit $rc)"; cat "$T/grantp/out" "$T/grantp/calls"; fi

run grantflat sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice; rc=$?
if [ $rc -eq 0 ] && grep -q "^grant https://r.example/#me --read /memory/patterns/ " "$T/grantflat/calls"; then
    pass "a reviewer grant without --project is unchanged (/memory/patterns/)"
else fail "grant without --project (exit $rc)"; cat "$T/grantflat/calls"; fi

run grantbad sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice --project ../estate; rc=$?
[ $rc -eq 64 ] && [ "$(calls grantbad '^grant')" -eq 0 ] && pass "an unknown --project is refused before any grant" || fail "grant --project ../estate exited $rc"

run grantdecl sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice --project oss-lib; rc=$?
[ $rc -eq 0 ] && grep -q "^grant https://r.example/#me --read /memory/projects/oss-lib/patterns/ " "$T/grantdecl/calls" \
    && pass "any project name a root can declare is granted (#112)" || fail "grant --project oss-lib exited $rc"
run grantestate sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice --project estate; rc=$?
[ $rc -eq 64 ] && [ "$(calls grantestate '^grant')" -eq 0 ] && pass "--project estate is refused (the estate is not a project)" || fail "grant --project estate exited $rc"

run grantagent sh scripts/memory-grant.sh agent https://a.example/#me --project valuedocs; rc=$?
[ $rc -eq 64 ] && pass "--project on the agent form is refused" || fail "agent --project exited $rc"

run revokep sh scripts/memory-revoke.sh https://r.example/#me --as alice --project valuedocs; rc=$?
if [ $rc -eq 0 ] && grep -q "^revoke https://r.example/#me /memory/projects/valuedocs/patterns/ " "$T/revokep/calls"; then
    pass "revoke --project undoes the project grant"
else fail "revoke --project (exit $rc)"; cat "$T/revokep/out" "$T/revokep/calls"; fi

mkdir -p "$T/caller"; cp -R "$S" "$T/sibling-root"
# ../sibling-root exists from $T/caller and nowhere near this repository, so only a root resolved
# from the caller's directory finds it.
run relroot sh -c "cd '$T/caller' && sh '$(pwd)/scripts/memory-publish.sh' --root ../sibling-root"; rc=$?
if [ $rc -eq 0 ] && [ "$(calls relroot '^sync')" -eq 2 ]; then pass "a relative --root is resolved from where the command runs"
else fail "relative --root from another directory (exit $rc)"; cat "$T/relroot/out"; fi

run passthrough sh scripts/memory-publish.sh --root "$S" --dry-run --delete; rc=$?
if [ $rc -eq 0 ] && [ "$(grep -c -- '--dry-run --delete$' "$T/passthrough/calls")" -eq 2 ]; then pass "--dry-run and --delete reach every sectioned sync"
else fail "flag passthrough (exit $rc)"; cat "$T/passthrough/calls"; fi

run unknownproj sh scripts/memory-publish.sh --root scripts/test/cards/sectioned/unknown-project; rc=$?
[ $rc -ne 0 ] && [ "$(calls unknownproj '^sync')" -eq 0 ] && pass "an unknown project folder stops the publish before any sync" || fail "unknown project (exit $rc)"

run emptyproj sh scripts/memory-publish.sh --root scripts/test/cards/sectioned/empty-projects; rc=$?
[ $rc -ne 0 ] && [ "$(calls emptyproj '^sync')" -eq 0 ] && pass "a sectioned root with nothing in it is not published" || fail "empty projects (exit $rc)"

cp -R "$S" "$T/stray"; mkdir -p "$T/stray/estate/notes"
run stray sh scripts/memory-publish.sh --root "$T/stray"; rc=$?
[ $rc -eq 0 ] && grep -q "WARN: estate/notes/ is not a card folder" "$T/stray/out" && ! grep -q "notes" "$T/stray/calls" \
    && pass "a folder that is not a card folder is reported and not published" || { fail "stray folder (exit $rc)"; cat "$T/stray/out"; }

run grantclient sh scripts/memory-grant.sh reviewer https://r.example/#me --as alice --project valuedocs --client https://c.example/app; rc=$?
[ $rc -eq 0 ] && grep -q "^grant https://r.example/#me --read /memory/projects/valuedocs/patterns/ --base .* --client https://c.example/app$" "$T/grantclient/calls" \
    && pass "--client still narrows a --project grant" || { fail "grant --project --client (exit $rc)"; cat "$T/grantclient/calls"; }

exit $status
