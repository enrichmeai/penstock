#!/bin/sh
# Exercises scripts/start.sh (#147) offline, in a scratch HOME, with stub curl, java and claude
# on PATH (the release JSON fixtures under scripts/test/start/ are the real v0.5.2 release's shape):
#   1. a fresh run downloads and checks the jar, creates the memory root, registers the server once
#   2. a second run downloads nothing, registers nothing and changes no file
#   3. a second project joins the same root, and check-cards.sh passes on it
#   4. a jar whose SHA-256 differs from the release digest is deleted and refused (exit 1)
#   5. a release with no digest is refused (exit 1); the jar asset may sit anywhere in the list
#   6. Java older than 21, an unreadable project name and a bad version are refused
#   7. without Claude Code, it prints the command to run and still succeeds
#   8. an existing root is joined at its own indentation, and one without a projects: block is left
#      as it is, with check-cards.sh still passing
#   9. java that prints no version gets the install advice; a relative memory root is made absolute
#  10. piped into sh (what `curl | sh` does), a command that reads stdin cannot eat the script
set -eu
cd "$(dirname "$0")/.."
here=$(pwd)

status=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; status=1; }

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
stubs="$scratch/stubs"; mkdir -p "$stubs"
printf 'not really a jar\n' >"$scratch/fake.jar"
if command -v sha256sum >/dev/null 2>&1; then good=$(sha256sum "$scratch/fake.jar" | cut -d ' ' -f 1)
else good=$(shasum -a 256 "$scratch/fake.jar" | cut -d ' ' -f 1); fi

# curl: the API URL prints the fixture with the digest filled in; -o <file> <url> "downloads" the fake jar.
cat >"$stubs/curl" <<'EOF'
#!/bin/sh
out=""; url=""
while [ $# -gt 0 ]; do
  case "$1" in -o) out=$2; shift 2 ;; -H) shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac
done
echo "curl $url ${out:+-> download}" >>"$STUB_LOG"
if [ -n "$out" ]; then cp "$STUB_JAR" "$out"; else sed "s/@JAR_DIGEST@/$STUB_DIGEST/" "$STUB_RELEASE"; fi
EOF
# java: prints the version line it is told to, and reads stdin as a careless command would.
cat >"$stubs/java" <<'EOF'
#!/bin/sh
cat >/dev/null
if [ -n "${STUB_JAVA_SILENT:-}" ]; then echo "The operation couldn't be completed. Unable to locate a Java Runtime." >&2; exit 1; fi
echo "openjdk version \"$STUB_JAVA\" 2026-07-15" >&2
EOF
# claude: `mcp get` succeeds once `mcp add` has run; both read stdin like a careless command would.
cat >"$stubs/claude" <<'EOF'
#!/bin/sh
cat >/dev/null
case "$1 $2" in
  "mcp get") [ -f "$STUB_STATE" ] ;;
  "mcp add") shift 2; echo "claude mcp add $*" >>"$STUB_LOG"; : >"$STUB_STATE" ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$stubs/curl" "$stubs/java" "$stubs/claude"
mkdir -p "$scratch/nostubs-claude"; cp "$stubs/curl" "$stubs/java" "$scratch/nostubs-claude/"

reset_home() {
  rm -rf "$scratch/home" "$scratch/state" "$scratch/log"
  mkdir -p "$scratch/home/work/My_App" "$scratch/home/work/second-one"
  : >"$scratch/log"
}
# run <project dir> [VAR=value ...]: start.sh with the stubs first on PATH; output in $out, exit code in $rc
run() {
  dir=$1; shift
  rc=0
  out=$(cd "$dir" && env HOME="$scratch/home" PATH="$stubs:/usr/bin:/bin" STUB_LOG="$scratch/log" \
    STUB_STATE="$scratch/state" STUB_JAR="$scratch/fake.jar" STUB_DIGEST="$good" STUB_JAVA=21.0.7 \
    STUB_RELEASE="$here/scripts/test/start/release.json" "$@" sh "$here/scripts/start.sh" </dev/null 2>&1) || rc=$?
}
app="$scratch/home/work/My_App"
root="$scratch/home/penstock-memory"

