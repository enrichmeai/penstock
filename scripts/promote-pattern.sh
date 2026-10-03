#!/bin/sh
# promote-pattern.sh — scaffold patterns/<id>/ as a draft from a finished task's branch diff.
# #!/bin/sh, same portability as check-cards.sh; python3 does the YAML/text work.
#
# Derivation rules (documented here per issue #77 — "Spec: none" in the issue, this is the
# implementer's reading, made auditable so the next change can sharpen it rather than guess):
#
#   commit scope = within --range, the commits whose subject or body matches "#<issue>" not
#                  immediately followed by another digit (git log --grep, so "#6" cannot
#                  match inside "#61" or "#67" — this repo's own issue numbers collide on
#                  exactly this prefix). If none match, the whole range is used and the
#                  script says so — a range can legitimately bundle more than one task's
#                  commits (e.g. a tag-to-tag range spans several merged PRs), and scoping by
#                  issue reference keeps the draft to the task that was asked for.
#   eligible files = the scoped commits' *added* files (status A), except anything under a
#                  "test" path segment, under .github/ or .claude/, or one of the repo's own
#                  meta files (README.md, ROADMAP.md, CLAUDE.md, build.gradle,
#                  settings.gradle, gradle.properties) — those describe the repo, not the
#                  pattern. Falls back to added-or-modified when the scoped commits added
#                  nothing at all (a modify-only task still needs a signature/skeleton) —
#                  otherwise the merely-touched integration points a new package always
#                  drags in (AgentApplication, ToolRegistry, application.yml, ...) dilute
#                  both the signature globs and the trigger list with directories that
#                  describe the wiring, not the pattern.
#   signatures   = the directories of eligible files, as "<dir>/**" globs, deduplicated;
#                  plus, for any *added* file with no "/" in its path (a genuinely new
#                  top-level file), that exact path.
#   triggers (max 12), each group deduplicated against what came before, in this order:
#     1. ALL-CAPS acronyms (ACP, IDE, JSON, RPC) found in the eligible *.md files' own text,
#        the request card's `want:` sentence and the issue title — a protocol/technology
#        name, the closest mechanical stand-in for "the request card's want: nouns" the
#        issue asks for (real noun-phrase extraction needs a tagger this script doesn't
#        have), and the most reusable kind of trigger since it names the *technology*, not
#        this integration. A handful of this repo's own meta mentions (CLAUDE.md, README.md)
#        are filtered out — they carry no pattern-specific signal.
#     2. up to 4 capitalised multi-word phrases (title-case runs) from the same text —
#        weaker signal than a bare acronym, so capped to leave room for the groups below.
#     3. tokens mined from the *added* lines of the scoped commits' diff — CLI flags
#        (--foo), slash wire-paths (session/request_permission), SCREAMING_SNAKE env/const
#        names, snake_case identifiers, and bare config/doc filenames (foo.json, foo.md) —
#        ranked by how often each appears, most-frequent first. These are the literal terms
#        a developer who built this again would type or grep for.
#     4. the last path segment of each signature's directory, and its parent segment when
#        that parent isn't itself a generic src/main/java path element (e.g. "acp" and its
#        parent "agent").
#     5. mixed-case capitalised words (JetBrains, Zed) from the same text as (1) — a
#        product/brand name: lower priority than an acronym since it names this
#        integration, not a reusable one.
#     6. the `want:` sentence's own words, lowercased, split on non-letters, kept when
#        longer than 3 letters and not in a short stopword list.
#     7. the issue title, same lowercase/length/stopword treatment as (6).
#   skeleton     = each eligible file's content at the range's head, with quoted literals
#                  that look like an absolute path, a URL, or a hostname rewritten to
#                  <placeholder>, and "// <-- varies per use" appended to any
#                  `private static final <TYPE> <NAME> = <literal>;` declaration the diff
#                  actually touched. Deliberately narrow: a wire-protocol token like
#                  "session/request_permission" is not a literal that "varies per use" and
#                  is left alone, same as the hand-built seed does in its own Javadoc.
#   PATTERN.md   = the issue's "## Goal" section verbatim as "when it applies", its
#                  "## Out of scope" section as "when it does not", and the scoped commits'
#                  bodies (in range order) as "the decision and why" — TODO(owner) wherever
#                  a section comes out empty because the issue didn't have it.
#   check.sh     = the credential grep below, a `javac -proc:none` parse of the skeleton's
#                  *.java files against the Gradle module cache when one is present (a
#                  visible SKIP, never a silent pass, when it is not), and one literal
#                  TODO(owner) line for the behavioural check only a person can name.
#
# Exit codes: 0 draft written (or printed, under --dry-run); 1 the clean-copy check.sh run
# failed; 2 a credential-shaped value was found in the candidate skeleton (nothing written
# under patterns/ or requests/ either way); 3 the output directory already holds a pattern
# and --replace was not given (an existing pattern is changed only by a deliberate PR,
# never overwritten by a re-run); 64 bad arguments, including an --out outside this
# repository.
set -eu

