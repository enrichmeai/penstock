#!/bin/sh
# Exercises scripts/consolidate.sh and scripts/fact.sh (issue #87) in a scratch repository:
#   1. a learned line with no fact proposes `new`; --write creates an inferred fact that passes check-cards
#   2. the same line again proposes nothing (already cited)
#   3. a restating line proposes `confirm` and moves last_confirmed
#   4. a contradicting line proposes `supersede`; --write writes the pair both ways
#   5. a credential-shaped learned line is refused with exit 2, path and line only, nothing written
#   6. fact.sh matches on statement/subject/triggers, hides superseded without --all, exits 1 on no match
#   7. a short, non-hex credential value never reaches stdout or stderr, even as a derived filename
#   8. two uncited episodes on one subject before the first --write (a backlog) write cleanly
#   9. a learned item that is a {what, why} mapping is flattened, not repr()'d
#  10. a line the owner rejected in facts/rejected/lines.yaml is never proposed again, and
#      check-cards fails a rejection whose learned text is not in its episode
set -eu
cd "$(dirname "$0")/.."
status=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; status=1; }

repo=$(mktemp -d); trap 'rm -rf "$repo"' EXIT
mkdir -p "$repo/scripts/lib" "$repo/episodes" "$repo/facts" "$repo/references" "$repo/requests"
cp scripts/consolidate.sh scripts/fact.sh scripts/check-cards.sh "$repo/scripts/"
cp scripts/lib/consolidate_generate.py scripts/lib/credential-grep.sh "$repo/scripts/lib/"
git -C "$repo" init -q -b main; git -C "$repo" config user.email t@e; git -C "$repo" config user.name t
ep() { # $1 id-suffix $2 date $3 learned line
    cat >"$repo/episodes/$2-$1.yaml" <<YAML
id: $2-$1
date: $2
project: penstock
asked: fixture
built: []
decided: []
refused: []
learned:
  - "$3"
open: []
YAML
}
run() { (cd "$repo" && sh scripts/consolidate.sh "$@"); }

