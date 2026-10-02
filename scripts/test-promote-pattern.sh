#!/bin/sh
# Verifies scripts/promote-pattern.sh against the one pattern this repo already hand-built
# (issue #77's own measure): regenerating patterns/stdio-json-rpc-agent/ in --dry-run from
# the range it actually shipped in should overlap the hand-built manifest by at least
# two-thirds on both triggers and signatures, and the generated check.sh must pass in a
# clean copy. Then, separately, the credential-refusal rule must still refuse the fixture
# under scripts/test/credential-fixture/ and never print the planted value.
set -eu
cd "$(dirname "$0")/.."

status=0

echo "=== test-promote-pattern: regenerate stdio-json-rpc-agent in --dry-run ==="
dry_run_output=$(mktemp)
trap 'rm -f "$dry_run_output"' EXIT
if bash scripts/promote-pattern.sh --id stdio-json-rpc-agent --range v0.2.0..v0.3.0 --issue 59 --dry-run \
    >"$dry_run_output" 2>&1; then
    echo "PASS: promote-pattern.sh --dry-run exited 0"
else
    echo "FAIL: promote-pattern.sh --dry-run exited non-zero"
    cat "$dry_run_output"
    status=1
fi

if ! grep -q "PASS: check.sh passed in the clean copy" "$dry_run_output"; then
    echo "FAIL: the generated check.sh did not pass in its clean-copy run"
    cat "$dry_run_output"
    status=1
else
    echo "PASS: the generated check.sh passed in its clean-copy run"
fi

echo "--- overlap against the hand-built seed ---"
if ! python3 - "$dry_run_output" <<'PYEOF'
import re
import sys
import fnmatch
import yaml

output = open(sys.argv[1], encoding="utf-8").read()
m = re.search(
    r"--- manifest\.yaml \(dry-run; nothing written\) ---\n(.*?)\n--- bar diagnostics ---",
    output,
    re.DOTALL,
)
if not m:
    print("FAIL: could not find the printed manifest.yaml block in promote-pattern.sh's output")
    sys.exit(1)

draft = yaml.safe_load(m.group(1)) or {}
seed = yaml.safe_load(open("patterns/stdio-json-rpc-agent/manifest.yaml", encoding="utf-8"))

WORD_RE = re.compile(r"[a-z0-9]+")


def words(items):
    out = set()
    for item in items:
        out |= set(WORD_RE.findall(str(item).lower()))
    return out


seed_trigger_words = words(seed.get("triggers") or [])
draft_trigger_words = words(draft.get("triggers") or [])
trigger_overlap = (
    len(seed_trigger_words & draft_trigger_words) / len(seed_trigger_words)
    if seed_trigger_words
    else 0.0
)

missing_trigger_words = sorted(seed_trigger_words - draft_trigger_words)
print(f"trigger word overlap: {trigger_overlap:.2%} (seed words not covered: {missing_trigger_words})")


def signature_matched(seed_sig, draft_sigs):
    seed_is_glob = "*" in seed_sig
    for d in draft_sigs:
        if seed_sig == d:
            return True
        d_is_glob = "*" in d
        if not seed_is_glob and d_is_glob and fnmatch.fnmatch(seed_sig, d):
            return True
        if seed_is_glob and not d_is_glob and fnmatch.fnmatch(d, seed_sig):
            return True
        if seed_is_glob and d_is_glob:
            sd = seed_sig.split("**")[0].rstrip("/")
            dd = d.split("**")[0].rstrip("/")
            if sd and dd and (sd == dd or sd.startswith(dd) or dd.startswith(sd)):
                return True
    return False


seed_sigs = seed.get("signatures") or []
draft_sigs = draft.get("signatures") or []
matched = [s for s in seed_sigs if signature_matched(s, draft_sigs)]
signature_overlap = len(matched) / len(seed_sigs) if seed_sigs else 0.0
print(f"signature overlap: {signature_overlap:.2%} ({len(matched)}/{len(seed_sigs)} seed signatures matched)")

