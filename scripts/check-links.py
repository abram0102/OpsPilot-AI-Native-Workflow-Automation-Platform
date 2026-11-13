#!/usr/bin/env python3
"""Checks that every internal link in the repository's Markdown actually resolves.

T35's acceptance includes "links are real". That is the kind of claim that is true the day it is
written and quietly false three refactors later, so it is a check rather than a promise — wired
into `make lint` and therefore into CI.

Scope, and why it stops where it does:

- **Internal links only.** Relative paths and in-repo anchors. External URLs are not fetched: a
  link checker that makes network calls is slow, flaky when a site is briefly down, and turns an
  offline `make lint` into a failure. GitHub Actions links in the progress log are external by
  nature and are left to a human.
- **Anchors within a file are checked** against that file's own headings, because a link to
  `#section-that-was-renamed` is exactly as broken as a missing file and much harder to spot.
- **Image links count.** T35 embeds screenshots; a README showing a broken image is worse than one
  showing none.

Usage: python3 scripts/check-links.py   (from the repository root)
"""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

# Directories that are not ours to validate.
SKIP_DIRS = {
    ".git",
    "node_modules",
    ".next",
    "target",
    ".venv",
    ".terraform",
    "__pycache__",
    "test-results",
    "playwright-report",
}

# [text](target) — but not images' sibling syntax inside code fences, which are stripped first.
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^```|^~~~")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*$", re.M)


def slugify(heading: str) -> str:
    """GitHub's heading-anchor rules, close enough for our own documents.

    Lowercase, strip anything that is not alphanumeric/space/hyphen, spaces to hyphens. Inline
    code and emphasis markers are removed first because GitHub slugs the rendered text.
    """
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"[*_]{1,2}([^*_]*)[*_]{1,2}", r"\1", text)
    text = unicodedata.normalize("NFKD", text)
    text = text.lower()
    text = re.sub(r"[^\w\s-]", "", text)
    # One hyphen per whitespace character, not per run. GitHub does not collapse them, so
    # `## Drill 3 — Worker killed` anchors as `drill-3--worker-killed`: the em dash is removed
    # and the two spaces that surrounded it each become a hyphen. Collapsing here would reject
    # links that work and accept links that do not.
    return re.sub(r"\s", "-", text.strip())


def anchors_in(path: Path) -> set[str]:
    try:
        content = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return set()
    return {slugify(match.group(2)) for match in HEADING.finditer(content)}


def strip_code_fences(text: str) -> str:
    """Blank out fenced blocks so example commands and sample output are not scanned for links."""
    out, fenced = [], False
    for line in text.splitlines():
        if FENCE.match(line.strip()):
            fenced = not fenced
            out.append("")
            continue
        out.append("" if fenced else line)
    return "\n".join(out)


def markdown_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.md")
        if not any(part in SKIP_DIRS for part in path.parts)
    )


def main() -> int:
    root = Path.cwd()
    problems: list[str] = []
    checked = 0

    for source in markdown_files(root):
        text = strip_code_fences(source.read_text(encoding="utf-8"))

        for match in LINK.finditer(text):
            target = match.group(1).strip()

            # Not ours to resolve.
            if target.startswith(("http://", "https://", "mailto:", "tel:")):
                continue

            checked += 1
            line = text[: match.start()].count("\n") + 1
            where = f"{source.relative_to(root)}:{line}"

            path_part, _, anchor = target.partition("#")

            if not path_part:
                # A link to an anchor in this same file.
                if anchor and slugify(anchor) not in anchors_in(source):
                    problems.append(f"{where}: no heading matches #{anchor}")
                continue

            resolved = (source.parent / path_part).resolve()
            if not resolved.exists():
                problems.append(f"{where}: {path_part} does not exist")
                continue

            if anchor and resolved.suffix == ".md":
                if slugify(anchor) not in anchors_in(resolved):
                    problems.append(f"{where}: {path_part} has no heading #{anchor}")

    if problems:
        print(f"✗ {len(problems)} broken link(s):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(f"✓ {checked} internal links across {len(markdown_files(root))} files all resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
