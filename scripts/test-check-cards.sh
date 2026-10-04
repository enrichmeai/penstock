#!/bin/sh
# Exercises the staleness/null-tag rules added to scripts/check-cards.sh (issue #81), against
# throwaway git repos built from the fixtures under scripts/test/cards/ — never against this
# repo's own patterns/ or tags, so a bad fixture can't touch the real catalog.
#
# check-cards.sh resolves its own repo root as "$(dirname "$0")/.." — copying it into a fixture
# repo's scripts/ directory, rather than invoking the real one with an override flag, is what
# makes it run against the fixture tree instead of this one.
set -eu
cd "$(dirname "$0")/.."

status=0

make_fixture_repo() {
    repo="$1"
    mkdir -p "$repo/scripts/lib" "$repo/references" "$repo/requests"
    cp scripts/check-cards.sh "$repo/scripts/check-cards.sh"
    # the episode rules call this helper by its repo-relative path (#86)
    cp scripts/lib/credential-grep.sh "$repo/scripts/lib/credential-grep.sh"
    git -C "$repo" init -q -b main
    git -C "$repo" config user.email test@example.com
    git -C "$repo" config user.name test
}

commit_fixture() {
    repo="$1"
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "fixture commit"
}

# check-cards.sh's "latest release" is `git describe --tags --abbrev=0 origin/main` — point a
# fixture repo's own origin/main at its own main so that resolves without a real remote.
set_origin_main() {
    repo="$1"
    sha=$(git -C "$repo" rev-parse main)
    git -C "$repo" update-ref refs/remotes/origin/main "$sha"
}

echo "=== test-check-cards: null verified-against.tag fails ==="
repo=$(mktemp -d)
make_fixture_repo "$repo"
mkdir -p "$repo/patterns/null-tag-fixture"
cp scripts/test/cards/null-tag/manifest.yaml "$repo/patterns/null-tag-fixture/manifest.yaml"
cp scripts/test/cards/null-tag/check.sh "$repo/patterns/null-tag-fixture/check.sh"
commit_fixture "$repo"
git -C "$repo" tag v0.1.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    echo "FAIL: check-cards.sh exited 0 for a null verified-against.tag"
    cat "$out"
    status=1
elif grep -q "FAIL: null-tag-fixture has no verified-against.tag" "$out"; then
    echo "PASS: null tag failed with the expected message"
else
    echo "FAIL: expected FAIL message not found"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: older tag warns STALE, exits 0 ==="
repo=$(mktemp -d)
make_fixture_repo "$repo"
mkdir -p "$repo/patterns/stale-tag-fixture" "$repo/patterns/fresh-tag-fixture"
cp scripts/test/cards/stale-tag/manifest.yaml "$repo/patterns/stale-tag-fixture/manifest.yaml"
cp scripts/test/cards/stale-tag/check.sh "$repo/patterns/stale-tag-fixture/check.sh"
cp scripts/test/cards/fresh-tag/manifest.yaml "$repo/patterns/fresh-tag-fixture/manifest.yaml"
cp scripts/test/cards/fresh-tag/check.sh "$repo/patterns/fresh-tag-fixture/check.sh"
commit_fixture "$repo"
git -C "$repo" tag v0.1.0
git -C "$repo" commit -q --allow-empty -m "second release"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    ok=1
    grep -q "STALE: stale-tag-fixture verified against v0.1.0, latest release is v0.2.0" "$out" || ok=0
    if grep -q "STALE: fresh-tag-fixture" "$out"; then
        echo "FAIL: fresh-tag-fixture (verified at the latest tag) was flagged STALE"
        ok=0
    fi
    if [ "$ok" -eq 1 ]; then
        echo "PASS: the stale pattern warned by name, the fresh one did not, and the build stayed green"
    else
        cat "$out"
        status=1
    fi
else
    echo "FAIL: check-cards.sh exited non-zero for a STALE (warning-only) pattern"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: no tags skips the STALE rule visibly ==="
repo=$(mktemp -d)
make_fixture_repo "$repo"
mkdir -p "$repo/patterns/fresh-tag-fixture"
cp scripts/test/cards/fresh-tag/manifest.yaml "$repo/patterns/fresh-tag-fixture/manifest.yaml"
cp scripts/test/cards/fresh-tag/check.sh "$repo/patterns/fresh-tag-fixture/check.sh"
commit_fixture "$repo"
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    if grep -q "not run: no tags" "$out"; then
        echo "PASS: no tags handled visibly, build stayed green"
    else
        echo "FAIL: expected 'not run: no tags' message not found"
        cat "$out"
        status=1
    fi
else
    echo "FAIL: check-cards.sh exited non-zero with no tags present"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: a manifest that fails staleness parsing is reported, not a crash ==="
