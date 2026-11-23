import pytest

from app.rag.pii import redact


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Mail jane.doe@acme.test today", "Mail [EMAIL] today"),
        ("Card 4111 1111 1111 1111 on file", "Card [CARD] on file"),
        ("Call +1 (555) 123-4567 now", "Call [PHONE] now"),
        ("SSN 123-45-6789", "SSN [ID_NUMBER]"),
    ],
)
def test_redacts_personal_data(text, expected):
    redacted, count = redact(text)
    assert redacted == expected
    assert count == 1


@pytest.mark.parametrize(
    "text",
    [
        "Starts on 2024-01-15",
        "25 days of annual leave",
        "Invoice 1234 5678 9012 3456",  # 16 digits but fails the Luhn check
    ],
)
def test_leaves_ordinary_text_alone(text):
    assert redact(text) == (text, 0)
