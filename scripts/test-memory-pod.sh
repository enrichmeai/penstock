#!/bin/sh
# test-memory-pod.sh — the end-to-end measure for issue #82 (memory 5: review by grant):
# publish, grant a reviewer, exercise every rule the grant is supposed to enforce, revoke, and
# check the owner's own receipts agree with what actually happened. Exit non-zero on any
# mismatch; paste the transcript into the PR body — never say "passed" without it.
#
# Needs a real Cistern server (verified against docs/source at v0.2.0 for grant/revoke/pod,
# and at `main` for the `sync` this depends on via memory-publish.sh — see that script's own
# header), with an owner identity AND a second, reviewer identity. v0.2.0 has no Solid-OIDC
# (per its own README: "the pod is for you and your own machines only"), so a reviewer needs a
# second *local* identity, configured on the SERVER side before this script runs, via Spring
# relaxed-binding env vars (confirmed against cistern-webflux's
# ConfiguredServicePrincipalRegistry / CisternProperties.ServicePrincipal, not documented in
# Cistern's own README):
#
#   CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID=<reviewer webid>
#   CISTERN_AUTH_SERVICEPRINCIPALS_0_CREDENTIALHASH=sha256:<hex digest of the reviewer's plaintext token>
#
# e.g.: printf '%s' "$REVIEWER_TOKEN" | openssl dgst -sha256 -hex | sed 's/.*= /sha256:/'
#
# Required environment (never printed):
#   CISTERN_BASE_URL     the running server, e.g. http://127.0.0.1:3737
#   CISTERN_CLI_JAR       a cistern-cli jar built from Cistern `main` (needs `sync`)
#   CISTERN_TOKEN         the owner's bearer token (= CISTERN_OWNER_TOKEN given to the server)
#   CISTERN_OWNER_WEBID   the owner's WebID (= CISTERN_OWNER_WEBID given to the server)
#   REVIEWER_WEBID        the reviewer's WebID (must match the service-principal above)
#   REVIEWER_TOKEN        the reviewer's plaintext bearer token
#
# Not wired into build.yml (needs Docker and a CLI jar built from Cistern main, Java 25): run
# this in the owner's own session.
set -eu
cd "$(dirname "$0")/.."

: "${CISTERN_BASE_URL:?CISTERN_BASE_URL must be set}"
: "${CISTERN_CLI_JAR:?CISTERN_CLI_JAR must be set}"
: "${CISTERN_TOKEN:?CISTERN_TOKEN must be set}"
: "${CISTERN_OWNER_WEBID:?CISTERN_OWNER_WEBID must be set}"
: "${REVIEWER_WEBID:?REVIEWER_WEBID must be set}"
: "${REVIEWER_TOKEN:?REVIEWER_TOKEN must be set}"

slug="test-reviewer"
other_slug="test-other-reviewer"
pattern_id="stdio-json-rpc-agent"
today=$(date -u +%Y-%m-%d)
verdict_file="$pattern_id-$today.yaml"

status=0
check_count=0

# Receipts are counted from here on, so a pod the reviewer identity touched before this run
# (a smoke test, an earlier run) does not make the count below fail. ISO instant, half-open
# [from, to) as the receipts query defines it.
run_started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "=== test-memory-pod: publish ==="
sh scripts/memory-publish.sh --base "$CISTERN_BASE_URL"

echo "=== test-memory-pod: grant reviewer $REVIEWER_WEBID --as $slug ==="
sh scripts/memory-grant.sh reviewer "$REVIEWER_WEBID" --as "$slug" --base "$CISTERN_BASE_URL"