ok = True
if trigger_overlap < 2 / 3:
    print(f"FAIL: trigger overlap {trigger_overlap:.2%} is below two-thirds")
    ok = False
if signature_overlap < 2 / 3:
    print(f"FAIL: signature overlap {signature_overlap:.2%} is below two-thirds")
    ok = False
sys.exit(0 if ok else 1)
PYEOF
then
    status=1
else
    echo "PASS: triggers and signatures each overlap the hand-built seed by at least two-thirds"
fi

echo "=== test-promote-pattern: commit scoping doesn't bleed across issue-number prefixes ==="
# This repo's own history already collides on exactly this: "#6" is a substring of "#61",
# "#64", "#65", "#66", "#67", "#69" — every two-digit issue in v0.1.0..v0.3.0 that starts
# with 6. An unanchored --grep would scope "issue 6" to all of them.
collision_hits=$(git log --format='%H' v0.1.0..v0.3.0 --grep='#6[^0-9]' -E)
if [ -n "$collision_hits" ]; then
    echo "FAIL: commit-scope pattern for issue 6 matched a commit in v0.1.0..v0.3.0 (expected none):"
    echo "$collision_hits"
    status=1
else
    echo "PASS: issue 6's commit-scope pattern matches nothing in v0.1.0..v0.3.0, despite #61/#64/#65/#66/#67/#69 all being in range"
fi
real_hits=$(git log --format='%H' v0.2.0..v0.3.0 --grep='#59[^0-9]' -E | wc -l | tr -d ' ')
if [ "$real_hits" -ge 3 ]; then
    echo "PASS: issue 59's commit-scope pattern still matches its own commits ($real_hits found)"
else
    echo "FAIL: issue 59's commit-scope pattern matched only $real_hits commits in v0.2.0..v0.3.0, expected at least 3"
    status=1
fi

echo "=== test-promote-pattern: end-to-end credential refusal through promote-pattern.sh ==="
repo_root=$(pwd)
sandbox=$(mktemp -d)
(
    cd "$sandbox"
    git init -q
    git config user.email "test@example.invalid"
    git config user.name "test"
    mkdir -p scripts src/main/java/com/example/agent/acp
    cp "$repo_root/scripts/promote-pattern.sh" scripts/
    cp -R "$repo_root/scripts/lib" scripts/
    echo "baseline" >baseline.txt
    git add -A
    git commit -q -m "chore: baseline"
    base=$(git rev-parse HEAD)
    cat >src/main/java/com/example/agent/acp/Fake.java <<'JAVA'
package com.example.agent.acp;

class Fake {
    private String token = "sk-0123456789realsecretvalue";
}
JAVA
    git add -A
    git commit -q -m "feat(acp): fake pattern for test (#1)"
    head=$(git rev-parse HEAD)
    e2e_status=0
    e2e_output=$(bash scripts/promote-pattern.sh --id fake-pattern --range "$base..$head" --issue 1 \
        --out patterns/fake-pattern 2>&1) || e2e_status=$?
    echo "$e2e_status" >.e2e-status
    echo "$e2e_output" >.e2e-output
)
e2e_status=$(cat "$sandbox/.e2e-status")
if [ "$e2e_status" -eq 2 ]; then
    echo "PASS: promote-pattern.sh exited 2 end-to-end on a real planted secret"
else
    echo "FAIL: promote-pattern.sh exited $e2e_status end-to-end on a real planted secret, expected 2"
    cat "$sandbox/.e2e-output"
    status=1
fi
if [ -d "$sandbox/patterns/fake-pattern" ]; then
    echo "FAIL: promote-pattern.sh wrote patterns/fake-pattern despite the credential hit"
    status=1
else
    echo "PASS: nothing written under patterns/ end-to-end"
