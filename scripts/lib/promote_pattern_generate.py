#!/usr/bin/env python3
"""Generates a patterns/<id>/ draft into $PP_DRAFT_DIR, plus a requests/<id>.yaml draft at
$PP_WORK/draft-request.yaml when the request card is new.

Called only from scripts/promote-pattern.sh, which documents the derivation rules at the
top of that file and sets every PP_* environment variable this script reads. Not meant to
be invoked directly.
"""
import json
import os
import re
import subprocess
import sys
from datetime import date

import yaml

ROOT = os.environ["PP_REPO_ROOT"]
WORK = os.environ["PP_WORK"]
DRAFT = os.environ["PP_DRAFT_DIR"]
PATTERN_ID = os.environ["PP_ID"]
HEAD = os.environ["PP_HEAD"]
ISSUE = os.environ["PP_ISSUE"]
REQUEST_ID = os.environ["PP_REQUEST_ID"]
REQUEST_PATH = os.environ["PP_REQUEST_PATH"]
REQUEST_IS_NEW = os.environ["PP_REQUEST_IS_NEW"] == "1"
WANT_SOURCE = os.environ.get("PP_WANT_SOURCE", "")
SCOPED = os.environ["PP_SCOPED"] == "1"

STOPWORDS = {
    "with", "over", "behind", "from", "their", "these", "those", "there", "here", "your",
    "about", "after", "before", "under", "above", "below", "between", "during", "through",
    "without", "within", "onto", "upon", "same", "such", "each", "both", "either", "neither",
    "while", "because", "since", "until", "unless", "that", "this", "then", "than", "when",
    "where", "what", "which", "have", "having", "will", "shall", "must", "should", "could",
    "would", "into", "also", "more", "most", "only", "just", "other", "some", "been", "being",
    "does", "doing", "selectable",
}


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def run_git(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    )


# ---------------------------------------------------------------------------
# Issue
# ---------------------------------------------------------------------------
issue_data = json.loads(read(os.environ["PP_ISSUE_JSON"]))
issue_title = issue_data.get("title") or ""
issue_body = issue_data.get("body") or ""
issue_labels = [l.get("name", "") for l in issue_data.get("labels") or []]


def extract_section(body, header):
    pattern = re.compile(
        r"^##\s+" + re.escape(header) + r"\s*\n(.*?)(?=^##\s+|\Z)",
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )
    m = pattern.search(body)
    return m.group(1).strip() if m else ""


goal_section = extract_section(issue_body, "Goal")
out_of_scope_section = extract_section(issue_body, "Out of scope")
why_section = extract_section(issue_body, "Why now") or extract_section(issue_body, "Why")

# ---------------------------------------------------------------------------
# Request card / want sentence
# ---------------------------------------------------------------------------
if WANT_SOURCE and os.path.isfile(WANT_SOURCE):
    want_card = yaml.safe_load(read(WANT_SOURCE)) or {}
    want_sentence = want_card.get("want") or issue_title
else:
    first_sentence = re.split(r"(?<=[.!?])\s+", goal_section.strip())[0] if goal_section.strip() else ""
    want_sentence = first_sentence or issue_title

# ---------------------------------------------------------------------------
# Scoped commits: name-status + bodies
# ---------------------------------------------------------------------------
name_status_lines = [
    l for l in read(os.environ["PP_NAME_STATUS_FILE"]).splitlines() if l.strip()
]

TEST_SEGMENT = re.compile(r"(^|/)test(/|$)")
# Not every test file lives under a "test/" directory — this repo's own convention names
# driver scripts "test-*.sh" right next to the thing they test (test-hooks.sh,
# test-promote-pattern.sh itself). Catch the naming convention too, not just the directory.
TEST_BASENAME = re.compile(r"(^|/)test[-_][^/]*$")
META_ROOT_FILES = {
    "README.md", "ROADMAP.md", "CLAUDE.md", "build.gradle", "settings.gradle",
    "gradle.properties",
}


