#!/bin/sh
# Exercises scripts/write-episode.sh's guards (issue #86) in a scratch git repository, offline:
# PATH is cut to /usr/bin:/bin so no `gh` is found and the draft is built from git alone.
#   1. a draft is produced from a commit's markers (Decided/Refused/Learned/Open), exit 0
#   2. a credential-shaped Learned: line is refused with exit 2, path and line only, nothing written
#   3. --out outside episodes/, a traversal, and a sub-folder are refused with exit 64
#   4. an existing episode is never overwritten without --replace (exit 3), and is with it
#   5. a bad slug, a bad project and a bad range are refused with exit 64
set -eu
cd "$(dirname "$0")/.."

status=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; status=1; }

repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
mkdir -p "$repo/scripts/lib" "$repo/episodes"
cp scripts/write-episode.sh "$repo/scripts/"
cp scripts/lib/write_episode_generate.py scripts/lib/credential-grep.sh "$repo/scripts/lib/"
git -C "$repo" init -q -b main
git -C "$repo" config user.email test@example.com
git -C "$repo" config user.name test
echo base >"$repo/base.txt"; git -C "$repo" add -A; git -C "$repo" commit -qm "base"
echo work >"$repo/work.txt"; git -C "$repo" add -A
git -C "$repo" commit -qm "work for #7" -m "Decided: keep it small -- the rules are under test
Refused: a second file -- one is enough
Learned: the fixture secret lives in FIXTURE_SECRET_NAME, by name only
Open: nothing yet"
echo more >"$repo/more.txt"; git -C "$repo" add -A
git -C "$repo" commit -qm "leak for #8" -m "Learned: the box password: hunter2abcdef1234"

run() { (cd "$repo" && PATH=/usr/bin:/bin sh scripts/write-episode.sh "$@"); }

echo "=== test-write-episode: a draft from markers, offline ==="
out=$(run --project penstock --issue 7 --range HEAD~2..HEAD~1 --date 2026-02-01 --slug markers --dry-run 2>/dev/null) || { fail "dry run exited non-zero"; out=""; }
ok=1
printf '%s' "$out" | grep -q '^id: 2026-02-01-markers$' || ok=0
printf '%s' "$out" | grep -q 'what: keep it small' || ok=0
printf '%s' "$out" | grep -q 'why: the rules are under test' || ok=0
printf '%s' "$out" | grep -q 'what: a second file' || ok=0
printf '%s' "$out" | grep -q 'FIXTURE_SECRET_NAME, by name only' || ok=0
printf '%s' "$out" | grep -q '^- nothing yet' || ok=0
[ "$ok" -eq 1 ] && pass "markers harvested into decided/refused/learned/open" || { fail "draft did not carry the markers"; printf '%s\n' "$out"; }

echo "=== test-write-episode: drafts carry memory format v1 (#98) ==="
out=$(run --project valuedocs --slug fmt --date 2026-02-06 --dry-run 2>/dev/null) || fail "format dry run failed"
printf '%s' "$out" | grep -q '^format: 1$' && pass "a draft carries format: 1" || { fail "no format: 1"; printf '%s\n' "$out" | head -5; }
printf '%s' "$out" | grep -q '^visibility: private$' && pass "with no memory.yaml (or another project) a draft defaults to private" || { fail "visibility not private"; printf '%s\n' "$out" | head -5; }
out=$(run --project penstock --slug fmt1 --date 2026-02-06 --dry-run 2>/dev/null)
printf '%s' "$out" | grep -q '^visibility: private$' && pass "with no memory.yaml even the repository's own project defaults to private" || fail "own project without memory.yaml not private"
printf 'format: 1\nproject: penstock\nvisibility: public\n' >"$repo/memory.yaml"
out=$(run --project penstock --slug fmt2 --date 2026-02-06 --dry-run 2>/dev/null)
printf '%s' "$out" | grep -q '^visibility: public$' && pass "an episode of the repository's own project takes the repository's visibility" || { fail "own-project visibility wrong"; printf '%s\n' "$out" | head -5; }
out=$(run --project valuedocs --slug fmt3 --date 2026-02-06 --dry-run 2>/dev/null)
printf '%s' "$out" | grep -q '^visibility: private$' && pass "another project's episode stays private in a public repository" || fail "other-project visibility not private"
printf 'visibility: [public\n' >"$repo/memory.yaml"
out=$(run --project penstock --slug fmt4 --date 2026-02-06 --dry-run 2>&1) && printf '%s' "$out" | grep -q '^visibility: private$' && ! printf '%s' "$out" | grep -q Traceback && pass "a malformed memory.yaml makes the draft private, never a crash" || { fail "malformed memory.yaml crashed or widened the draft"; printf '%s\n' "$out" | tail -3; }
rm -f "$repo/memory.yaml"

echo "=== test-write-episode: a credential-shaped Learned: line is refused ==="
before=$(ls "$repo/episodes" | wc -l)
# `if` so the expected failure does not trip set -e
if err=$(run --project penstock --issue 8 --range HEAD~1..HEAD --date 2026-02-02 --slug leak 2>&1 >/dev/null); then rc=0; else rc=$?; fi
after=$(ls "$repo/episodes" | wc -l)
if [ "$rc" -eq 2 ] && [ "$before" = "$after" ] && printf '%s' "$err" | grep -q 'episodes/2026-02-02-leak.yaml:[0-9]' && ! printf '%s' "$err" | grep -q 'hunter2'; then
    pass "refused with exit 2, path and line only, nothing written"
