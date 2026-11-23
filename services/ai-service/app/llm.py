"""LLM access. Claude via the official Anthropic SDK; any other model fits the same interface."""

import logging
from dataclasses import dataclass
from typing import Protocol

import anthropic

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class LLMResult:
    answer: str
    model: str  # the model that actually served the request (from the response)
    # Why generation stopped ("end_turn", "max_tokens", "refusal", ...) and the tokens it used.
    # The service ignores both; the answer-quality eval records them to report cost and truncation.
    stop_reason: str | None = None
    usage: dict[str, int] | None = None


class LLMUnavailableError(Exception):
    """The LLM could not be reached or returned an error. Maps to HTTP 503/502."""


class LLMClient(Protocol):
    def complete(self, system: str, user: str) -> LLMResult: ...


class ClaudeClient:
    def __init__(
        self, api_key: str, model: str, effort: str, max_tokens: int, timeout_seconds: float
    ) -> None:
        # The SDK retries 408/409/429/5xx and connection errors itself (max_retries).
        self._client = anthropic.Anthropic(api_key=api_key, timeout=timeout_seconds, max_retries=2)
        self._model = model
        self._effort = effort
        self._max_tokens = max_tokens

    def complete(self, system: str, user: str) -> LLMResult:
        try:
            response = self._client.beta.messages.create(
                model=self._model,
                max_tokens=self._max_tokens,
                # How hard the model thinks. Q&A over short documents rarely needs more.
                output_config={"effort": self._effort},
                # If the model declines on safety grounds, the API retries on a fallback model.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
                system=system,
                messages=[{"role": "user", "content": user}],
            )
        except anthropic.RateLimitError as e:
            raise LLMUnavailableError("LLM rate limited") from e
        except anthropic.APIStatusError as e:
            log.error("LLM error status=%s request_id=%s", e.status_code, e.request_id)
            raise LLMUnavailableError(f"LLM returned {e.status_code}") from e
        except anthropic.APIConnectionError as e:
            raise LLMUnavailableError("LLM unreachable") from e

        usage = _usage(response.usage)
        if response.stop_reason == "refusal":
            return LLMResult("I can't help with that request.", response.model, "refusal", usage)
        if response.stop_reason == "max_tokens":
            log.warning("LLM answer truncated at max_tokens=%s", self._max_tokens)

        text = "".join(block.text for block in response.content if block.type == "text")
        return LLMResult(text.strip(), response.model, response.stop_reason, usage)


def _usage(usage: object) -> dict[str, int]:
    """Token counts from the API response (cache fields are None when caching wasn't used)."""
    fields = (
        "input_tokens",
        "output_tokens",
        "cache_read_input_tokens",
        "cache_creation_input_tokens",
    )
    return {f: getattr(usage, f, None) or 0 for f in fields}
