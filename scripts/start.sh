#!/bin/sh
# Start in one command (#147): Penstock's memory in Claude Code, on this machine only.
#
#   curl -fsSL https://github.com/enrichmeai/penstock/releases/latest/download/start.sh | sh
#
# The release workflow attaches this script to every release from the first one after #147, so the
# script and the jar it fetches come from the same release.
#
# Run it from inside the project you work on. It:
#   1. checks for Java 21 or later;
#   2. downloads the latest release jar to ~/.penstock/ and checks its SHA-256 against the digest
#      GitHub publishes for the release asset;
#   3. creates a private memory root (~/penstock-memory, a git repository) declaring this project,
#      or adds this project's one line to the memory.yaml already there (only when that file lists
#      its projects in a block; otherwise it says what to add and changes nothing);
#   4. registers the MCP server `penstock-memory` with Claude Code for this project;
#   5. prints what to try first, and how to remove it.
#
# No account, no network listener, no credential. Running it again changes nothing.
# Under `curl … | sh` the script is read from stdin, so every command that could read stdin is
# given </dev/null: otherwise it would swallow the rest of the script.
#
# Settings (environment): PENSTOCK_HOME (default ~/.penstock), PENSTOCK_MEMORY_ROOT (default
# ~/penstock-memory), PENSTOCK_PROJECT (default: this folder's name, lower-cased), PENSTOCK_VERSION
# (a release such as 0.5.2; default the latest).
#
# Exit codes: 0 done, 1 a step failed (the message says which), 64 a bad setting.
set -eu

REPO_API="https://api.github.com/repos/enrichmeai/penstock"
SERVER="penstock-memory"
PENSTOCK_HOME=${PENSTOCK_HOME:-"$HOME/.penstock"}
MEMORY_ROOT=${PENSTOCK_MEMORY_ROOT:-"$HOME/penstock-memory"}

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die()  { printf 'penstock start: %s\n' "$*" >&2; exit 1; }

# --- the project ------------------------------------------------------------------------------
if [ -n "${PENSTOCK_PROJECT:-}" ]; then
  PROJECT=$PENSTOCK_PROJECT
else
  PROJECT=$(basename "$PWD" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9-]/-/g; s/--*/-/g; s/^-//; s/-$//')
fi
if ! printf '%s' "$PROJECT" | grep -Eq '^[a-z0-9][a-z0-9-]*$' || [ "$PROJECT" = estate ]; then
  printf 'penstock start: "%s" is not a project name ([a-z0-9-], not estate); set PENSTOCK_PROJECT\n' "$PROJECT" >&2
  exit 64
fi
if [ -n "${PENSTOCK_VERSION:-}" ] && ! printf '%s' "$PENSTOCK_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  printf 'penstock start: PENSTOCK_VERSION "%s" is not a release number such as 0.5.2\n' "$PENSTOCK_VERSION" >&2
  exit 64
fi

say "Penstock memory for project '$PROJECT' (memory root $MEMORY_ROOT)"

