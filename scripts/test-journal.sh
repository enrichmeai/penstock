#!/bin/sh
# Exercises scripts/journal.sh (issue #90) against scratch memory roots:
#   1. --since filters the timeline: an episode before the window is left out
#   2. --project filters to one project
#   3. an open loop a later episode closes (same text in decided/built/open, or a closes: marker)
#      disappears; one nobody closed stays, oldest first, with the episode that opened it
#   4. a supersede pair in the window renders as "old → new", and a superseded fact is never
#      shown as current
#   5. a root with no facts/ folder prints the one-line notice for that section, exit 0
#   6. a sectioned root (estate/ plus projects/<name>/) is read section by section
#   7. --json emits the same content as one object
#   8. usage errors exit 64
#   9. an open loop older than --since still shows, and loops list oldest first
#  10. a loop a later episode repeats under open is listed once, with the episode that opened it
#  11. a closes: marker in an EARLIER episode, or a why that matches, does not close a loop
#  12. a scalar where a list belongs is one entry, an episode with no valid date is reported and
#      skipped, and neither is a traceback
#  13. a project filter keeps beliefs to that project's facts and the estate's
set -eu
cd "$(dirname "$0")/.."
status=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; status=1; }
J="$PWD/scripts/journal.sh"
[ -f "$J" ] || { echo "FAIL: scripts/journal.sh does not exist"; exit 1; }

flat=$(mktemp -d); sect=$(mktemp -d); bare=$(mktemp -d); odd=$(mktemp -d); trap 'rm -rf "$flat" "$sect" "$bare" "$odd"' EXIT
episode() { # $1 dir $2 date $3 slug $4 project; the body on stdin is appended
    { printf 'id: %s-%s\nformat: 1\nvisibility: public\ndate: %s\nproject: %s\n' "$2" "$3" "$2" "$4"; cat; } >"$1/$2-$3.yaml"
}
mkdir -p "$flat/episodes" "$flat/facts"
episode "$flat/episodes" 2026-09-01 old-work penstock <<'YAML'
asked: something from before the window
decided: [{what: an old decision, why: long ago}]
open: [an old loop nobody closed]
YAML
episode "$flat/episodes" 2026-10-02 start-work penstock <<'YAML'
asked: start the journal
built: [{pr: enrichmeai/penstock#901, merged: abc1234}]
decided: [{what: plain text output, why: pastes into an issue}]
refused: [{what: a web dashboard, why: a file the owner reads is enough}]
open: [run the journal on a real week, decide the closure rule]
YAML
episode "$flat/episodes" 2026-10-05 finish-work penstock <<'YAML'
asked: finish the journal
decided: [{what: decide the closure rule, why: same text closes a loop}]
open: [one loop still open]
YAML
episode "$flat/episodes" 2026-10-06 other-project cistern <<'YAML'
asked: a cistern change
closes: [2026-10-02-start-work#1]
YAML
episode "$flat/episodes" 2026-10-07 carry-on penstock <<'YAML'
asked: carry a loop forward
open: [one loop still open]
YAML
cat >"$flat/facts/old-port.yaml" <<'YAML'
id: old-port
statement: the demo db listens on 5432
subject: demo-db-port
status: superseded
superseded_by: new-port
first_seen: 2026-09-01
last_confirmed: 2026-09-01
YAML
cat >"$flat/facts/new-port.yaml" <<'YAML'
id: new-port
statement: the demo db listens on 6543
subject: demo-db-port
status: asserted
supersedes: old-port
first_seen: 2026-10-03
last_confirmed: 2026-10-03
YAML
cat >"$flat/facts/cistern-only.yaml" <<'YAML'
id: cistern-only
statement: a belief only cistern holds
subject: cistern-thing
scope: project:cistern
status: asserted
first_seen: 2026-10-04
last_confirmed: 2026-10-04
YAML

out=$(sh "$J" --root "$flat" --since 2026-10-01 2>&1) || { fail "journal exits non-zero on a valid root: $out"; out=""; }
case "$out" in *2026-10-02*start\ the\ journal*) pass "1 the timeline lists an episode in the window" ;; *) fail "1 missing the in-window episode: $out" ;; esac
case "$out" in *"something from before the window"*) fail "1 an episode before --since was listed" ;; *) pass "1 an episode before --since is left out" ;; esac
case "$out" in *"#901"*abc1234*|*abc1234*"#901"*) pass "1 built collapses to the PR and merged sha" ;; *) fail "1 built not shown as PR and sha: $out" ;; esac

