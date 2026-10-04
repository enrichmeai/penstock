#!/usr/bin/env python3
"""Propose facts from episodes' learned lines (issue #87). Text rules only: no model, no embedding.

Usage: consolidate_generate.py <repo-root> [--since YYYY-MM-DD] [--memory-yaml <path>] [--estate]
  --memory-yaml  the memory.yaml that sets visibility (default <repo-root>/memory.yaml; a section of
                 a sectioned memory root passes the root's, #91)
  --estate       the estate section of a sectioned root: every fact is scoped `estate`, since the
                 checker requires that scope under estate/ (#91)
Prints a JSON list of proposals to stdout:
  {"action": "new"|"confirm"|"supersede", "episode": id, "learned": line, "learned_index": n,
   "fact": {...draft...}, "old": id-or-null}
A fact file without an id or a subject is reported on stderr by path and skipped.
The shell wrapper renders the table and, with --write, applies the proposals.
"""
import glob
import json
import os
import re
import sys

import yaml

root = sys.argv[1]
since = None
memory_yaml = os.path.join(root, "memory.yaml")
estate_section = False
args = sys.argv[2:]
while args:
    a = args.pop(0)
    if a == "--since":
        since = args.pop(0)
    elif a == "--memory-yaml":
        memory_yaml = args.pop(0)
    elif a == "--estate":
        estate_section = True


def load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


facts = {}
for p in sorted(glob.glob(os.path.join(root, "facts", "*.yaml"))):
    d = load(p)
    if not isinstance(d, dict) or not d.get("id") or not d.get("subject"):
        print(f"consolidate.sh: {os.path.relpath(p, root)} has no id or subject; skipped (check-cards.sh will fail it)", file=sys.stderr)
        continue
    facts[d["id"]] = d

cited = set()
for d in facts.values():
    for e in d.get("provenance") or []:
        if isinstance(e, dict) and e.get("episode") and e.get("learned"):
            cited.add((e["episode"], " ".join(str(e["learned"]).split())))

# Lines the owner reviewed and decided are not beliefs (events, one-off measurements, pending
# tasks) live in facts/rejected/lines.yaml, so a drop is recorded once and never proposed again.
rejected_path = os.path.join(root, "facts", "rejected", "lines.yaml")
if os.path.isfile(rejected_path):
    try:
        rej = load(rejected_path)
    except Exception as exc:
        print(f"consolidate.sh: facts/rejected/lines.yaml did not parse ({exc}); ignoring it (check-cards.sh will fail it)", file=sys.stderr)
        rej = {}
    for e in (rej.get("rejected") if isinstance(rej, dict) else None) or []:
        if isinstance(e, dict) and e.get("episode") and e.get("learned"):
            cited.add((str(e["episode"]), " ".join(str(e["learned"]).split())))

active_by_subject = {d["subject"]: d for d in facts.values() if d.get("status") != "superseded"}

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
SECRET_WORD = re.compile(r"\b(secret|password|token|credential|key)s?\b", re.I)
IDENT = re.compile(r"\b([a-z0-9]+(?:-[a-z0-9]+){2,}|https?://\S+|[a-z0-9.-]+\.(?:example|com|org|io|in|co)\b)", re.I)
RULE_WORD = re.compile(r"\b(never|always|must|only ever|cannot)\b", re.I)
STOP = {"the", "a", "an", "is", "are", "for", "of", "to", "in", "on", "as", "by", "and", "or",
        "with", "its", "it", "that", "this", "be", "was", "so", "at", "from", "into", "which", "who"}


def kind_of(text):
    if EMAIL.search(text):
        return "account"
    if SECRET_WORD.search(text):
        return "location-of-secret"
    if RULE_WORD.search(text):
        return "convention"
    if IDENT.search(text):
        return "identifier"
    return "decision"


# Anything that could be a value rather than a name stays out of ids, subjects and triggers:
# a path or filename is printed on refusal, so it must never carry the value it refuses.
VALUE_SHAPE = re.compile(r"[A-Za-z0-9+/_=-]{20,}|\b[0-9a-fA-F]{16,}\b")


def strip_values(text):
    return VALUE_SHAPE.sub(" ", text)


def kebab(s):
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return re.sub(r"-{2,}", "-", s)


def subject_of(kind, text):
    text = strip_values(text)
    domains = {m.group(0).split("@", 1)[1].lower() for m in EMAIL.finditer(text)}
    nouns = [m.group(0) for m in IDENT.finditer(text) if "@" not in m.group(0)]
    nouns = [n for n in nouns if not n.lower().startswith("http") and n.lower() not in domains]
    if nouns:
        head = kebab(nouns[0])[:48]
    else:
        words = [w for w in re.findall(r"[A-Za-z0-9][A-Za-z0-9.'-]*", text) if w.lower() not in STOP][:4]
        head = kebab(" ".join(words))[:48]
    return f"{kind}/{head or 'unnamed'}"