# expect_status <label> <expected-code> <method> <bearer> <path> [data-file]
expect_status() {
    label="$1"; expected="$2"; method="$3"; bearer="$4"; path="$5"; data_file="${6:-}"
    check_count=$((check_count + 1))
    if [ -n "$data_file" ]; then
        got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" \
            -H "Authorization: Bearer $bearer" -H 'If-None-Match: *' \
            -H 'Content-Type: application/yaml' --data-binary "@$data_file" \
            "$CISTERN_BASE_URL$path")
    else
        got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" \
            -H "Authorization: Bearer $bearer" \
            "$CISTERN_BASE_URL$path")
    fi
    if [ "$got" = "$expected" ]; then
        echo "PASS ($check_count): $label -> $got"
    else
        echo "FAIL ($check_count): $label -> expected $expected, got $got" >&2
        status=1
    fi
}

echo "=== test-memory-pod: reviewer exercises the grant ==="
expect_status "reviewer GET /memory/patterns/$pattern_id/manifest.yaml" 200 GET \
    "$REVIEWER_TOKEN" "/memory/patterns/$pattern_id/manifest.yaml"

expect_status "reviewer GET /memory/requests/acp-in-ide.yaml" 403 GET \
    "$REVIEWER_TOKEN" "/memory/requests/acp-in-ide.yaml"

verdict_tmp=$(mktemp)
trap 'rm -f "$verdict_tmp"' EXIT
cat >"$verdict_tmp" <<YAML
pattern: $pattern_id
version: 1
verdict: keep
reasons:
  - exercised by scripts/test-memory-pod.sh
reviewer: $REVIEWER_WEBID
date: $today
YAML

expect_status "reviewer PUT /memory/verdicts/$slug/$verdict_file" 201 PUT \
    "$REVIEWER_TOKEN" "/memory/verdicts/$slug/$verdict_file" "$verdict_tmp"

expect_status "reviewer GET their own verdict back" 200 GET \
    "$REVIEWER_TOKEN" "/memory/verdicts/$slug/$verdict_file"

expect_status "reviewer PUT into another slug's folder" 403 PUT \
    "$REVIEWER_TOKEN" "/memory/verdicts/$other_slug/intrusion.yaml" "$verdict_tmp"

echo "=== test-memory-pod: revoke ==="
sh scripts/memory-revoke.sh "$REVIEWER_WEBID" --as "$slug" --base "$CISTERN_BASE_URL"

expect_status "reviewer GET the manifest again, after revoke" 403 GET \
    "$REVIEWER_TOKEN" "/memory/patterns/$pattern_id/manifest.yaml"

echo "=== test-memory-pod: owner checks the receipts ==="
encoded_webid=$(printf '%s' "$REVIEWER_WEBID" | python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=""))')
receipts=$(curl -sf -H "Authorization: Bearer $CISTERN_TOKEN" \
    "$CISTERN_BASE_URL/?receipts&agent=$encoded_webid&from=$run_started_at")

receipts_status=0
python3 - "$receipts" "$check_count" <<'PYEOF' || receipts_status=$?
import json
import sys

receipts_text = sys.argv[1]
expected_count = int(sys.argv[2])

records = [json.loads(line) for line in receipts_text.splitlines() if line.strip()]
print(f"[test-memory-pod] receipts for the reviewer since this run started: {len(records)} decision(s)")
for r in records:
    print(f"  {r.get('at')} {r.get('required')} {r.get('outcome')} {r.get('target')}")

ok = True
if len(records) != expected_count:
    print(f"FAIL: expected exactly {expected_count} receipted decisions, got {len(records)}")
    ok = False

for r in records:
    if r.get("outcome") == "ALLOWED":
        target = r.get("target", "")
        if "/memory/patterns/" not in target and "/memory/verdicts/" not in target:
            print(f"FAIL: an ALLOWED decision outside /memory/patterns/ or /memory/verdicts/: {target}")
            ok = False

sys.exit(0 if ok else 1)
PYEOF
if [ "$receipts_status" -ne 0 ]; then
    status=1
fi

if [ "$status" -eq 0 ]; then
    echo "=== test-memory-pod: ALL CHECKS PASSED ==="
else
    echo "=== test-memory-pod: FAILED ===" >&2
fi
exit "$status"