pout=$(sh "$J" --root "$flat" --since 2026-10-01 penstock 2>&1)
case "$pout" in *"a cistern change"*) fail "2 --project penstock listed a cistern episode" ;; *) pass "2 a project filter keeps to that project" ;; esac

loops=$(printf '%s\n' "$out" | sed -n '/^## Open loops/,/^## /p')
case "$loops" in *"decide the closure rule"*) fail "3 a loop closed by a later decided line is still listed" ;; *) pass "3 a loop closed by a later episode's text disappears" ;; esac
case "$loops" in *"run the journal on a real week"*) fail "3 a loop closed by a closes: marker is still listed" ;; *) pass "3 a loop closed by a closes: marker disappears" ;; esac
case "$loops" in *"one loop still open"*"2026-10-05-finish-work"*) pass "3 an unclosed loop stays, with the episode that opened it" ;; *) fail "3 the unclosed loop is missing or unattributed: $loops" ;; esac

beliefs=$(printf '%s\n' "$out" | sed -n '/^## Beliefs changed/,/^## /p')
case "$beliefs" in *"5432"*"→"*"6543"*) pass "4 a supersede pair renders as old → new" ;; *) fail "4 no old → new pair: $beliefs" ;; esac
if printf '%s\n' "$out" | grep -v '→' | grep -q 'listens on 5432'; then fail "4 the superseded fact is shown as current"; else pass "4 a superseded fact is never shown as current"; fi

mkdir -p "$bare/episodes"
episode "$bare/episodes" 2026-10-02 only penstock <<'YAML'
asked: no facts here
YAML
bout=$(sh "$J" --root "$bare" --since 2026-10-01 2>&1); bcode=$?
[ "$bcode" -eq 0 ] && pass "5 a root without facts/ still exits 0" || fail "5 exit $bcode without facts/"
case "$bout" in *"Beliefs changed"*"no facts/"*) pass "5 the beliefs section is one line saying there is no facts/ folder" ;; *) fail "5 missing the one-line notice: $bout" ;; esac

mkdir -p "$sect/estate/episodes" "$sect/projects/oss-lib/episodes"
printf 'format: 1\nvisibility: private\nprojects:\n  oss-lib: { audience: public }\n' >"$sect/memory.yaml"
episode "$sect/estate/episodes" 2026-10-02 owner-wide estate <<'YAML'
asked: an owner-wide piece of work
YAML
episode "$sect/projects/oss-lib/episodes" 2026-10-03 lib-work oss-lib <<'YAML'
asked: a library change
open: [publish the library]
YAML
mkdir -p "$sect/estate/facts"
printf 'id: owner-wide\nstatement: an owner-wide belief\nsubject: owner-thing\nscope: estate\nstatus: asserted\nfirst_seen: 2026-10-02\nlast_confirmed: 2026-10-02\n' >"$sect/estate/facts/owner-wide.yaml"
sout=$(sh "$J" --root "$sect" --since 2026-10-01 2>&1)
case "$sout" in *"an owner-wide piece of work"*"a library change"*|*"a library change"*"an owner-wide piece of work"*) pass "6 a sectioned root is read in every section" ;; *) fail "6 sectioned root not read: $sout" ;; esac
soss=$(sh "$J" --root "$sect" --since 2026-10-01 oss-lib 2>&1)
case "$sout" in *"## Open loops"*"publish the library"*) pass "6 a sectioned root's open loops are listed" ;; *) fail "6 sectioned open loop missing: $sout" ;; esac
case "$soss" in *"Beliefs changed"*"an owner-wide belief"*) pass "13 a project filter on a sectioned root keeps the estate's beliefs" ;; *) fail "13 the estate belief is missing under oss-lib: $soss" ;; esac
case "$soss" in *"owner-wide piece"*) fail "6 a project filter on a sectioned root listed the estate" ;; *"a library change"*) pass "6 a project filter on a sectioned root keeps to its section" ;; *) fail "6 project filter lost the project: $soss" ;; esac

