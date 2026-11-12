"""Version-controlled prompts, loaded from disk rather than embedded in code.

docs/SECURITY_AND_TESTING.md section 5: "version the prompt in Git and track each changed prompt as
a measurable experiment". Keeping each prompt in its own versioned file is what makes that possible
— a prompt change shows up as a reviewable diff, and a new revision is a new file (`.v2.md`) rather
than an edit that silently changes behaviour for every past run.

`PromptName` therefore names a *revision*, not just a prompt: whatever is recorded against a run is
enough to reproduce what the model was actually told.
"""

from __future__ import annotations

import hashlib
from enum import StrEnum
from functools import lru_cache
from pathlib import Path

_PROMPT_DIR = Path(__file__).parent


class PromptName(StrEnum):
    CLASSIFICATION_V1 = "classification.v1"
    REPLY_DRAFT_V1 = "reply_draft.v1"


@lru_cache
def load_prompt(name: PromptName) -> str:
    """Reads a prompt from disk. Cached — prompts are immutable once released."""
    path = _PROMPT_DIR / f"{name}.md"
    if not path.is_file():
        raise FileNotFoundError(f"Prompt file is missing: {path}")
    return path.read_text(encoding="utf-8").strip()


def prompt_revisions() -> dict[str, str]:
    """Each prompt's revision name and a digest of what it actually says.

    The revision name alone is not quite enough to stamp a run with (T30: "prompt version/model/
    cost stamping"). A revision is supposed to be immutable once released, but "supposed to be" is
    not a guarantee a report can rely on — an edit to `classification.v1.md` would leave every
    past and future run claiming the same version while the model was told something different.
    The digest makes that visible in a diff of two reports.
    """
    return {
        name.value: hashlib.sha256(load_prompt(name).encode("utf-8")).hexdigest()[:12]
        for name in PromptName
    }
