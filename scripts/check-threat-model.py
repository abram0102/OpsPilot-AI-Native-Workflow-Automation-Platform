#!/usr/bin/env python3
"""Checks that every test referenced by docs/THREAT_MODEL.md still exists.

A threat model whose "Test" column points at tests that were renamed or deleted is worse than
having no column at all: it reads as coverage and is not. This runs in CI (docs/TASKS.md T28), so
renaming a security test fails the build and the author has to update the row.

It deliberately does not try to understand the tests. It checks two things:

1. Every ``path/to/File.ext::test name`` reference resolves — the file exists and contains that
   exact identifier.
2. The document still contains a plausible number of references, so a bad edit that empties the
   tables cannot pass by referencing nothing.

Usage: python3 scripts/check-threat-model.py  (from the repository root)
"""

from __future__ import annotations

import pathlib
import re
import sys

DOC = pathlib.Path("docs/THREAT_MODEL.md")

# `path::identifier` inside a code span. The path must look like a source file so that prose such
# as `make audit-deps` is not mistaken for a reference.
REFERENCE = re.compile(r"`([\w./\[\]-]+\.(?:java|py|tsx|ts))::([^`]+)`")

# A floor, not a target. The point is only that a truncated or gutted document fails.
MINIMUM_REFERENCES = 60


def main() -> int:
    if not DOC.exists():
        print(f"✗ {DOC} does not exist", file=sys.stderr)
        return 1

    text = DOC.read_text(encoding="utf-8")
    references = REFERENCE.findall(text)

    problems: list[str] = []
    for path, name in references:
        name = name.strip()
        source = pathlib.Path(path)
        if not source.exists():
            problems.append(f"{path} does not exist (referenced as `{name}`)")
            continue
        if name not in source.read_text(encoding="utf-8"):
            problems.append(f"{path} no longer contains `{name}` — renamed or removed?")

    if len(references) < MINIMUM_REFERENCES:
        problems.append(
            f"only {len(references)} test references found, expected at least "
            f"{MINIMUM_REFERENCES} — the tables look truncated"
        )

    if problems:
        print(f"✗ {DOC} is out of date with the tests:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nUpdate the row, or the test name, so the threat model keeps naming real tests.",
            file=sys.stderr,
        )
        return 1

    print(f"✓ {DOC}: all {len(references)} referenced tests exist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