def is_excluded(path):
    if TEST_SEGMENT.search(path) or TEST_BASENAME.search(path):
        return True
    if path.startswith(".github/") or path.startswith(".claude/"):
        return True
    if "/" not in path and path in META_ROOT_FILES:
        return True
    return False


added_top_level = []
all_changed = {}  # path -> status ("A" or "M")
for line in name_status_lines:
    parts = line.split("\t")
    status = parts[0]
    if status.startswith("R"):
        # rename: old path, new path
        path = parts[-1]
        status = "M"
    else:
        path = parts[-1]
    if status not in ("A", "M"):
        continue
    if status == "A" and "/" not in path:
        added_top_level.append(path)
    if is_excluded(path):
        continue
    # keep the most "interesting" status if a file shows up more than once
    # across the scoped commits (added once, modified again later in range).
    if path not in all_changed or status == "A":
        all_changed[path] = status

# Signatures and the skeleton favour genuinely *new* files over merely-touched ones: a task
# that adds a package (e.g. acp/) also routinely edits a handful of existing, heavily-shared
# files to wire it in (AgentApplication, ToolRegistry, AgentProperties, application.yml) —
# those are integration noise, not "the pattern," and including them both pulls unrelated
# directories into signatures and crowds the 12-trigger cap with their directory names
# before a single topical word gets a chance. Fall back to every changed file only when a
# task added nothing new at all (a modify-only task still needs a signature/skeleton).
added_only = {p: s for p, s in all_changed.items() if s == "A"}
eligible = added_only if added_only else all_changed
eligible_paths = sorted(eligible)
head_sha = run_git("rev-parse", HEAD + "^{commit}").stdout.strip()

# ---------------------------------------------------------------------------
# Signatures
# ---------------------------------------------------------------------------
sig_dirs = sorted({os.path.dirname(p) for p in eligible_paths if os.path.dirname(p)})
signatures = [d + "/**" for d in sig_dirs] + sorted(set(added_top_level))
if not signatures:
    signatures = ["TODO(owner): no eligible changed file produced a signature"]

# ---------------------------------------------------------------------------
# Triggers
# ---------------------------------------------------------------------------
FLAG_RE = re.compile(r"(?<![\w-])--[a-zA-Z][a-zA-Z-]{1,30}\b")
WIRE_RE = re.compile(r"\b[a-z][a-z]*(?:/[a-z][a-z_]*){1,4}\b")
SCREAMING_RE = re.compile(r"\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b")
SNAKE_RE = re.compile(r"\b[a-z][a-z]*(?:_[a-z]+)+\b")
FILENAME_RE = re.compile(r"\b[a-z][a-z0-9-]*\.(?:json|md|yaml|yml|xml)\b")
MINE_PATTERNS = [FLAG_RE, WIRE_RE, SCREAMING_RE, SNAKE_RE, FILENAME_RE]

mined_freq = {}
for raw_line in read(os.environ["PP_ADDED_LINES_FILE"]).splitlines():
    if not raw_line.startswith("+") or raw_line.startswith("+++"):
        continue
    content = raw_line[1:]
    for pattern in MINE_PATTERNS:
        for match in pattern.findall(content):
            key = match.lower()
            mined_freq[key] = mined_freq.get(key, 0) + 1

mined_sorted = sorted(mined_freq.items(), key=lambda kv: (-kv[1], kv[0]))
mined_tokens = [tok for tok, _ in mined_sorted[:8]]

# The deepest directory segment ("acp") and its immediate parent ("agent") when that parent
# is itself meaningful (not a generic src/main/java path element) — a new package's own name
# and the module it lives in are both things a developer would search by.
GENERIC_PATH_SEGMENTS = {"src", "main", "test", "java", "resources", "com", "example", "docs"}


def basenames_for(dir_path):
    segments = [s for s in dir_path.split("/") if s]
    names = []
    if segments:
        names.append(segments[-1])
        if len(segments) > 1 and segments[-2] not in GENERIC_PATH_SEGMENTS:
            names.append(segments[-2])
    return names


dir_basenames = []
for d in sig_dirs:
    for name in basenames_for(d):
        if name not in dir_basenames:
            dir_basenames.append(name)

