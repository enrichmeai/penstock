#!/usr/bin/env python3
"""Shape an episode draft (issue #86) from the evidence write-episode.sh gathered.

Reads a work directory containing (each optional, absent when the gather step had nothing):
  meta.json      {"id","date","project","issue_ref","pr_ref"}
  issue.json     the GitHub issue (title, body)
  pr.json        the GitHub pull request (title, body, merge_commit_sha, merged_at)
  pr_files.json  the pull request's files ([{filename}])
  commits.txt    the scoped commits, as "sha<TAB>subject<NUL>body" records
  audit.json     the session's audit events from GET /api/sessions/{id}/audit (#88):
                 pattern.loaded → patterns, fact.loaded + episode.loaded → recalled
Writes the draft YAML to stdout. No network, no git: the shell wrapper owns those.

Markers, case-insensitive, at the start of a line in a commit body or the PR body:
  Decided: <what> -- <why>      Refused: <what> -- <why>      Learned: <text>   fact: <text>
  Open: <text>
A PR body's "## Known limits" bullets become refused entries (what = the bullet, why = "known
limit stated in the PR"), since that section is where the builder writes what it chose not to do.
"""
import datetime
import json
import os
import re
import sys

import yaml

work = sys.argv[1]


def load(name):
    path = os.path.join(work, name)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


meta = load("meta.json") or {}
issue = load("issue.json") or {}
pr = load("pr.json") or {}
pr_files = load("pr_files.json") or []
audit = load("audit.json") or []

MARK = re.compile(r"^\s*(decided|refused|learned|fact|open)\s*:\s*(.+?)\s*$", re.I)
SPLIT_WHY = re.compile(r"\s+(?:--|—|;\s*why:)\s+", re.I)


def split_what_why(text):
    parts = SPLIT_WHY.split(text, maxsplit=1)
    if len(parts) == 2:
        return parts[0].strip(), parts[1].strip()
    return text.strip(), "<fill in: why>"


decided, refused, learned, open_loops = [], [], [], []


def harvest(text):
    for line in (text or "").splitlines():
        m = MARK.match(line)
        if not m:
            continue
        kind, body = m.group(1).lower(), m.group(2)
        if kind == "decided":
            what, why = split_what_why(body)
            decided.append({"what": what, "why": why})
        elif kind == "refused":
            what, why = split_what_why(body)
            refused.append({"what": what, "why": why})
        elif kind in ("learned", "fact"):
            learned.append(body)
        elif kind == "open":
            open_loops.append(body)


commits = []
commits_path = os.path.join(work, "commits.txt")
if os.path.isfile(commits_path):
    with open(commits_path, encoding="utf-8") as f:
        raw = f.read()
    for rec in raw.split("\0"):
        if not rec.strip():
            continue
        head, _, body = rec.partition("\n")
        sha, _, subject = head.partition("\t")
        commits.append({"sha": sha.strip()[:7], "subject": subject.strip()})
        harvest(body)

harvest(pr.get("body"))


def known_limits(body):
    out, inside = [], False
    for line in (body or "").splitlines():
        if line.startswith("## "):
            inside = line.strip().lower() == "## known limits"
            continue
        if inside and line.lstrip().startswith(("- ", "* ")):
            out.append(line.lstrip()[2:].strip())
    return out


for bullet in known_limits(pr.get("body")):
    refused.append({"what": bullet, "why": "known limit stated in the PR"})

asked = (issue.get("title") or pr.get("title") or "<fill in: what was asked, in the owner's words>").strip()

built = []
if meta.get("pr_ref"):
    entry = {"pr": meta["pr_ref"]}
    if pr.get("merge_commit_sha"):
        entry["merged"] = pr["merge_commit_sha"][:7]
    files = [f.get("filename") for f in pr_files if f.get("filename")]
    if files:
        entry["files"] = files
    built.append(entry)
elif commits:
    built.append({"pr": "<fill in: owner/repo#n>", "files": []})

def repo_memory():
    """memory.yaml at the repository root (#98): its project and visibility, or None when absent."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "memory.yaml")
    try:
        with open(path, encoding="utf-8") as f:
            mem = yaml.safe_load(f)
    except Exception:  # absent or malformed: no boundary is declared, so drafts stay private
        return None
    return mem if isinstance(mem, dict) else None


def default_visibility(project):
    # An episode of this repository's own project takes the repository's visibility; any other
    # project's, or a repository without memory.yaml, is private until the owner widens it.
    mem = repo_memory()
    if mem and project == mem.get("project") and mem.get("visibility") in ("private", "shareable", "public"):
        return mem["visibility"]
    return "private"


draft = {
    "id": meta["id"],
    "format": 1,
    "visibility": default_visibility(meta["project"]),
    # a real date, so the YAML carries 2026-10-03 unquoted and check-cards.sh compares it as text
    "date": datetime.date.fromisoformat(meta["date"]),
    "project": meta["project"],
    "asked": asked,
}
if meta.get("issue_ref"):
    draft["issue"] = meta["issue_ref"]
draft["built"] = built
draft["decided"] = decided
draft["refused"] = refused
draft["learned"] = learned
draft["open"] = open_loops
# What the session recalled before it reasoned (#88). Ids are de-duplicated in first-seen order;
# a fact recalled is not a fact learned, so nothing here touches `learned`.
recalled_patterns, recalled_cards = [], []
for ev in audit if isinstance(audit, list) else []:
    if not isinstance(ev, dict):
        continue
    detail = ev.get("detail") if isinstance(ev.get("detail"), dict) else {}
    kind = ev.get("eventType")
    if kind == "pattern.loaded" and detail.get("patternId"):
        ref = str(detail["patternId"])
        if ref not in recalled_patterns:
            recalled_patterns.append(ref)
    elif kind == "fact.loaded" and detail.get("factId"):
        ref = "fact:" + str(detail["factId"])
        if ref not in recalled_cards:
            recalled_cards.append(ref)
    elif kind == "episode.loaded" and detail.get("episodeId"):
        ref = "episode:" + str(detail["episodeId"])
        if ref not in recalled_cards:
            recalled_cards.append(ref)
draft["patterns"] = recalled_patterns
if isinstance(audit, list) and audit:
    draft["recalled"] = recalled_cards
if commits:
    draft["commits"] = [f"{c['sha']} {c['subject']}" for c in commits]

print("# Drafted by scripts/write-episode.sh from the task's own evidence; edit before committing.")
print("# Empty lists and <fill in> placeholders are yours to complete; a learned line is a fact by")
print("# reference (an account, a secret's *name*, an id), never a value.")
yaml.safe_dump(draft, sys.stdout, sort_keys=False, allow_unicode=True, width=100, default_flow_style=False)