usage() {
    cat <<'USAGE' >&2
Usage: promote-pattern.sh --id <id> --range <base>..<head> --issue <n>
                           [--request <id>] [--dry-run] [--out <dir>] [--keep] [--replace]
USAGE
}

id=""
range=""
issue=""
request_id=""
out_dir=""
dry_run=0
keep=0
replace=0

while [ $# -gt 0 ]; do
    case "$1" in
        --id) id="$2"; shift 2 ;;
        --range) range="$2"; shift 2 ;;
        --issue) issue="$2"; shift 2 ;;
        --request) request_id="$2"; shift 2 ;;
        --out) out_dir="$2"; shift 2 ;;
        --dry-run) dry_run=1; shift ;;
        --keep) keep=1; shift ;;
        --replace) replace=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "promote-pattern.sh: unknown argument '$1'" >&2; usage; exit 64 ;;
    esac
done

if [ -z "$id" ] || [ -z "$range" ] || [ -z "$issue" ]; then
    echo "promote-pattern.sh: --id, --range and --issue are required" >&2
    usage
    exit 64
fi

case "$range" in
    *..*) ;;
    *) echo "promote-pattern.sh: --range must be <base>..<head>, got '$range'" >&2; exit 64 ;;
esac
base=${range%%..*}
head=${range#*..}

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

: "${out_dir:=patterns/$id}"

# --out must stay inside this repository: the draft is later removed and re-created with
# rm -rf, and that must never be able to reach a path the owner did not mean.
case "$out_dir" in
    /*) out_abs="$out_dir" ;;
    *) out_abs="$repo_root/$out_dir" ;;
esac
out_abs=$(python3 -c 'import os,sys; print(os.path.normpath(sys.argv[1]))' "$out_abs")
case "$out_abs" in
    "$repo_root"/*) ;;
    *) echo "promote-pattern.sh: --out must be inside the repository ($repo_root), got '$out_dir'" >&2; exit 64 ;;
esac
if [ "$out_abs" = "$repo_root" ] || [ "$out_abs" = "$repo_root/patterns" ]; then
    echo "promote-pattern.sh: --out must name one pattern directory, not '$out_dir'" >&2
    exit 64
fi

# An existing pattern is never overwritten by a re-run. Refuse up front, before any work and
# before the dry-run/real split, so a typo in --id cannot cost the hand-built seed.
if [ "$dry_run" -eq 0 ] && [ "$replace" -eq 0 ] && [ -e "$out_abs" ]; then
    echo "promote-pattern.sh: $out_dir already exists; pick a new --id, or pass --replace to overwrite it deliberately (nothing written)" >&2
    exit 3
fi

if [ -z "$request_id" ]; then
    # "retrieve before reason" (#76): a request card for this issue may already exist under
    # some other id than "issue-<n>" — requests/acp-in-ide.yaml's own `issue: 59` is exactly
    # this case for the seed pattern. Reuse it rather than drafting a second card.
    existing_request=$(python3 - "$issue" <<'PYEOF'
import glob
import sys

import yaml

issue = sys.argv[1]
for path in sorted(glob.glob("requests/*.yaml")):
    try:
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    except yaml.YAMLError:
        continue
    if str(data.get("issue")) == issue:
        print(path)
        break
PYEOF
    )
    if [ -n "$existing_request" ]; then
        request_path="$existing_request"
        request_id=$(basename "$existing_request" .yaml)
    else
        request_id=$(printf '%s' "issue-$issue" | tr -cs 'a-zA-Z0-9' '-' | tr 'A-Z' 'a-z')
        request_path="requests/$request_id.yaml"
    fi
else
    request_path="requests/$request_id.yaml"
fi
request_is_new=1
[ -f "$request_path" ] && request_is_new=0

work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

mkdir -p "$work/draft"

echo "[promote-pattern] id=$id range=$base..$head issue=$issue request=$request_id (new=$request_is_new) out=$out_dir dry-run=$dry_run"

# --- gather: issue data (gh is already on PATH in this repo's build and CI images; a
# transient failure here is reported, not silently swallowed into an empty draft) ---
issue_json="$work/issue.json"
if ! gh issue view "$issue" --repo enrichmeai/penstock --json title,body,labels >"$issue_json" 2>"$work/gh.err"; then
    echo "promote-pattern.sh: could not fetch issue #$issue via gh (see below); continuing with an empty issue" >&2
    cat "$work/gh.err" >&2
    printf '{"title":"","body":"","labels":[]}' >"$issue_json"
fi

# --- gather: the commits in --range that reference this issue; fall back to the whole range ---
commits_file="$work/commits.txt"
# "#59" must not match inside "#59" 's own extension — "#592" or "#59" immediately
# followed by another digit. git's --grep has no \b in plain -E, so the guard is a
# trailing non-digit class instead (a commit message from `%B` always ends in a
# newline, which satisfies it even when the reference is the very last thing written).
git log --format='%H' "$base..$head" --grep="#$issue"'[^0-9]' -E >"$commits_file" || true
scoped=1
if [ ! -s "$commits_file" ]; then
    scoped=0
    git log --format='%H' "$base..$head" >"$commits_file"
    echo "[promote-pattern] no commit in $base..$head mentions #$issue; using the whole range" >&2
fi

# --- gather: name-status and commit bodies for exactly those commits, in range order ---
name_status_file="$work/name-status.txt"
bodies_file="$work/bodies.txt"
: >"$name_status_file"
: >"$bodies_file"
while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    git show --no-patch --format='%B' "$sha" >>"$bodies_file"
    printf '\n---PP-COMMIT-END---\n' >>"$bodies_file"
    git diff-tree --no-commit-id --name-status -r "$sha" >>"$name_status_file"
done <"$commits_file"

# --- gather: added lines across those same commits, for trigger mining ---
added_lines_file="$work/added-lines.txt"
: >"$added_lines_file"
while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    git show --format='' "$sha" >>"$added_lines_file"
done <"$commits_file"

if [ "$request_is_new" -eq 1 ]; then
    want_source=""
else
    want_source="$request_path"
fi

PP_REPO_ROOT="$repo_root" \
PP_WORK="$work" \
PP_ID="$id" \
PP_BASE="$base" \
PP_HEAD="$head" \
PP_ISSUE="$issue" \
PP_REQUEST_ID="$request_id" \
PP_REQUEST_PATH="$request_path" \
PP_REQUEST_IS_NEW="$request_is_new" \
PP_WANT_SOURCE="$want_source" \
PP_ISSUE_JSON="$issue_json" \
PP_COMMITS_FILE="$commits_file" \
PP_NAME_STATUS_FILE="$name_status_file" \
PP_BODIES_FILE="$bodies_file" \
PP_ADDED_LINES_FILE="$added_lines_file" \
PP_SCOPED="$scoped" \
PP_DRAFT_DIR="$work/draft" \
python3 "$repo_root/scripts/lib/promote_pattern_generate.py"

# --- credential refusal: before anything real is written, grep the candidate skeleton ---
# Same shapes as patterns/stdio-json-rpc-agent/check.sh's own credential grep.
if [ -d "$work/draft/skeleton" ]; then
    cred_hits=$(sh "$repo_root/scripts/lib/credential-grep.sh" "$work/draft/skeleton") && cred_status=0 || cred_status=$?
    case "${cred_status:-0}" in
        0) ;;
        2)
            echo "FAIL: credential-shaped value found in candidate skeleton; nothing written" >&2
            printf '%s\n' "$cred_hits" >&2
            exit 2
            ;;
        *)
            echo "promote-pattern.sh: credential-grep.sh exited $cred_status unexpectedly" >&2
            exit "$cred_status"
            ;;
    esac
fi
echo "[promote-pattern] credential grep: no credential-shaped value in candidate skeleton"

# --- clean-copy run: the draft's own check.sh must pass away from this working tree ---
clean_copy=$(mktemp -d)
cp -R "$work/draft/." "$clean_copy/"
echo "[promote-pattern] running check.sh in a clean copy ($clean_copy)"
check_status=0
( cd "$clean_copy" && sh check.sh ) || check_status=$?

if [ "$check_status" -ne 0 ]; then
    echo "FAIL: generated check.sh exited $check_status in the clean copy" >&2
    if [ "$keep" -eq 1 ]; then
        mkdir -p "$(dirname "$out_abs")"
        rm -rf "$out_abs"
        cp -R "$work/draft" "$out_abs"
        echo "promote-pattern.sh: --keep set, left the failing draft at $out_dir" >&2
    fi
    rm -rf "$clean_copy"
    exit 1
fi
rm -rf "$clean_copy"
echo "PASS: check.sh passed in the clean copy"

if [ "$dry_run" -eq 1 ]; then
    echo "--- manifest.yaml (dry-run; nothing written) ---"
    cat "$work/draft/manifest.yaml"
    echo "--- bar diagnostics ---"
    cat "$work/draft/.bar-diagnostics.txt" 2>/dev/null || true
    exit 0
fi

mkdir -p "$(dirname "$out_abs")"
rm -rf "$out_abs"
cp -R "$work/draft" "$out_abs"
rm -f "$out_abs/.bar-diagnostics.txt"

if [ "$request_is_new" -eq 1 ] && [ -f "$work/draft-request.yaml" ]; then
    mkdir -p "$(dirname "$request_path")"
    cp "$work/draft-request.yaml" "$request_path"
    echo "[promote-pattern] wrote draft request card $request_path"
fi

echo "--- manifest.yaml ---"
cat "$out_dir/manifest.yaml"
echo "--- bar diagnostics ---"
cat "$work/draft/.bar-diagnostics.txt" 2>/dev/null || true
echo "[promote-pattern] draft written to $out_dir"