repo=$(mktemp -d)
make_fixture_repo "$repo"
# "zz-" so this sorts alphabetically AFTER malformed-fixture: the point of this case is that
# the loop keeps checking patterns after the one that failed to parse, not before it.
mkdir -p "$repo/patterns/malformed-fixture" "$repo/patterns/zz-fresh-tag-fixture"
cp scripts/test/cards/malformed/manifest.yaml "$repo/patterns/malformed-fixture/manifest.yaml"
cp scripts/test/cards/malformed/check.sh "$repo/patterns/malformed-fixture/check.sh"
cp scripts/test/cards/fresh-tag/manifest.yaml "$repo/patterns/zz-fresh-tag-fixture/manifest.yaml"
cp scripts/test/cards/fresh-tag/check.sh "$repo/patterns/zz-fresh-tag-fixture/check.sh"
commit_fixture "$repo"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    echo "FAIL: check-cards.sh exited 0 despite the unreadable manifest"
    cat "$out"
    status=1
else
    ok=1
    grep -q "FAIL: patterns/malformed-fixture/manifest.yaml could not be read for staleness checking" "$out" || ok=0
    # zz-fresh-tag-fixture's own id is "fresh-tag-fixture" (from the copied manifest) and is
    # verified at the latest tag, so it must still be checked (proving the loop didn't abort)
    # and must not be flagged STALE.
    grep -q "STALE: fresh-tag-fixture" "$out" && ok=0
    if [ "$ok" -eq 1 ]; then
        echo "PASS: the unreadable manifest was reported by name, and the pattern after it was still checked (no abort)"
    else
        echo "FAIL: expected reporting did not match"
        cat "$out"
        status=1
    fi
fi
rm -rf "$repo" "$out"

# --- episodes (issue #86) ---------------------------------------------------------------
# Each fixture repo gets the fresh-tag pattern too, so the pattern rules stay green and the
# only thing that can fail is the episode rule under test.
episode_repo() {
    repo=$(mktemp -d)
    make_fixture_repo "$repo"
    mkdir -p "$repo/patterns/fresh-tag-fixture" "$repo/episodes"
    cp scripts/test/cards/fresh-tag/manifest.yaml "$repo/patterns/fresh-tag-fixture/manifest.yaml"
    cp scripts/test/cards/fresh-tag/check.sh "$repo/patterns/fresh-tag-fixture/check.sh"
    echo "$repo"
}

echo "=== test-check-cards: a valid episode passes ==="
repo=$(episode_repo)
cp scripts/test/cards/episodes/valid/2026-01-15-valid-fixture.yaml "$repo/episodes/"
commit_fixture "$repo"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1 && grep -q "PASS: episodes/2026-01-15-valid-fixture.yaml" "$out"; then
    echo "PASS: the valid episode was checked and passed"