echo "=== test-start: a fresh run ==="
reset_home
run "$app"
ok=1
[ "$rc" = 0 ] || { ok=0; echo "$out"; }
[ -f "$scratch/home/.penstock/penstock-0.5.2.jar" ] || ok=0
[ "$(readlink "$scratch/home/.penstock/penstock.jar")" = penstock-0.5.2.jar ] || ok=0
grep -q '^  my-app: { audience: private }$' "$root/memory.yaml" || ok=0
grep -q '^visibility: private$' "$root/memory.yaml" || ok=0
for d in estate/facts projects/my-app/episodes projects/my-app/facts projects/my-app/patterns; do [ -d "$root/$d" ] || ok=0; done
[ -d "$root/.git" ] && [ -f "$root/README.md" ] || ok=0
[ "$(grep -c '^claude mcp add' "$scratch/log")" = 1 ] || ok=0
grep -q "^claude mcp add -e AGENT_MEMORY_ROOT=$root -e AGENT_MEMORY_PROJECT=my-app --transport stdio penstock-memory -- java -jar $scratch/home/.penstock/penstock.jar --mcp\$" "$scratch/log" || ok=0
printf '%s' "$out" | grep -q '/mcp__penstock-memory__start-with-memory' || ok=0
[ "$ok" = 1 ] && pass "fresh run: jar checked, root created, server registered once, first step printed" || { fail "fresh run"; cat "$scratch/log"; }

echo "=== test-start: a second run changes nothing ==="
before=$(cd "$scratch/home" && find . -type f ! -path './penstock-memory/.git/*' -exec cksum {} + | sort)
: >"$scratch/log"
run "$app"
after=$(cd "$scratch/home" && find . -type f ! -path './penstock-memory/.git/*' -exec cksum {} + | sort)
ok=1
[ "$rc" = 0 ] || ok=0
grep -q 'download' "$scratch/log" && ok=0
grep -q '^claude mcp add' "$scratch/log" && ok=0
[ "$before" = "$after" ] || ok=0
[ "$ok" = 1 ] && pass "second run: no download, no registration, no file changed" || { fail "second run"; echo "$out"; }

echo "=== test-start: a second project joins the same root ==="
rm -f "$scratch/state"
run "$scratch/home/work/second-one"
ok=1
[ "$rc" = 0 ] || ok=0
grep -q '^  my-app: { audience: private }$' "$root/memory.yaml" || ok=0
grep -q '^  second-one: { audience: private }$' "$root/memory.yaml" || ok=0
[ -d "$root/projects/second-one/episodes" ] || ok=0
[ "$ok" = 1 ] && pass "second project added under projects:, the first kept" || { fail "second project"; echo "$out"; cat "$root/memory.yaml"; }
if python3 -c 'import yaml' 2>/dev/null; then
  if cc=$(bash scripts/check-cards.sh --root "$root" 2>&1); then pass "check-cards.sh --root passes on the created root"
  else fail "check-cards.sh --root on the created root"; echo "$cc" | grep -E 'FAIL|ERROR' | head; fi
else
  echo "NOT RUN: check-cards.sh on the created root (PyYAML missing)"
fi

echo "=== test-start: a jar that does not match the digest ==="
reset_home
run "$app" STUB_DIGEST=0000000000000000000000000000000000000000000000000000000000000000
ok=1
[ "$rc" = 1 ] || ok=0
printf '%s' "$out" | grep -q 'deleted it, not installing' || ok=0
[ -e "$scratch/home/.penstock/penstock-0.5.2.jar" ] && ok=0
[ -e "$scratch/home/.penstock/penstock-0.5.2.jar.part" ] && ok=0
grep -q '^claude mcp add' "$scratch/log" && ok=0
[ "$ok" = 1 ] && pass "a mismatched jar is deleted, exit 1, nothing registered" || { fail "digest mismatch"; echo "$out"; }