PHRASE_RE = re.compile(r"\b[A-Z][a-zA-Z0-9]*(?:[ \t]+[A-Z][a-zA-Z0-9]*){1,3}\b")
WORD_RE = re.compile(r"\b[A-Z][a-zA-Z0-9]{2,}\b")

# A capitalized word is only interesting here when it is an acronym (all-caps: ACP, IDE,
# JSON, RPC) or a genuine proper noun — not when it is merely the first word of a sentence
# or a markdown list item ("The", "Before", "Add", "Click"), which this shape-only regex
# cannot otherwise tell apart from a real one.
SENTENCE_STARTERS = {
    "the", "this", "that", "these", "those", "before", "after", "when", "once", "add",
    "click", "open", "your", "here", "there", "what", "which", "who", "for", "and", "but",
    "or", "note", "see", "then", "now", "set", "use", "if", "in", "on", "as", "a", "an",
    "to", "of", "with", "from", "it", "its", "so", "do", "does", "did", "run", "built",
    "part", "closes", "verified", "source", "zed's", "you", "download", "penstock",
    "claude", "readme",  # this repo's own meta files, mentioned in passing — not the pattern
}


def capitalized_acronyms(text):
    """All-caps tokens (ACP, IDE, JSON, RPC) — a technology/protocol name, not a brand."""
    seen = []
    for w in WORD_RE.findall(text):
        lw = w.lower()
        if w.isupper() and lw not in SENTENCE_STARTERS and lw not in seen:
            seen.append(lw)
    return seen


def capitalized_phrases(text):
    seen = []
    for p in PHRASE_RE.findall(text):
        lp = p.lower()
        if p.split()[0].lower() not in SENTENCE_STARTERS and lp not in seen:
            seen.append(lp)
    return seen


def proper_noun_words(text):
    """Mixed-case capitalized words (JetBrains, Zed, Cistern) — product/brand names: lower
    priority than an acronym, since they identify *this* integration, not a reusable one."""
    seen = []
    for w in WORD_RE.findall(text):
        lw = w.lower()
        if not w.isupper() and lw not in SENTENCE_STARTERS and lw not in seen:
            seen.append(lw)
    return seen


def filtered_tokens(text):
    tokens = re.findall(r"[a-zA-Z]+", text.lower())
    out = []
    for t in tokens:
        if len(t) > 3 and t not in STOPWORDS and t not in out:
            out.append(t)
    return out


# Markdown among the eligible files (typically docs/*.md) is prose, not code — it is where a
# proper noun or an acronym the rest of the mining can't shape-match (ACP, JSON, RPC, IDE)
# actually gets spelled out. Mined ahead of the generic file/directory-name groups below so a
# handful of docs/readme navigation phrases don't crowd out domain terms when both are present.
doc_text = ""
for p in eligible_paths:
    if p.lower().endswith(".md"):
        show = run_git("show", f"{head_sha or HEAD}:{p}")
        if show.returncode == 0:
            doc_text += "\n" + show.stdout
doc_text += "\n" + want_sentence + "\n" + issue_title

triggers = []
for group in (
    capitalized_acronyms(doc_text),
    capitalized_phrases(doc_text)[:4],
    mined_tokens,
    dir_basenames,
    proper_noun_words(doc_text),
    filtered_tokens(want_sentence),
    filtered_tokens(issue_title),
):
    for item in group:
        if item and item not in triggers:
            triggers.append(item)
triggers = triggers[:12]
if not triggers:
    triggers = ["TODO(owner): no trigger could be mined"]

# ---------------------------------------------------------------------------
# verified-against
# ---------------------------------------------------------------------------
tags_at_head = run_git("tag", "--points-at", head_sha or HEAD).stdout.split()


def tag_sort_key(tag):
    nums = re.findall(r"\d+", tag)
    return [int(n) for n in nums] if nums else [0]


verified_tag = max(tags_at_head, key=tag_sort_key) if tags_at_head else None
today = date.today().isoformat()