echo "=== test-facts: new ==="
ep first 2026-02-01 "the fixture console for project fixture-proj is used as owner@fixture.example"
out=$(run) || fail "dry run exited non-zero"
printf '%s' "$out" | grep -q '^new ' && pass "a new line proposes new" || { fail "expected a new proposal"; printf '%s\n' "$out"; }
run --write >/dev/null && pass "--write exit 0" || fail "--write failed"
n=$(ls "$repo/facts" | wc -l | tr -d ' '); [ "$n" = "1" ] && pass "one fact written" || fail "expected 1 fact, found $n"
f=$(ls "$repo/facts"/*.yaml); grep -q 'kind: account' "$f" && grep -q 'status: inferred' "$f" && pass "kind account, status inferred" || { fail "wrong kind/status"; cat "$f"; }
(cd "$repo" && git add -A && git commit -qm f && git update-ref refs/remotes/origin/main "$(git rev-parse HEAD)" && sh scripts/check-cards.sh >/dev/null 2>&1) && pass "the written fact passes check-cards" || fail "check-cards failed on the written fact"

echo "=== test-facts: already cited ==="
out=$(run); printf '%s' "$out" | grep -q 'nothing to propose' && pass "the cited line proposes nothing" || { fail "re-proposed a cited line"; printf '%s\n' "$out"; }

echo "=== test-facts: confirm ==="
ep second 2026-02-05 "the fixture console for project fixture-proj is used as owner@fixture.example"
out=$(run); printf '%s' "$out" | grep -q '^confirm ' && pass "a restating line proposes confirm" || { fail "expected confirm"; printf '%s\n' "$out"; }
run --write >/dev/null; grep -q 'last_confirmed: 2026-02-05' "$f" && pass "last_confirmed moved to the new episode" || { fail "last_confirmed did not move"; cat "$f"; }
[ "$(ls "$repo/facts" | wc -l | tr -d ' ')" = "1" ] && pass "confirm wrote no new file" || fail "confirm created a file"

echo "=== test-facts: supersede ==="
ep third 2026-02-08 "the fixture console for project fixture-proj is used as other@fixture.example"
out=$(run); printf '%s' "$out" | grep -q '^supersede ' && pass "a contradicting line proposes supersede" || { fail "expected supersede"; printf '%s\n' "$out"; }
run --write >/dev/null
[ "$(ls "$repo/facts" | wc -l | tr -d ' ')" = "2" ] && pass "supersede wrote the new file" || fail "expected 2 facts"
grep -q 'status: superseded' "$f" && grep -q 'superseded_by: ' "$f" && pass "old fact marked superseded with superseded_by" || { fail "old fact not updated"; cat "$f"; }
new=$(grep -l 'supersedes: ' "$repo"/facts/*.yaml | grep -v "$f" | head -1); grep -q 'other@fixture.example' "$new" && pass "new fact carries supersedes" || fail "new fact missing"
(cd "$repo" && git add -A && git commit -qm g && sh scripts/check-cards.sh >/dev/null 2>&1) && pass "the pair passes check-cards both ways" || { fail "check-cards failed on the supersede pair"; (cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep FAIL); }

echo "=== test-facts: credential refusal ==="
ep leak 2026-02-09 "the pod token: 0123456789abcdef0123456789abcdef"
before=$(ls "$repo/facts" | wc -l | tr -d ' ')
if err=$(run --write 2>&1 >/dev/null); then rc=0; else rc=$?; fi
after=$(ls "$repo/facts" | wc -l | tr -d ' ')
if [ "$rc" -eq 2 ] && [ "$before" = "$after" ] && printf '%s' "$err" | grep -q 'episodes/2026-02-09-leak.yaml: learned item 1' && ! printf '%s' "$err" | grep -qi '0123456789abcdef'; then
    pass "refused with exit 2, naming the episode item only, nothing written"
else
    fail "credential refusal: rc=$rc before=$before after=$after"; printf '%s\n' "$err" | sed 's/0123456789abcdef[0-9a-f]*/<redacted>/'
fi
rm -f "$repo/episodes/2026-02-09-leak.yaml"

echo "=== test-facts: short credential value ==="
ep leak2 2026-02-09 "the db password: hunter2secret"
before=$(ls "$repo/facts" | wc -l | tr -d ' ')
if all=$(run 2>&1); then rc=0; else rc=$?; fi
after=$(ls "$repo/facts" | wc -l | tr -d ' ')
if [ "$rc" -eq 2 ] && [ "$before" = "$after" ] && printf '%s' "$all" | grep -q 'episodes/2026-02-09-leak2.yaml: learned item 1' && ! printf '%s' "$all" | grep -qi 'hunter2secret'; then
    pass "a short value is refused before the dry run prints, naming the episode item only"
else
    fail "short value: rc=$rc before=$before after=$after"; printf '%s\n' "$all" | sed 's/hunter2secret/<redacted>/g'
fi
rm -f "$repo/episodes/2026-02-09-leak2.yaml"

echo "=== test-facts: backlog on one subject ==="
ep bl1 2026-02-11 "the fixture registry for project fixture-reg is used as reg@fixture.example"
ep bl2 2026-02-12 "the fixture registry for project fixture-reg is used as reg@fixture.example"
ep bl3 2026-02-13 "the fixture registry for project fixture-reg is used as reg2@fixture.example"
before=$(ls "$repo/facts" | wc -l | tr -d ' ')
if err=$(run --write 2>&1 >/dev/null); then
    after=$(ls "$repo/facts" | wc -l | tr -d ' ')
    [ $((after - before)) -eq 2 ] && pass "new + confirm + supersede in one run wrote the two files" || { fail "expected 2 new files, got $((after - before))"; }
    (cd "$repo" && git add -A && git commit -qm h && sh scripts/check-cards.sh >/dev/null 2>&1) && pass "the backlog result passes check-cards" || { fail "check-cards failed on the backlog result"; (cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep FAIL); }
else
    fail "--write on a backlog exited $?"; printf '%s\n' "$err" | tail -3
fi

echo "=== test-facts: mapping learned item ==="
cat >"$repo/episodes/2026-02-14-map.yaml" <<YAML
id: 2026-02-14-map
date: 2026-02-14
project: penstock
asked: fixture
built: []
decided: []
refused: []
learned:
  - what: the fixture queue drains nightly
    why: the fixture scheduler runs at 02:00
open: []
YAML
out=$(run) || fail "dry run with a mapping item exited non-zero"
printf '%s' "$out" | grep -q "fixture queue drains nightly; the fixture scheduler" && pass "a mapping item is flattened" || { fail "mapping item not flattened"; printf '%s\n' "$out" | grep -i queue; }
printf '%s' "$out" | grep -q "{'what'" && fail "mapping item was repr()'d" || pass "no repr() in the proposal"
rm -f "$repo/episodes/2026-02-14-map.yaml"

echo "=== test-facts: a rejected line is not proposed again ==="
ep rej 2026-02-15 "the fixture build took four minutes on that run"
out=$(run); printf '%s' "$out" | grep -q 'four minutes' && pass "before rejection the line is proposed" || { fail "line not proposed before rejection"; printf '%s\n' "$out"; }
mkdir -p "$repo/facts/rejected"
cat >"$repo/facts/rejected/lines.yaml" <<'YAML'
rejected:
  - episode: 2026-02-15-rej
    learned: the fixture build took four minutes on that run
    reason: an event, not a belief
    owner: 2026-02-16
YAML
out=$(run); printf '%s' "$out" | grep -q 'four minutes' && { fail "a rejected line was proposed again"; printf '%s\n' "$out"; } || pass "a rejected line is not proposed again"
(cd "$repo" && git add -A && git commit -qm r && sh scripts/check-cards.sh >/dev/null 2>&1) && pass "a valid rejection passes check-cards" || { fail "check-cards failed on a valid rejection"; (cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep FAIL); }
cat >>"$repo/facts/rejected/lines.yaml" <<'YAML'
  - episode: 2026-02-15-rej
    learned: a line that episode never said
    reason: drift
    owner: 2026-02-16
YAML
(cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep -q "facts/rejected/lines.yaml.*not a learned line") && pass "a rejection whose text is not in its episode fails check-cards" || fail "check-cards accepted a drifted rejection"
cat >"$repo/facts/rejected/lines.yaml" <<'YAML'
rejected:
  - episode: 2026-02-01-first
    learned: the fixture console for project fixture-proj is used as owner@fixture.example
    reason: contradicts a fact
    owner: 2026-02-16
YAML
(cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep -q "facts/rejected/lines.yaml.*both a fact and rejected") && pass "a line both cited by a fact and rejected fails check-cards" || { fail "check-cards accepted a line that is both a fact and rejected"; (cd "$repo" && sh scripts/check-cards.sh 2>&1 | grep -i rejected); }
rm -rf "$repo/facts/rejected" "$repo/episodes/2026-02-15-rej.yaml"

echo "=== test-facts: fact.sh ==="
(cd "$repo" && sh scripts/fact.sh fixture console >/dev/null 2>&1) && pass "fact.sh matches on words" || fail "fact.sh found nothing"
(cd "$repo" && sh scripts/fact.sh fixture console 2>/dev/null | grep -q 'other@fixture.example') && pass "fact.sh shows the active successor" || fail "fact.sh did not show the successor"
(cd "$repo" && sh scripts/fact.sh fixture console 2>/dev/null | grep -q 'owner@fixture.example') && fail "fact.sh showed a superseded fact without --all" || pass "fact.sh hides the superseded fact"
(cd "$repo" && sh scripts/fact.sh fixture console --all 2>/dev/null | grep -q 'superseded by') && pass "--all shows the superseded fact with its successor" || fail "--all did not show the superseded fact"
msg=$(cd "$repo" && sh scripts/fact.sh '*' 2>&1 >/dev/null || true)
[ "$msg" = "no fact matches: *" ] && pass "fact.sh does not glob its words" || fail "fact.sh globbed '*' against the cwd: $msg"
if (cd "$repo" && sh scripts/fact.sh nosuchword >/dev/null 2>&1); then fail "fact.sh exited 0 on no match"; else [ $? -eq 1 ] && pass "fact.sh exits 1 on no match" || fail "fact.sh wrong exit on no match"; fi

exit $status