fi
if grep -qF "realsecretvalue" "$sandbox/.e2e-output"; then
    echo "FAIL: promote-pattern.sh's end-to-end output printed the planted value"
    status=1
else
    echo "PASS: promote-pattern.sh's end-to-end output never printed the planted value"
fi
rm -rf "$sandbox"

echo "=== test-promote-pattern: credential fixture ==="
secret_value="ghp_FAKE1234567890FAKETOKENVALUE"
before=$(find scripts/test/credential-fixture -type f | sort)
cred_output=$(mktemp)
cred_status=0
sh scripts/lib/credential-grep.sh scripts/test/credential-fixture/skeleton >"$cred_output" 2>&1 || cred_status=$?
after=$(find scripts/test/credential-fixture -type f | sort)

if [ "$cred_status" -eq 2 ]; then
    echo "PASS: credential-grep.sh exited 2 on the fixture"
else
    echo "FAIL: credential-grep.sh exited $cred_status on the fixture, expected 2"
    status=1
fi

if [ "$before" = "$after" ]; then
    echo "PASS: nothing written under scripts/test/credential-fixture/"
else
    echo "FAIL: credential-grep.sh changed the fixture directory's contents"
    status=1
fi

if grep -qF "$secret_value" "$cred_output"; then
    echo "FAIL: credential-grep.sh's output printed the planted value"
    status=1
else
    echo "PASS: credential-grep.sh's output never printed the planted value"
fi
if ! grep -q "application-snippet.yml:8" "$cred_output"; then
    echo "FAIL: credential-grep.sh did not report the offending path and line"
    status=1
else
    echo "PASS: credential-grep.sh reported the offending path and line"
fi
rm -f "$cred_output"

echo "=== test-promote-pattern: an existing pattern is never overwritten by a re-run ==="
seed_before=$(cat patterns/stdio-json-rpc-agent/manifest.yaml patterns/stdio-json-rpc-agent/check.sh | sha256sum | cut -d' ' -f1)
overwrite_output=$(mktemp)
if bash scripts/promote-pattern.sh --id stdio-json-rpc-agent --range v0.2.0..v0.3.0 --issue 59 \
    >"$overwrite_output" 2>&1; then
    echo "FAIL: a real run on an existing --id exited 0 (it must refuse with 3 unless --replace)"
    cat "$overwrite_output"
    status=1
else
    rc=$?
    if [ "$rc" -eq 3 ]; then
        echo "PASS: real run on an existing --id refused with exit 3"
    else
        echo "FAIL: expected exit 3 on an existing --id, got $rc"
        cat "$overwrite_output"
        status=1
    fi
fi
seed_after=$(cat patterns/stdio-json-rpc-agent/manifest.yaml patterns/stdio-json-rpc-agent/check.sh | sha256sum | cut -d' ' -f1)
if [ "$seed_before" = "$seed_after" ]; then
    echo "PASS: the hand-built seed is byte-identical after the refused run"
else
    echo "FAIL: the hand-built seed changed"
    status=1
fi
rm -f "$overwrite_output"

echo "=== test-promote-pattern: --out cannot leave the repository ==="
for bad_out in ../elsewhere /tmp/elsewhere patterns; do
    if bash scripts/promote-pattern.sh --id probe --range v0.2.0..v0.3.0 --issue 59 --out "$bad_out" >/dev/null 2>&1; then
        echo "FAIL: --out $bad_out was accepted"
        status=1
    else
        rc=$?
        if [ "$rc" -eq 64 ]; then
            echo "PASS: --out $bad_out refused with exit 64"
        else
            echo "FAIL: --out $bad_out exited $rc, expected 64"
            status=1
        fi
    fi
done
[ -e ../elsewhere ] && { echo "FAIL: ../elsewhere was created"; status=1; }
[ -e /tmp/elsewhere ] && { echo "FAIL: /tmp/elsewhere was created"; status=1; }

exit $status