# ---------------------------------------------------------------------------
# Skeleton
# ---------------------------------------------------------------------------
PLACEHOLDER = "<placeholder>"
ABS_PATH_LITERAL = re.compile(r'"(/[^"\n]+)"')
URL_LITERAL = re.compile(r'"((?:https?|ftp)://[^"\n]+)"')
HOST_LITERAL = re.compile(r'"([a-zA-Z0-9-]+\.(?:com|org|io|dev|net))([/"][^"\n]*)?"')
CONSTANT_DECL = re.compile(
    r"^(\s*(?:private|public|protected)?\s*static\s+final\s+\S+\s+[A-Z][A-Z0-9_]*\s*=.*;)\s*$"
)

skeleton_dir = os.path.join(DRAFT, "skeleton")
written_skeleton_files = []
for path in eligible_paths:
    show = run_git("show", f"{head_sha or HEAD}:{path}")
    if show.returncode != 0:
        continue
    content = show.stdout
    if "\x00" in content:
        continue  # binary; skip rather than corrupt it
    content = URL_LITERAL.sub(lambda m: m.group(0).replace(m.group(1), PLACEHOLDER), content)
    content = ABS_PATH_LITERAL.sub(lambda m: m.group(0).replace(m.group(1), PLACEHOLDER), content)
    content = HOST_LITERAL.sub(
        lambda m: m.group(0).replace(m.group(1), PLACEHOLDER), content
    )
    lines = content.splitlines()
    for i, line in enumerate(lines):
        if CONSTANT_DECL.match(line) and "varies per use" not in line:
            lines[i] = line + "  // <-- varies per use"
    content = "\n".join(lines) + ("\n" if content.endswith("\n") else "")

    dest = os.path.join(skeleton_dir, path)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8") as f:
        f.write(content)
    written_skeleton_files.append(os.path.join("skeleton", path))

os.makedirs(skeleton_dir, exist_ok=True)

# ---------------------------------------------------------------------------
# PATTERN.md
# ---------------------------------------------------------------------------
commit_bodies_raw = read(os.environ["PP_BODIES_FILE"])
commit_bodies = [
    b.strip() for b in commit_bodies_raw.split("---PP-COMMIT-END---") if b.strip()
]

when_it_applies = goal_section or "TODO(owner): the issue had no \"## Goal\" section."
when_it_does_not = out_of_scope_section or "TODO(owner): the issue had no \"## Out of scope\" section."
decision_and_why = (
    "\n\n".join(commit_bodies) if commit_bodies else "TODO(owner): no scoped commit had a body."
)
what_varies = (
    "- " + "\n- ".join(f"`{PLACEHOLDER}` substitutions in {p}" for p in written_skeleton_files)
    if written_skeleton_files
    else "TODO(owner): list what a user of this pattern must supply."
) + "\n\nTODO(owner): the mechanical draft cannot tell which of the above are load-bearing inputs versus incidental; prune and name them."

pattern_md = f"""# {PATTERN_ID}

Draft generated by `scripts/promote-pattern.sh` from issue #{ISSUE} and {HEAD}. Review every
TODO(owner) before merging — this file was not written by a person.

## When it applies

{when_it_applies}

## When it does not

{when_it_does_not}

## The decision and why

{decision_and_why}

## What varies per use

{what_varies}
"""

with open(os.path.join(DRAFT, "PATTERN.md"), "w", encoding="utf-8") as f:
    f.write(pattern_md)

# ---------------------------------------------------------------------------
# manifest.yaml
# ---------------------------------------------------------------------------
def repo_visibility():
    """#98: a drafted pattern takes memory.yaml's visibility; private when none is declared."""
    try:
        with open(os.path.join(ROOT, "memory.yaml"), encoding="utf-8") as f:
            mem = yaml.safe_load(f)
    except Exception:
        return "private"
    vis = mem.get("visibility") if isinstance(mem, dict) else None
    return vis if vis in ("private", "shareable", "public") else "private"