echo "=== test-start: a release with no digest; the jar listed last ==="
reset_home
run "$app" STUB_RELEASE="$here/scripts/test/start/release-no-digest.json"
{ [ "$rc" = 1 ] && printf '%s' "$out" | grep -q 'publishes no SHA-256 digest'; } && pass "no digest: refused, exit 1" || { fail "no digest"; echo "$out"; }
reset_home
run "$app" STUB_RELEASE="$here/scripts/test/start/release-jar-last.json"
{ [ "$rc" = 0 ] && [ -f "$scratch/home/.penstock/penstock-0.5.2.jar" ]; } && pass "jar listed last: found and checked" || { fail "jar listed last"; echo "$out"; }

echo "=== test-start: refusals ==="
reset_home
run "$app" STUB_JAVA=17.0.9
{ [ "$rc" = 1 ] && printf '%s' "$out" | grep -q 'Java 17 found; Penstock needs 21'; } && pass "Java 17 refused" || { fail "Java 17"; echo "$out"; }
run "$app" STUB_JAVA=1.8.0_402
{ [ "$rc" = 1 ] && printf '%s' "$out" | grep -q 'Java 8 found'; } && pass "Java 1.8 read as 8 and refused" || { fail "Java 1.8"; echo "$out"; }
run "$app" PENSTOCK_PROJECT=estate
[ "$rc" = 64 ] && pass "project 'estate' refused, exit 64" || fail "project estate (rc=$rc)"
run "$app" PENSTOCK_PROJECT='../x'
[ "$rc" = 64 ] && pass "project '../x' refused, exit 64" || fail "project ../x (rc=$rc)"
run "$app" PENSTOCK_VERSION='0.5;rm'
[ "$rc" = 64 ] && pass "a bad PENSTOCK_VERSION refused, exit 64" || fail "bad version (rc=$rc)"
[ -e "$root" ] && fail "a refused run created the memory root" || pass "refused runs created nothing"

echo "=== test-start: an existing root is extended safely ==="
# entries indented 4 spaces, block-style, with a nested key named like the new project
reset_home
mkdir -p "$root/estate" "$root/projects/oss"
printf 'format: 1\nvisibility: private\nprojects:\n    oss:\n        audience: public\n        uses: []\n' >"$root/memory.yaml"
run "$scratch/home/work/second-one" PENSTOCK_PROJECT=uses
ok=1
[ "$rc" = 0 ] || ok=0
grep -q '^    uses: { audience: private }$' "$root/memory.yaml" || ok=0
grep -q '^    oss:$' "$root/memory.yaml" || ok=0
[ -d "$root/projects/uses/episodes" ] || ok=0
if python3 -c 'import yaml' 2>/dev/null; then
  python3 -c 'import sys,yaml; d=yaml.safe_load(open(sys.argv[1])); assert set(d["projects"])=={"oss","uses"}, d' "$root/memory.yaml" 2>/dev/null || ok=0
fi
[ "$ok" = 1 ] && pass "4-space block entries: joined at their indent, a nested 'uses:' key is not a declaration, still valid YAML" || { fail "4-space root"; echo "$out"; cat "$root/memory.yaml"; }
# no projects: block at all: memory.yaml untouched, no section folder, a plain instruction
reset_home
mkdir -p "$root/estate"
printf 'format: 1\nvisibility: private\n' >"$root/memory.yaml"
before=$(cksum <"$root/memory.yaml")
run "$app"
ok=1
[ "$rc" = 0 ] || ok=0
[ "$(cksum <"$root/memory.yaml")" = "$before" ] || ok=0
[ -e "$root/projects/my-app" ] && ok=0
printf '%s' "$out" | grep -q "memory.yaml does not declare 'my-app'" || ok=0
printf '%s' "$out" | grep -q "Not registered: declare 'my-app'" || ok=0
grep -q '^claude mcp add' "$scratch/log" && ok=0
[ "$ok" = 1 ] && pass "no projects: block: memory.yaml unchanged, no projects/my-app/, not registered, says what to add" || { fail "no projects block"; echo "$out"; }
if python3 -c 'import yaml' 2>/dev/null; then
  if cc=$(bash scripts/check-cards.sh --root "$root" 2>&1); then pass "check-cards.sh still passes on that root"
  else fail "check-cards.sh on the undeclared root"; echo "$cc" | grep -E 'FAIL|ERROR' | head; fi