# --- 1. Java ----------------------------------------------------------------------------------
step "Checking Java"
command -v java >/dev/null 2>&1 || die "Java 21 or later is needed and none was found. Install a JDK 21 (for example Eclipse Temurin 21 from https://adoptium.net) and run this again."
# The first line of `java -version` names the version: openjdk version "21.0.7" (or "1.8.0_402").
JAVA_LINE=$(java -version 2>&1 </dev/null | grep -i 'version' | head -n 1)
JAVA_MAJOR=$(printf '%s' "$JAVA_LINE" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
[ "$JAVA_MAJOR" = 1 ] && JAVA_MAJOR=$(printf '%s' "$JAVA_LINE" | sed -n 's/.*version "1\.\([0-9][0-9]*\).*/\1/p')
[ -n "$JAVA_MAJOR" ] || die "the 'java' command did not report a version (${JAVA_LINE:-no output}), so no usable Java is installed. Install a JDK 21 (for example Eclipse Temurin 21 from https://adoptium.net) and run this again."
[ "$JAVA_MAJOR" -ge 21 ] || die "Java $JAVA_MAJOR found; Penstock needs 21 or later. Install a JDK 21 (for example Eclipse Temurin 21 from https://adoptium.net) and run this again."
say "Java $JAVA_MAJOR"

# --- 2. The release jar -----------------------------------------------------------------------
step "Fetching the Penstock release"
command -v curl >/dev/null 2>&1 || die "curl is needed to download the release"
if [ -n "${PENSTOCK_VERSION:-}" ]; then RELEASE_URL="$REPO_API/releases/tags/v$PENSTOCK_VERSION"; else RELEASE_URL="$REPO_API/releases/latest"; fi
RELEASE_JSON=$(curl -fsSL -H 'Accept: application/vnd.github+json' "$RELEASE_URL") || die "could not read $RELEASE_URL"
# The release JSON is read with POSIX tools, not jq, so nothing beyond a shell and curl is needed.
# One "key": value per line; each asset starts at its own "url" (…/releases/assets/<id>), and the
# jar's name, digest and download URL are taken from the same asset, whatever their order.
FIELDS=$(printf '%s' "$RELEASE_JSON" | tr ',{}' '\n\n\n')
TAG=$(printf '%s\n' "$FIELDS" | sed -n 's/^[[:space:]]*"tag_name"[[:space:]]*:[[:space:]]*"v\([0-9][0-9.]*\)".*/\1/p' | head -n 1)
[ -n "$TAG" ] || die "the release at $RELEASE_URL has no v<version> tag"
JAR_NAME="penstock-$TAG.jar"
ASSET=$(printf '%s\n' "$FIELDS" | awk -v want="$JAR_NAME" '
  function value(s) { sub(/^[^:]*:[[:space:]]*"/, "", s); sub(/".*$/, "", s); return s }
  function flush() { if (name == want && !found) { print digest " " url; found = 1 } name = ""; digest = ""; url = "" }
  /^[[:space:]]*"url"[[:space:]]*:[[:space:]]*"[^"]*\/releases\/assets\// { flush() }
  /^[[:space:]]*"name"[[:space:]]*:/                 { if (name == "") name = value($0) }
  /^[[:space:]]*"digest"[[:space:]]*:/               { if (digest == "") digest = value($0) }
  /^[[:space:]]*"browser_download_url"[[:space:]]*:/ { if (url == "") url = value($0) }
  END { flush() }')
[ -n "$ASSET" ] || die "release v$TAG has no $JAR_NAME asset"
DIGEST=$(printf '%s' "$ASSET" | cut -d ' ' -f 1 | sed -n 's/^sha256:\([0-9a-f]\{64\}\)$/\1/p')
JAR_URL=$(printf '%s' "$ASSET" | cut -d ' ' -f 2)
[ -n "$DIGEST" ] || die "release v$TAG publishes no SHA-256 digest for $JAR_NAME, so the download cannot be checked; not installing"
[ -n "$JAR_URL" ] || die "release v$TAG has no download URL for $JAR_NAME"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d ' ' -f 1
  else die "neither sha256sum nor shasum was found, so the download cannot be checked"; fi
}

mkdir -p "$PENSTOCK_HOME"
JAR="$PENSTOCK_HOME/$JAR_NAME"
if [ -f "$JAR" ] && [ "$(sha256_of "$JAR")" = "$DIGEST" ]; then
  say "$JAR_NAME already here, checksum matches"
else
  PART="$JAR.part"
  rm -f "$PART"
  curl -fsSL -o "$PART" "$JAR_URL" || { rm -f "$PART"; die "could not download $JAR_URL"; }
  GOT=$(sha256_of "$PART")
  if [ "$GOT" != "$DIGEST" ]; then
    rm -f "$PART"
    die "the downloaded $JAR_NAME has SHA-256 $GOT, the release says $DIGEST; deleted it, not installing"
  fi
  mv "$PART" "$JAR"
  say "downloaded $JAR_NAME, checksum matches"
fi
# Claude Code launches a fixed path; a later run moves the link to the newer release.
LINK="$PENSTOCK_HOME/penstock.jar"
if [ "$(readlink "$LINK" 2>/dev/null || true)" != "$JAR_NAME" ]; then
  ln -sfn "$JAR_NAME" "$LINK"
fi

# --- 3. The memory root -----------------------------------------------------------------------
step "Preparing the memory root"
write_new() { # write_new <path>: stdin to <path>, only when it does not exist yet
  if [ -e "$1" ]; then cat >/dev/null; else cat >"$1"; fi
}
mkdir -p "$MEMORY_ROOT"
MEMORY_ROOT=$(CDPATH= cd -- "$MEMORY_ROOT" && pwd)   # Claude Code may start the server from another folder
# How memory.yaml declares its projects: "declared", "insert <indent>" (a block-style `projects:` the
# entry can join at the indent its entries already use), "none" (no `projects:` key) or "other"
# (`projects:` written some other way, such as a flow mapping). Only entries of that block count.
project_state() {
  awk -v want="$PROJECT" '
    /^projects:[[:space:]]*$/ { seen = 1; inblock = 1; next }
    /^projects:/              { seen = 1; other = 1; next }
    inblock && /^[[:space:]]*(#.*)?$/ { next }
    inblock {
      match($0, /^ */); n = RLENGTH
      if (n == 0) { inblock = 0; next }
      if (indent == "") indent = n
      if (n == indent) { key = substr($0, n + 1); sub(/[[:space:]]*:.*/, "", key); gsub(/^["\047]|["\047]$/, "", key); if (key == want) found = 1 }
    }
    END {
      if (found) print "declared"
      else if (other) print "other"
      else if (seen) print "insert " (indent == "" ? 2 : indent)
      else print "none"
    }' "$MEMORY_ROOT/memory.yaml"
}
SECTION=yes
if [ ! -f "$MEMORY_ROOT/memory.yaml" ]; then
  write_new "$MEMORY_ROOT/memory.yaml" <<EOF
# Your memory root (memory format v1; https://github.com/enrichmeai/penstock/blob/main/docs/memory-root.md).
# Private: it stays on this machine unless you push it somewhere yourself.
format: 1
visibility: private
projects:
  $PROJECT: { audience: private }
EOF
  say "created $MEMORY_ROOT/memory.yaml declaring '$PROJECT'"
else
  STATE=$(project_state)
  case "$STATE" in
    declared) say "memory.yaml already declares '$PROJECT'" ;;
    "insert "*)
      # Join the block at the indent its entries already use, directly under `projects:`.
      INDENT=$(awk -v n="${STATE#insert }" 'BEGIN { while (i++ < n) printf " " }')
      TMP="$MEMORY_ROOT/memory.yaml.tmp"
      awk -v line="$INDENT$PROJECT: { audience: private }" \
        '{ print } /^projects:[[:space:]]*$/ && !done { print line; done = 1 }' \
        "$MEMORY_ROOT/memory.yaml" >"$TMP" && mv "$TMP" "$MEMORY_ROOT/memory.yaml"
      say "added '$PROJECT' to memory.yaml (audience: private)" ;;
    *)
      # Without a declaration, check-cards.sh fails a projects/<name>/ folder, and adding a whole
      # `projects:` block would cut every undeclared project down to the estate. The owner decides.
      SECTION=no
      say "memory.yaml does not declare '$PROJECT', and this script will not change how it declares projects."
      say "Add it yourself under a block-style 'projects:' as  $PROJECT: { audience: private }  then run this again."
      say "Until then no projects/$PROJECT/ folder is created, and recall reads only what the root already declares." ;;
  esac