manifest = {
    "id": PATTERN_ID,
    "format": 1,
    "visibility": repo_visibility(),
    "version": 1,
    "triggers": triggers,
    "signatures": signatures,
    "inputs": ["TODO(owner): what the user must supply — the mechanical draft cannot infer this"],
    "verified-against": {"tag": verified_tag, "date": today},
    "supersedes": None,
}
with open(os.path.join(DRAFT, "manifest.yaml"), "w", encoding="utf-8") as f:
    yaml.safe_dump(manifest, f, sort_keys=False, default_flow_style=False, allow_unicode=True)

if verified_tag is None:
    print(
        f"[promote-pattern] {HEAD} is not on a tagged commit; verified-against.tag is null — "
        "the owner must set it before merge",
        file=sys.stderr,
    )

# ---------------------------------------------------------------------------
# check.sh
# ---------------------------------------------------------------------------
check_sh = """#!/bin/sh
# Generated by scripts/promote-pattern.sh. Same three checks as the hand-built seed
# (patterns/stdio-json-rpc-agent/check.sh), minus the one only a person can name below.
set -eu
cd "$(dirname "$0")"

status=0

echo "[credential-grep] scanning skeleton/ for credential-shaped values"
cred_hits=$(mktemp)
grep -rEn '(sk-[A-Za-z0-9]{10,}|ghp_[A-Za-z0-9]{10,}|AKIA[0-9A-Z]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' skeleton/ >>"$cred_hits" 2>/dev/null || true
grep -rEni '(password|token|secret)[[:space:]]*[:=][[:space:]]*"[^"[:space:]]{6,}"' skeleton/ >>"$cred_hits" 2>/dev/null || true
grep -rEni --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.xml' \
    --include='*.md' --include='*.json' --include='*.conf' \
    '(password|token|secret)[[:space:]]*[:=][[:space:]]*[^<$"{[:space:]]' skeleton/ >>"$cred_hits" 2>/dev/null || true
if [ -s "$cred_hits" ]; then
    echo "FAIL: skeleton/ contains a credential-shaped value"
    cat "$cred_hits"
    status=1
else
    echo "PASS: no credential-shaped value in skeleton/"
fi
rm -f "$cred_hits"

echo "[javac] parsing skeleton/**/*.java"
java_files=$(find skeleton -name '*.java' 2>/dev/null)
if [ -z "$java_files" ]; then
    echo "SKIP (not run): no .java files in skeleton/"
elif ! command -v javac >/dev/null 2>&1; then
    echo "SKIP (not run): no javac on PATH"
else
    outdir=$(mktemp -d)
    trap 'rm -rf "$outdir"' EXIT
    if javac -proc:none -d "$outdir" $java_files 2>"$outdir/javac-plain.log"; then
        echo "PASS: skeleton/**/*.java parses with no external classpath"
    else
        cache_jars=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1" \\
            -type f -name '*.jar' 2>/dev/null | tr '\\n' ':')
        if [ -z "$cache_jars" ]; then
            echo "SKIP (not run): plain parse failed and no Gradle module cache found under \\${GRADLE_USER_HOME:-\\$HOME/.gradle} — run './gradlew build' first"
            cat "$outdir/javac-plain.log" >&2
        elif javac -proc:none -cp "$cache_jars" -d "$outdir" $java_files >"$outdir/javac-cached.log" 2>&1; then
            echo "PASS: skeleton/**/*.java parses against the cached Gradle dependencies"
        else
            # A mechanical skeleton pulled from a live codebase routinely still references
            # other classes of *this* project (com.example.*) that weren't captured — that's
            # expected coupling, not a broken draft, and is a person's call to trim or accept.
            # Only an unresolved *external* package (anything else) is treated as a real FAIL.
            unresolved_external=$(grep -oE '(package [a-zA-Z0-9_.]+ does not exist|location: package [a-zA-Z0-9_.]+)' \\
                "$outdir/javac-cached.log" 2>/dev/null | grep -vc 'com\\.example' || true)
            if grep -q 'com\\.example' "$outdir/javac-cached.log" 2>/dev/null && [ "${unresolved_external:-0}" -eq 0 ]; then
                echo "SKIP (not run): skeleton references other com.example.* classes this draft did not capture (expected for a mechanical extract) — a person should trim the skeleton to what's truly standalone, or confirm the coupling is intentional"
            else
                echo "FAIL: skeleton/**/*.java did not parse even against the cached Gradle dependencies"
                cat "$outdir/javac-cached.log"
                status=1
            fi
        fi
    fi
fi

echo "TODO(owner): add the behavioural check only a person can name for this pattern."

exit $status
"""
check_path = os.path.join(DRAFT, "check.sh")
with open(check_path, "w", encoding="utf-8") as f:
    f.write(check_sh)