jout=$(sh "$J" --root "$flat" --since 2026-10-01 --json 2>&1)
if printf '%s' "$jout" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert {"timeline","decided","beliefs","open"} <= set(d), d.keys(); assert any(e["id"]=="2026-10-02-start-work" for e in d["timeline"]); assert all(b["new"]!="old-port" for b in d["beliefs"]), d["beliefs"]; assert any(b["change"]=="superseded" and b["old"]=="old-port" for b in d["beliefs"]), d["beliefs"]; assert [l["text"] for l in d["open"]]==["an old loop nobody closed","one loop still open"], d["open"]' 2>/dev/null; then pass "7 --json carries the same sections"; else fail "7 --json is not the same content: $jout"; fi

case "$loops" in *"an old loop nobody closed"*"2026-09-01-old-work"*"one loop still open"*) pass "9 a loop older than --since still shows, oldest first" ;; *) fail "9 the old loop is missing or out of order: $loops" ;; esac
n=$(printf '%s\n' "$loops" | grep -c 'one loop still open' || true)
case "$n:$loops" in 1:*"one loop still open"*"2026-10-05-finish-work"*) pass "10 a carried-forward loop is listed once, with its opener" ;; *) fail "10 carried loop listed $n times or misattributed: $loops" ;; esac
case "$pout" in *"a belief only cistern holds"*) fail "13 a project filter showed another project's belief" ;; *"6543"*) pass "13 a project filter keeps beliefs to that project and the estate" ;; *) fail "13 the filter lost the project's own beliefs: $pout" ;; esac

mkdir -p "$odd/episodes"
episode "$odd/episodes" 2026-10-01 early-marker penstock <<'YAML'
asked: a marker that points forward
closes: [2026-10-03-late-loop#1]
YAML
episode "$odd/episodes" 2026-10-03 late-loop penstock <<'YAML'
asked: open a loop
open: [the late loop]
YAML
episode "$odd/episodes" 2026-10-04 scalars penstock <<'YAML'
asked: fields written as scalars
built: 5
decided: just a string
open: a scalar loop
YAML
episode "$odd/episodes" 2026-10-05 why-match penstock <<'YAML'
asked: a why that names the loop
decided: [{what: something else, why: the late loop}]
YAML
printf 'id: no-date\nformat: 1\nvisibility: public\ndate: someday\nproject: penstock\nasked: undated\n' >"$odd/episodes/no-date.yaml"
ocode=0; oout=$(sh "$J" --root "$odd" --since 2026-10-01 2>&1) || ocode=$?
oloops=$(printf '%s\n' "$oout" | sed -n '/^## Open loops/,/^## /p')
case "$oloops" in *"the late loop"*) pass "11 an earlier marker or a matching why does not close a loop" ;; *) fail "11 the late loop was closed: $oloops" ;; esac
case "$ocode:$oout" in *Traceback*) fail "12 a scalar field crashed the journal: $oout" ;; 0:*"a scalar loop"*) pass "12 a scalar open line is one loop" ;; *) fail "12 exit $ocode or scalar loop missing: $oout" ;; esac
if printf '%s\n' "$oloops" | grep -qx -- '- [a-z]  .*'; then fail "12 a scalar was split into letters: $oloops"; else pass "12 a scalar is never split into letters"; fi
case "$oout" in *"decided: just a string"*) pass "12 a scalar decided is one entry" ;; *) fail "12 scalar decided missing: $oout" ;; esac
case "$oout" in *no-date.yaml*) pass "12 an episode without a valid date is reported" ;; *) fail "12 the undated episode was dropped silently: $oout" ;; esac

set +e
sh "$J" --root "$flat" --since not-a-date >/dev/null 2>&1; c1=$?
sh "$J" --root /nonexistent/x >/dev/null 2>&1; c2=$?
sh "$J" --bogus >/dev/null 2>&1; c3=$?
set -e
[ "$c1" -eq 64 ] && [ "$c2" -eq 64 ] && [ "$c3" -eq 64 ] && pass "8 usage errors exit 64" || fail "8 usage exits were $c1 $c2 $c3, not 64"
exit $status
