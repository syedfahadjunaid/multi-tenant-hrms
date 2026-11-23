"""Mask personal data before text leaves our infrastructure (i.e. is sent to an LLM).

Regexes catch the obvious formats only. Production systems layer this with a dedicated
PII detector (e.g. Microsoft Presidio) and data-classification tags on documents.
Add patterns for the national ID formats your users actually have.
"""

import re

_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_SSN = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
_CARD_CANDIDATE = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")
_PHONE_CANDIDATE = re.compile(r"(?<![\w+])\+?\(?\d[\d ()-]{6,}\d\b")


def _digits(s: str) -> str:
    return "".join(c for c in s if c.isdigit())


def _luhn_valid(number: str) -> bool:
    total = 0
    for i, ch in enumerate(reversed(number)):
        d = int(ch)
        if i % 2 == 1:
            d = d * 2 - 9 if d > 4 else d * 2
        total += d
    return total % 10 == 0


def redact(text: str) -> tuple[str, int]:
    """Returns the redacted text and how many items were masked."""
    count = 0

    def sub(pattern: re.Pattern[str], label: str, s: str, accept=lambda m: True) -> str:
        def replace(match: re.Match[str]) -> str:
            nonlocal count
            if not accept(match.group()):
                return match.group()
            count += 1
            return f"[{label}]"

        return pattern.sub(replace, s)

    text = sub(_EMAIL, "EMAIL", text)
    text = sub(_SSN, "ID_NUMBER", text)
    # Only real card numbers (Luhn check), so long reference numbers aren't masked.
    text = sub(
        _CARD_CANDIDATE,
        "CARD",
        text,
        lambda s: 13 <= len(_digits(s)) <= 19 and _luhn_valid(_digits(s)),
    )
    # 10+ digits, so dates like 2024-01-15 and "25 days" are left alone.
    text = sub(_PHONE_CANDIDATE, "PHONE", text, lambda s: 10 <= len(_digits(s)) <= 15)
    return text, count