def fold(s):
    return " ".join(str(s).lower().split()).rstrip(".")


RANK = {"private": 0, "shareable": 1, "public": 2}


def repo_memory():
    try:
        mem = load(memory_yaml)
    except Exception:  # absent or malformed: no boundary is declared, so everything stays private
        return {}
    return mem if isinstance(mem, dict) else {}


def fact_visibility(scope, ep):
    """#98: the narrowest of (a) the scope default: this repository's own project takes the
    repository's visibility, estate-wide and other projects' facts are private; and (b) the source
    episode's visibility (or, when it has none, that episode's own default). A private episode's
    line never comes back as a public fact. The owner widens by hand."""
    mem = repo_memory()
    proj, vis = mem.get("project"), mem.get("visibility")
    known = vis in RANK
    by_scope = vis if proj and known and (scope == f"project:{proj}" or scope.endswith(f"/{proj}")) else "private"
    ep_vis = ep.get("visibility")
    if ep_vis not in RANK:
        ep_vis = vis if proj and known and ep.get("project") == proj else "private"
    return min(by_scope, ep_vis, key=RANK.get)


def project_scope(ep):
    if estate_section:
        return "estate"
    p = ep.get("project")
    return "estate" if p in (None, "estate") else f"project:{p}"


def triggers_of(text):
    text = strip_values(text)
    words = [w for w in re.findall(r"[A-Za-z0-9][A-Za-z0-9.-]{2,}", text) if w.lower() not in STOP]
    seen, out = set(), []
    for w in words:
        k = w.lower().rstrip(".")
        if k not in seen and len(out) < 6:
            seen.add(k); out.append(k)
    return out


proposals = []
for p in sorted(glob.glob(os.path.join(root, "episodes", "*.yaml"))):
    ep = load(p)
    if not isinstance(ep, dict) or not ep.get("id"):
        continue
    if since and str(ep.get("date")) < since:
        continue
    for index, line in enumerate(ep.get("learned") or [], start=1):
        if isinstance(line, dict):  # a {what:, why:} item: flatten its values, like check-cards' text_lines
            line = "; ".join(str(v) for v in line.values() if v is not None)
        elif isinstance(line, list):
            line = "; ".join(str(v) for v in line)
        text = " ".join(str(line).split())
        if not text:
            continue
        if (ep["id"], text) in cited:
            continue
        kind = kind_of(text)
        subject = subject_of(kind, text)
        draft = {
            "id": kebab(subject.split("/", 1)[1] + "-" + kind)[:60].strip("-"),
            "format": 1,
            "visibility": fact_visibility(project_scope(ep), ep),
            "statement": text[0].upper() + text[1:] + ("" if text.endswith(".") else "."),
            "kind": kind,
            "subject": subject,
            "scope": project_scope(ep),
            "status": "inferred",
            "confidence": 0.7,
            "provenance": [{"episode": ep["id"], "learned": text}],
            "first_seen": str(ep.get("date")),
            "last_confirmed": str(ep.get("date")),
            "supersedes": None,
            "superseded_by": None,
            "triggers": triggers_of(text),
        }
        old = active_by_subject.get(subject)
        if old is None:
            action = "new"
            # avoid id collisions with any existing fact
            base, n = draft["id"], 2
            while draft["id"] in facts:
                draft["id"] = f"{base}-{n}"; n += 1
            facts[draft["id"]] = draft
            active_by_subject[subject] = draft
        elif fold(old.get("statement")) == fold(draft["statement"]):
            action = "confirm"
            draft = {"id": old["id"], "last_confirmed": str(ep.get("date")),
                     "provenance_add": {"episode": ep["id"], "learned": text}}
            # #98: citing a narrower episode narrows the fact, so it is never wider than a source
            if old.get("visibility") in RANK:
                ep_vis = fact_visibility(project_scope(ep), ep) if ep.get("visibility") not in RANK else ep["visibility"]
                if RANK[ep_vis] < RANK[old["visibility"]]:
                    draft["visibility"] = ep_vis
        else:
            action = "supersede"
            draft["supersedes"] = old["id"]
            draft["first_seen"] = str(ep.get("date"))
            base, n = draft["id"], 2
            while draft["id"] in facts:
                draft["id"] = f"{base}-{n}"; n += 1
            facts[draft["id"]] = draft
            active_by_subject[subject] = draft
        proposals.append({"action": action, "episode": ep["id"], "learned": text, "learned_index": index,
                          "fact": draft, "old": old["id"] if old else None})

json.dump(proposals, sys.stdout, indent=1, default=str)
