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