else
    echo "FAIL: the valid episode did not pass, or was not checked at all"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: an asked line carrying a credential value fails (#88: asked reaches the prompt) ==="
repo=$(episode_repo)
cp scripts/test/cards/episodes/asked-value/2026-01-17-asked-fixture.yaml "$repo/episodes/"
commit_fixture "$repo"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    echo "FAIL: check-cards.sh exited 0 for an episode whose asked line carries a token value"
    cat "$out"
    status=1
elif grep -q "FAIL: episodes/2026-01-17-asked-fixture.yaml has a credential-shaped value" "$out" \
     && ! grep -q "0123456789abcdef" "$out"; then
    echo "PASS: the credential-shaped asked line failed by path, and the value was not printed"
else
    echo "FAIL: expected FAIL message not found, or the value leaked into the output"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: a learned line carrying a credential value fails ==="
repo=$(episode_repo)
cp scripts/test/cards/episodes/token-value/2026-01-16-token-fixture.yaml "$repo/episodes/"
commit_fixture "$repo"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    echo "FAIL: check-cards.sh exited 0 for an episode carrying a token value"
    cat "$out"
    status=1
elif grep -q "FAIL: episodes/2026-01-16-token-fixture.yaml has a credential-shaped value" "$out" \
     && ! grep -q "0123456789abcdef" "$out"; then
    echo "PASS: the credential-shaped line failed by path, and the value was not printed"
else
    echo "FAIL: expected FAIL message not found, or the value leaked into the output"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: an episode whose date disagrees with its filename fails ==="
repo=$(episode_repo)
cp scripts/test/cards/episodes/mismatched-date/2026-01-17-mismatch-fixture.yaml "$repo/episodes/"
commit_fixture "$repo"
git -C "$repo" tag v0.2.0
set_origin_main "$repo"
out=$(mktemp)
if bash "$repo/scripts/check-cards.sh" >"$out" 2>&1; then
    echo "FAIL: check-cards.sh exited 0 for a mismatched episode date"
    cat "$out"
    status=1
elif grep -q "FAIL: episodes/2026-01-17-mismatch-fixture.yaml date 2026-01-18 does not match its filename" "$out"; then
    echo "PASS: the mismatched date failed with the expected message"
else
    echo "FAIL: expected FAIL message not found"
    cat "$out"
    status=1
fi
rm -rf "$repo" "$out"

# --- facts (issue #87) ------------------------------------------------------------------
fact_repo() {
    repo=$(episode_repo)
    mkdir -p "$repo/facts"
    echo "$repo"
}
run_facts() { # $1 repo, $2 out file -> exit code of check-cards.sh
    commit_fixture "$1"; git -C "$1" tag v0.2.0; set_origin_main "$1"
    bash "$1/scripts/check-cards.sh" >"$2" 2>&1
}

echo "=== test-check-cards: a valid fact passes ==="
repo=$(fact_repo); cp scripts/test/cards/facts/valid/*.yaml "$repo/facts/"; out=$(mktemp)
if run_facts "$repo" "$out" && grep -q "PASS: facts/console-account-fixture.yaml" "$out"; then
    echo "PASS: the valid fact was checked and passed"
else
    echo "FAIL: the valid fact did not pass, or was not checked at all"; cat "$out"; status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: two active facts on one subject fail ==="
repo=$(fact_repo); cp scripts/test/cards/facts/collide/*.yaml "$repo/facts/"; out=$(mktemp)
if run_facts "$repo" "$out"; then
    echo "FAIL: check-cards.sh exited 0 with two active facts on one subject"; cat "$out"; status=1
elif grep -q "FAIL: facts/console-account-fixture-b.yaml subject 'fixture-console/fixture-proj' is also active in facts/console-account-fixture-a.yaml" "$out"; then
    echo "PASS: the collision failed with the expected message"
else
    echo "FAIL: expected collision message not found"; cat "$out"; status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: a one-sided supersede fails ==="
repo=$(fact_repo); cp scripts/test/cards/facts/one-sided/*.yaml "$repo/facts/"; out=$(mktemp)
if run_facts "$repo" "$out"; then
    echo "FAIL: check-cards.sh exited 0 for a one-sided supersede"; cat "$out"; status=1
elif grep -q "FAIL: facts/old-fixture.yaml is superseded_by new-fixture, but new-fixture does not name it under supersedes" "$out"; then
    echo "PASS: the one-sided supersede failed with the expected message"
else
    echo "FAIL: expected supersede message not found"; cat "$out"; status=1
fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: a statement carrying a credential value fails ==="
repo=$(fact_repo); cp scripts/test/cards/facts/token-value/*.yaml "$repo/facts/"; out=$(mktemp)
if run_facts "$repo" "$out"; then
    echo "FAIL: check-cards.sh exited 0 for a fact carrying a token value"; cat "$out"; status=1
elif grep -q "FAIL: facts/pod-token-fixture.yaml has a credential-shaped value" "$out" && ! grep -q "0123456789abcdef" "$out"; then
    echo "PASS: the credential-shaped statement failed by path, and the value was not printed"
else
    echo "FAIL: expected FAIL message not found, or the value leaked"; cat "$out"; status=1
fi
rm -rf "$repo" "$out"

# --- memory format v1 (#98): every card validated against schema/, visibility vs the repo ---
format_repo() {
    repo=$(fact_repo)
    mkdir -p "$repo/schema" "$repo/scripts/lib"
    cp schema/*.schema.json "$repo/schema/"
    cp scripts/lib/memory_format.py "$repo/scripts/lib/"
    printf 'format: 1\nproject: penstock\nvisibility: public\n' >"$repo/memory.yaml"
    # the shared fixture's pattern card predates the format; give it the two fields
    for m in "$repo"/patterns/*/manifest.yaml; do
        [ -f "$m" ] && sed -i '/^id:/a format: 1\nvisibility: public' "$m"
    done
    echo "$repo"
}
format_case() { # $1 fixture dir, $2 expected exit (0|1), $3 grep pattern, $4 label
    repo=$(format_repo); cp -r "scripts/test/cards/format/$1/." "$repo/"; out=$(mktemp)
    if run_facts "$repo" "$out"; then rc=0; else rc=1; fi
    if [ "$rc" = "$2" ] && grep -q -- "$3" "$out"; then echo "PASS: $4"
    else echo "FAIL: $4 (exit $rc, expected $2; pattern '$3')"; cat "$out"; status=1; fi
    rm -rf "$repo" "$out"
}
echo "=== test-check-cards: memory format v1 ==="
format_case valid 0 "PASS: facts/format-valid-fixture.yaml (fact, format 1, public)" "a valid format-1 card passes the schema"
format_case missing-field 1 "FAIL: facts/format-missing-fixture.yaml \$ is missing required 'kind'" "a card missing a required field fails by path"
format_case bad-visibility 1 "FAIL: facts/format-badvis-fixture.yaml \$.visibility must be one of" "an unknown visibility fails"
format_case unknown-major 1 "FAIL: facts/format-major-fixture.yaml is format 2; this checker knows format 1" "an unknown format major fails"
format_case private-in-public 1 "FAIL: facts/format-private-fixture.yaml is private but this repository is public" "a private card in a public repository fails (#91: private cards live in the private memory root)"
format_case episode-missing 1 "FAIL: episodes/2026-01-20-ep-missing.yaml \$ is missing required 'asked'" "an episode missing a required field fails its schema"
format_case episode-nested 1 "FAIL: episodes/2026-01-20-ep-nested.yaml \$.built\[0\] has 'size', which the format does not define" "an undefined field nested inside an episode fails"
format_case pattern-missing 1 "FAIL: patterns/fmt-pattern-fixture/manifest.yaml \$ is missing required 'triggers'" "a pattern manifest missing a required field fails its schema"
format_case reference-missing 1 "FAIL: references/fmt-reference-fixture.yaml \$ is missing required 'holds'" "a reference missing a required field fails its schema"
format_case request-missing 1 "FAIL: requests/fmt-request-fixture.yaml \$ is missing required 'done-when'" "a request missing a required field fails its schema"
format_case reference-unreachable 0 "PASS: references/fmt-unreach-fixture.yaml (reference, format 1, public)" "a reference in the documented unreachable state (no read) passes"
format_case reference-neither 1 "FAIL: references/fmt-neither-fixture.yaml needs read: (when the source was read) or unreachable: (why it could not be)" "a reference with neither read nor unreachable fails"
format_case format-true 1 "FAIL: facts/format-true-fixture.yaml" "format: true is not format 1"
format_case fact-wider 1 "FAIL: facts/fact-wider-fixture.yaml is public but cites episode 2026-01-21-private-src, which is private" "a fact wider than an episode it cites fails (the leak path)"
format_case stray-file 1 "FAIL: facts/stray.yml is under a memory folder but matches no card kind" "a YAML file in a memory folder that is no card kind fails"
repo=$(format_repo); printf 'format: 2\nproject: penstock\nvisibility: public\n' >"$repo/memory.yaml"; out=$(mktemp)
if run_facts "$repo" "$out"; then echo "FAIL: memory.yaml format 2 was accepted"; cat "$out"; status=1
elif grep -q "FAIL: memory.yaml is format 2" "$out"; then echo "PASS: memory.yaml of an unknown format fails"
else echo "FAIL: memory.yaml format 2 not reported as expected"; cat "$out"; status=1; fi
rm -rf "$repo" "$out"
repo=$(format_repo); printf 'visibility: [public\n' >"$repo/memory.yaml"; out=$(mktemp)
if run_facts "$repo" "$out"; then echo "FAIL: a malformed memory.yaml was accepted"; status=1
elif grep -q "FAIL: memory.yaml did not parse" "$out" && ! grep -q "Traceback" "$out"; then echo "PASS: a malformed memory.yaml fails by name, without a traceback"
else echo "FAIL: malformed memory.yaml not reported cleanly"; cat "$out"; status=1; fi
rm -rf "$repo" "$out"
if out=$(python3 scripts/lib/memory_format.py --keywords schema 2>&1); then echo "PASS: every schema keyword is one the validator implements"
else echo "FAIL: a schema uses a keyword the validator does not implement: $out"; status=1; fi
repo=$(format_repo); rm "$repo/schema/fact.schema.json"; cp -r scripts/test/cards/format/valid/. "$repo/"; out=$(mktemp)
if run_facts "$repo" "$out"; then echo "FAIL: a missing schema went unnoticed"; cat "$out"; status=1
elif grep -q "FAIL: schema/fact.schema.json is missing" "$out"; then echo "PASS: a repository with memory.yaml but a missing schema fails"
else echo "FAIL: missing schema not reported as expected"; cat "$out"; status=1; fi
rm -rf "$repo" "$out"

echo "=== test-check-cards: this repo's own seed pattern still passes ==="
out=$(mktemp)
if bash scripts/check-cards.sh >"$out" 2>&1; then
    echo "PASS: scripts/check-cards.sh passes against the real repo"
else
    echo "FAIL: scripts/check-cards.sh failed against the real repo"
    cat "$out"
    status=1
fi
rm -f "$out"

exit $status
