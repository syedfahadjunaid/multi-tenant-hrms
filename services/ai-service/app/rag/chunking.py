"""Split documents into retrieval-sized chunks.

Smaller chunks make retrieval more precise; too small and they lose context. ~1200
characters on paragraph boundaries is a reasonable start. Tune it with an evaluation set.
"""

import re


def chunk_text(text: str, max_chars: int = 1200, overlap: int = 150) -> list[str]:
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]

    # Very long paragraphs are cut into overlapping windows so no sentence is lost at a boundary.
    pieces: list[str] = []
    step = max_chars - overlap
    for para in paragraphs:
        if len(para) <= max_chars:
            pieces.append(para)
        else:
            pieces.extend(para[i : i + max_chars] for i in range(0, len(para), step))

    # Then pack consecutive paragraphs together up to max_chars.
    chunks: list[str] = []
    current = ""
    for piece in pieces:
        if current and len(current) + 2 + len(piece) > max_chars:
            chunks.append(current)
            current = piece
        else:
            current = f"{current}\n\n{piece}" if current else piece
    if current:
        chunks.append(current)
    return chunks