else
    fail "credential refusal: rc=$rc before=$before after=$after"; printf '%s\n' "$err" | sed 's/hunter2[a-z0-9]*/<redacted>/'
fi

echo "=== test-write-episode: --out is confined to episodes/ itself ==="
for o in /tmp/x.yaml episodes/../README.yaml episodes/sub/escape.yaml; do
    if run --project penstock --slug probe --date 2026-02-03 --out "$o" >/dev/null 2>&1; then
        fail "--out $o was accepted"
    else
        rc=$?; [ "$rc" -eq 64 ] && pass "--out $o refused with exit 64" || fail "--out $o exited $rc, expected 64"
    fi
done
[ -e "$repo/episodes/sub" ] && fail "a sub-folder was created" || true
[ -e "$repo/README.yaml" ] && fail "a traversal wrote outside episodes/" || true

echo "=== test-write-episode: never overwritten without --replace ==="
run --project penstock --slug twice --date 2026-02-04 >/dev/null 2>&1 && pass "first write exit 0" || fail "first write failed"
sum1=$(cksum "$repo/episodes/2026-02-04-twice.yaml")
if run --project penstock --slug twice --date 2026-02-04 >/dev/null 2>&1; then fail "second write did not refuse"; else
    rc=$?; [ "$rc" -eq 3 ] && pass "second write refused with exit 3" || fail "second write exited $rc, expected 3"; fi
[ "$sum1" = "$(cksum "$repo/episodes/2026-02-04-twice.yaml")" ] && pass "file untouched by the refused write" || fail "file changed"
run --project penstock --slug twice --date 2026-02-04 --replace >/dev/null 2>&1 && pass "--replace overwrites deliberately" || fail "--replace failed"

echo "=== test-write-episode: --audit fills patterns and recalled from the session's audit log (#88) ==="
cat >"$repo/audit.json" <<'JSON'
[{"timestamp":"2026-10-03T10:00:00Z","eventType":"fact.loaded","detail":{"factId":"gcp-console-account","status":"asserted","confidence":1.0}},
 {"timestamp":"2026-10-03T10:00:00Z","eventType":"episode.loaded","detail":{"episodeId":"2026-10-02-memory-1","date":"2026-10-02"}},
 {"timestamp":"2026-10-03T10:00:00Z","eventType":"pattern.loaded","detail":{"patternId":"stdio-json-rpc-agent","version":"1"}},
 {"timestamp":"2026-10-03T10:00:01Z","eventType":"pattern.loaded","detail":{"patternId":"stdio-json-rpc-agent","version":"1"}},
 {"timestamp":"2026-10-03T10:00:02Z","eventType":"llm_call","detail":{"provider":"stub"}}]
JSON
out=$(run --project penstock --slug recalled --date 2026-02-05 --audit "$repo/audit.json" --dry-run 2>/dev/null) || fail "--audit dry run failed"
printf '%s' "$out" | grep -q '^patterns:' && printf '%s' "$out" | grep -A1 '^patterns:' | grep -q 'stdio-json-rpc-agent' && pass "patterns filled from pattern.loaded" || { fail "patterns not filled"; printf '%s\n' "$out"; }
[ "$(printf '%s' "$out" | grep -c 'stdio-json-rpc-agent')" = "1" ] && pass "a pattern loaded twice is listed once" || fail "pattern duplicated"
printf '%s' "$out" | grep -q 'fact:gcp-console-account' && printf '%s' "$out" | grep -q 'episode:2026-10-02-memory-1' && pass "recalled lists the fact and the episode" || { fail "recalled missing"; printf '%s\n' "$out"; }
printf '%s' "$out" | grep -A1 '^learned:' | grep -q '^learned: \[\]' && pass "learned stays empty (a fact recalled is not a fact learned)" || { fail "learned was seeded"; printf '%s\n' "$out" | grep -A2 '^learned'; }
printf '{"not":"a list"}' >"$repo/audit-obj.json"
if err=$(run --project penstock --slug objaudit --date 2026-02-05 --audit "$repo/audit-obj.json" --dry-run 2>&1 >/dev/null); then
    printf '%s' "$err" | grep -q "not a JSON list" && pass "an object-shaped audit file is ignored with a message" || fail "no 'not a JSON list' message: $err"
else fail "object-shaped audit file made the draft fail"; fi
if (cd "$repo" && sh scripts/write-episode.sh --project penstock --slug x --audit "$repo/audit.json" --session abc >/dev/null 2>&1); then fail "--session with --audit accepted"; else [ $? -eq 64 ] && pass "--session with --audit refused with exit 64" || fail "wrong exit for --session with --audit"; fi
if (cd "$repo" && env -u AGENT_AUTH_PASSWORD sh scripts/write-episode.sh --project penstock --slug x --session abc >/dev/null 2>&1); then fail "--session without a password accepted"; else [ $? -eq 64 ] && pass "--session without AGENT_AUTH_PASSWORD refused with exit 64" || fail "wrong exit for --session without password"; fi

echo "=== test-write-episode: bad slug, project and range are refused ==="
for args in "--project penstock --slug aB --dry-run" "--project penstock --slug a..b --dry-run" "--project penstock --slug -x --dry-run" "--project nowhere --slug ok --dry-run" "--project penstock --range notarange --dry-run"; do
    # shellcheck disable=SC2086
    if run $args >/dev/null 2>&1; then fail "'$args' was accepted"; else
        rc=$?; [ "$rc" -eq 64 ] && pass "'$args' refused with exit 64" || fail "'$args' exited $rc, expected 64"; fi
done

exit $status
