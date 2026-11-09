"""Version-controlled prompts, loaded from disk rather than embedded in code.

docs/SECURITY_AND_TESTING.md section 5: "version the prompt in Git and track each changed prompt as
a measurable experiment". Keeping each prompt in its own versioned file is what makes that possible
— a prompt change shows up as a reviewable diff, and a new revision is a new file (`.v2.md`) rather
than an edit that silently changes behaviour for every past run.

`PromptName` therefore names a *revision*, not just a prompt: whatever is recorded against a run is
enough to reproduce what the model was actually told.
"""

from __future__ import annotations

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
