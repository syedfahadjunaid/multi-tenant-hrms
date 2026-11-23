"""The evaluation datasets are data, so they get tests too: a typo in an expected title would
silently count as a miss and make every model look worse than it is."""

import json
import re
from pathlib import Path

import pytest

DATASET_DIR = Path(__file__).parents[1] / "evals" / "retrieval" / "datasets"
DATASETS = sorted(DATASET_DIR.glob("*.json"))


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def acceptable(question: dict) -> list[str]:
    expected = question["expected"]
    if expected is None:
        return []
    return [expected] if isinstance(expected, str) else expected


@pytest.mark.parametrize("path", DATASETS, ids=lambda p: p.stem)
def test_dataset_is_consistent(path: Path) -> None:
    data = load(path)
    titles = [d["title"] for d in data["documents"]]
    questions = [q["question"] for q in data["questions"]]

    assert len(titles) == len(set(titles)), "duplicate document titles"
    assert len(questions) == len(set(questions)), "duplicate questions"
    assert all(d["content"].strip() for d in data["documents"]), "empty document"
    for q in data["questions"]:
        for title in acceptable(q):
            assert title in titles, f"{q['question']!r} expects unknown document {title!r}"
    assert any(q["expected"] is None for q in data["questions"]), "needs unanswerable questions"
    asked_about = {t for q in data["questions"] for t in acceptable(q)}
    assert set(titles) <= asked_about, f"never asked about: {set(titles) - asked_about}"
    assert {q.get("slice", "general") for q in data["questions"]} <= {"general", "exact_terms"}


def contains_word(text: str, term: str) -> bool:
    return re.search(rf"\b{re.escape(term)}\b", text, re.IGNORECASE) is not None


def test_exact_term_questions_really_use_a_term_from_the_policy() -> None:
    """The slice's definition, checked: the term is in the question and verbatim in a right
    answer; for unanswerable questions, it appears in no policy at all."""
    data = load(DATASET_DIR / "hr_large.json")
    documents = {d["title"]: f"{d['title']} {d['content']}" for d in data["documents"]}
    exact = [q for q in data["questions"] if q.get("slice") == "exact_terms"]
    assert len(exact) >= 30

    for q in exact:
        term = q["term"]
        assert contains_word(q["question"], term), q["question"]
        if acceptable(q):
            assert any(contains_word(documents[t], term) for t in acceptable(q)), q["question"]
        else:
            assert not any(contains_word(text, term) for text in documents.values()), q["question"]


def test_large_dataset_contains_the_small_one() -> None:
    """So results on the two sets can be compared."""
    small, large = load(DATASET_DIR / "hr_small.json"), load(DATASET_DIR / "hr_large.json")
    large_docs = {d["title"]: d["content"] for d in large["documents"]}
    for doc in small["documents"]:
        assert large_docs.get(doc["title"]) == doc["content"]
    large_questions = {q["question"] for q in large["questions"]}
    assert {q["question"] for q in small["questions"]} <= large_questions