fi

# a quoted key is already a declaration: no duplicate entry
reset_home
mkdir -p "$root/estate"
printf 'format: 1\nvisibility: private\nprojects:\n  "my-app": { audience: private }\n' >"$root/memory.yaml"
before=$(cksum <"$root/memory.yaml")
run "$app"
{ [ "$rc" = 0 ] && [ "$(cksum <"$root/memory.yaml")" = "$before" ] && printf '%s' "$out" | grep -q "already declares 'my-app'"; } \
  && pass "a quoted \"my-app\": key counts as declared, no duplicate" || { fail "quoted key"; echo "$out"; cat "$root/memory.yaml"; }

echo "=== test-start: java without a version; a relative memory root ==="
reset_home
run "$app" STUB_JAVA_SILENT=1
{ [ "$rc" = 1 ] && printf '%s' "$out" | grep -q 'did not report a version' && printf '%s' "$out" | grep -q 'Temurin 21'; } \
  && pass "java with no version line: exit 1 with the install advice" || { fail "silent java"; echo "$out"; }
reset_home
run "$app" PENSTOCK_MEMORY_ROOT=../../mem
{ [ "$rc" = 0 ] && grep -q "AGENT_MEMORY_ROOT=$scratch/home/mem " "$scratch/log"; } \
  && pass "a relative PENSTOCK_MEMORY_ROOT is registered as an absolute path" || { fail "relative root"; cat "$scratch/log"; }

echo "=== test-start: no Claude Code ==="
reset_home
rc=0
out=$(cd "$app" && env HOME="$scratch/home" PATH="$scratch/nostubs-claude:/usr/bin:/bin" STUB_LOG="$scratch/log" \
  STUB_JAR="$scratch/fake.jar" STUB_DIGEST="$good" STUB_JAVA=21.0.7 \
  STUB_RELEASE="$here/scripts/test/start/release.json" sh "$here/scripts/start.sh" </dev/null 2>&1) || rc=$?
if command -v claude >/dev/null 2>&1 && [ "$(command -v claude)" != "${stubs}/claude" ] && PATH="$scratch/nostubs-claude:/usr/bin:/bin" command -v claude >/dev/null 2>&1; then
  echo "NOT RUN: no-Claude-Code case (a real claude is on /usr/bin:/bin)"
else
  { [ "$rc" = 0 ] && printf '%s' "$out" | grep -q 'was not found. Once it is installed' \
    && printf '%s' "$out" | grep -q "claude mcp add -e 'AGENT_MEMORY_ROOT="; } \
    && pass "without claude: prints the command, exit 0" || { fail "no claude"; echo "$out"; }
fi

echo "=== test-start: run from stdin, as curl | sh does ==="
reset_home
rc=0
out=$(cd "$app" && env HOME="$scratch/home" PATH="$stubs:/usr/bin:/bin" STUB_LOG="$scratch/log" \
  STUB_STATE="$scratch/state" STUB_JAR="$scratch/fake.jar" STUB_DIGEST="$good" STUB_JAVA=21.0.7 \
  STUB_RELEASE="$here/scripts/test/start/release.json" sh -c "cat \"$here/scripts/start.sh\" | sh" 2>&1) || rc=$?
{ [ "$rc" = 0 ] && printf '%s' "$out" | grep -q 'To remove it from this project, run here:  claude mcp remove penstock-memory'; } \
  && pass "from stdin: java and claude read nothing of the script, it runs to the end" || { fail "from stdin"; echo "$out"; }

exit $status