fi
for d in estate/facts estate/references; do mkdir -p "$MEMORY_ROOT/$d"; done
if [ "$SECTION" = yes ]; then
  for d in requests references patterns episodes facts; do mkdir -p "$MEMORY_ROOT/projects/$PROJECT/$d"; done
fi
write_new "$MEMORY_ROOT/README.md" <<'EOF'
# My memory

Cards that Penstock gives an AI coding agent before it plans: what you decided, corrected and
learned, per project. Plain YAML, owned by you; nothing here leaves this machine unless you push it.

- `estate/` holds what is true across all your work; `projects/<name>/` what is true for one project.
- The agent proposes episodes (`draft_episode`); you read them and keep, edit or delete them.
- The format: https://github.com/enrichmeai/penstock/blob/main/docs/memory-format.md
EOF
if command -v git >/dev/null 2>&1 && [ ! -d "$MEMORY_ROOT/.git" ]; then
  git -C "$MEMORY_ROOT" init -q </dev/null && say "made $MEMORY_ROOT a git repository (nothing committed; that is yours to do)"
fi

# --- 4. Claude Code ---------------------------------------------------------------------------
step "Registering with Claude Code"
if [ "$SECTION" = no ]; then
  # A session for an undeclared project would draft episodes into a folder check-cards.sh fails.
  say "Not registered: declare '$PROJECT' in $MEMORY_ROOT/memory.yaml first (see above), then run this again."
  exit 0
fi
ADD_CMD="claude mcp add -e 'AGENT_MEMORY_ROOT=$MEMORY_ROOT' -e 'AGENT_MEMORY_PROJECT=$PROJECT' --transport stdio $SERVER -- java -jar '$LINK' --mcp"
if ! command -v claude >/dev/null 2>&1; then
  say "Claude Code (the 'claude' command) was not found. Once it is installed, run this from $PWD:"
  say "  $ADD_CMD"
elif claude mcp get "$SERVER" </dev/null >/dev/null 2>&1; then
  say "'$SERVER' is already registered for this project (claude mcp get $SERVER shows it); its settings are kept."
  say "To change them, run 'claude mcp remove $SERVER' here, then this script again."
else
  # Local scope, Claude Code's default: only you, only this project. At least one option must sit
  # between the last -e and the server name, or the name is read as another KEY=value pair.
  claude mcp add -e "AGENT_MEMORY_ROOT=$MEMORY_ROOT" -e "AGENT_MEMORY_PROJECT=$PROJECT" \
    --transport stdio "$SERVER" -- java -jar "$LINK" --mcp </dev/null >/dev/null \
    || die "claude mcp add failed; run it yourself to see why: $ADD_CMD"
  say "registered '$SERVER' for this project"
fi

# --- 5. What next -----------------------------------------------------------------------------
step "Done"
say "Start Claude Code here and type:  /mcp__${SERVER}__start-with-memory"
say "It recalls your memory for this project before planning (empty at first: it says so)."
say "When you correct the agent, ask it to draft an episode; review the file under"
say "  $MEMORY_ROOT/projects/$PROJECT/episodes/  and keep, edit or delete it."
say ""
say "To remove it from this project, run here:  claude mcp remove $SERVER"
say "The jar in $PENSTOCK_HOME is shared by every project you set up; delete it once none uses it. Your memory root stays."
