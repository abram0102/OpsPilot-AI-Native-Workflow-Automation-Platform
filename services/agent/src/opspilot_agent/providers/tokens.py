"""Input-size budgeting.

There is no tokenizer in this service's dependency set, so nothing here counts
tokens exactly and nothing here pretends to. `estimate_tokens` is a documented
heuristic used as a guard rail: it decides whether a request is obviously too
large to send, never how many tokens were actually billed. Real usage numbers
come from the provider's own response and are T20's to record — inventing them
here would be exactly the fabricated metric AGENTS.md rule 6 forbids.

The heuristic deliberately errs high (it over-estimates rather than under-), so
the guard trips before a provider's own hard limit does.
"""

from __future__ import annotations

from collections.abc import Iterable

from opspilot_agent.providers.errors import AiInputTooLargeError

_CHARS_PER_TOKEN = 4
"""Rule-of-thumb ratio for English prose in byte-pair encodings. An estimate, not a measurement."""


def estimate_tokens(text: str) -> int:
    """A conservative upper-ish estimate of the tokens `text` would encode to."""
    if not text:
        return 0
    return -(-len(text) // _CHARS_PER_TOKEN)  # ceiling division


def estimate_message_tokens(messages: Iterable[dict[str, str]]) -> int:
    """Estimated size of a chat payload, counting role labels as well as content."""
    return sum(
        estimate_tokens(message.get("role", "")) + estimate_tokens(message.get("content", ""))
        for message in messages
    )


def enforce_input_budget(messages: Iterable[dict[str, str]], max_input_tokens: int) -> int:
    """Rejects an over-budget request before it costs anything. Returns the estimate."""
    estimated = estimate_message_tokens(messages)
    if estimated > max_input_tokens:
        raise AiInputTooLargeError(
            f"Request is roughly {estimated} tokens, over the {max_input_tokens} input budget. "
            "(Size is estimated from text length, not tokenized.)"
        )
    return estimated