os.chmod(check_path, 0o755)

# ---------------------------------------------------------------------------
# requests/<id>.yaml draft, written only when the request card is new
# ---------------------------------------------------------------------------
if REQUEST_IS_NEW:
    draft_request = {
        "id": REQUEST_ID,
        "want": want_sentence,
        "why": (re.split(r"(?<=[.!?])\s+", why_section.strip())[0] if why_section.strip() else
                "TODO(owner): fill in from the issue"),
        "done-when": ["TODO(owner): copy the issue's acceptance criteria"],
        "not": ["TODO(owner): copy the issue's \"Out of scope\" items that must stay false"],
        "references": [],
        "patterns": [PATTERN_ID],
        "outcome": "",
        "issue": int(ISSUE) if ISSUE.isdigit() else ISSUE,
    }
    with open(os.path.join(WORK, "draft-request.yaml"), "w", encoding="utf-8") as f:
        yaml.safe_dump(draft_request, f, sort_keys=False, default_flow_style=False, allow_unicode=True)

# ---------------------------------------------------------------------------
# Bar diagnostics (informational only — the skill decides, this just prints)
# ---------------------------------------------------------------------------
import fnmatch
import glob

has_label = "pattern-candidate" in issue_labels

other_signature_hit = None
for manifest_path in sorted(glob.glob(os.path.join(ROOT, "patterns", "*", "manifest.yaml"))):
    other_id = os.path.basename(os.path.dirname(manifest_path))
    if other_id == PATTERN_ID:
        continue
    try:
        other = yaml.safe_load(read(manifest_path)) or {}
    except yaml.YAMLError:
        continue
    for sig in other.get("signatures") or []:
        for path in eligible_paths:
            if fnmatch.fnmatch(path, sig):
                other_signature_hit = other_id
                break
        if other_signature_hit:
            break
    if other_signature_hit:
        break

scoped_shas = {l.strip() for l in read(os.environ["PP_COMMITS_FILE"]).splitlines() if l.strip()}
prior_hits = 0
for d in sig_dirs:
    log = run_git("log", "--format=%H", "--", d + "/")
    shas = {s for s in log.stdout.split() if s not in scoped_shas}
    if shas:
        prior_hits += 1
built_twice_pct = round(100 * prior_hits / len(sig_dirs)) if sig_dirs else 0

with open(os.path.join(DRAFT, ".bar-diagnostics.txt"), "w", encoding="utf-8") as f:
    f.write(f"issue #{ISSUE} has label 'pattern-candidate': {'yes' if has_label else 'no'}\n")
    f.write(
        f"changed files match an existing pattern's signatures: "
        f"{'yes (' + other_signature_hit + ')' if other_signature_hit else 'no'}\n"
    )
    f.write(
        f"built-twice test: {prior_hits}/{len(sig_dirs)} signature directories "
        f"({built_twice_pct}%) were touched by a commit outside this task's scoped set\n"
    )
    if SCOPED:
        f.write(f"commit scope: matched a commit referencing #{ISSUE}\n")
    else:
        f.write(f"commit scope: no commit in the range mentioned #{ISSUE}; used the whole --range\n")

print(f"[promote-pattern] triggers: {triggers}")
print(f"[promote-pattern] signatures: {signatures}")
print(f"[promote-pattern] eligible files: {len(eligible_paths)}, skeleton files written: {len(written_skeleton_files)}")
