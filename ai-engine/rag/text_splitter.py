"""Pure-Python, paragraph-aware text splitter for RAG chunking.

No external dependencies (previously used ``langchain_text_splitters``, which was
removed from requirements to keep the Render free-tier build light). The core
``split_text`` operates on an already-loaded string so it is trivially testable
and never touches the filesystem/network.
"""
import re

DEFAULT_CHUNK_SIZE = 900
DEFAULT_OVERLAP = 150

# Split on sentence terminators followed by whitespace.
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


def _normalize(text: str) -> str:
    """Normalise newlines and whitespace so paragraph detection is reliable."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+\n", "\n", text)   # drop trailing spaces before newlines
    text = re.sub(r"\n{3,}", "\n\n", text)   # collapse 3+ newlines to a paragraph break
    return text.strip()


def _split_long_paragraph(paragraph: str, chunk_size: int) -> list[str]:
    """Break a paragraph larger than ``chunk_size`` into pieces <= ``chunk_size``.

    Prefers sentence boundaries; hard-slices any single sentence that is itself
    longer than ``chunk_size``.
    """
    if len(paragraph) <= chunk_size:
        return [paragraph]

    pieces: list[str] = []
    current = ""
    for sentence in _SENTENCE_RE.split(paragraph):
        if len(sentence) > chunk_size:
            if current:
                pieces.append(current.strip())
                current = ""
            for i in range(0, len(sentence), chunk_size):
                pieces.append(sentence[i:i + chunk_size].strip())
            continue
        if current and len(current) + len(sentence) + 1 > chunk_size:
            pieces.append(current.strip())
            current = sentence
        else:
            current = (current + " " + sentence).strip() if current else sentence
    if current:
        pieces.append(current.strip())
    return [p for p in pieces if p]


def split_text(text: str,
               chunk_size: int = DEFAULT_CHUNK_SIZE,
               overlap: int = DEFAULT_OVERLAP) -> list[str]:
    """Split ``text`` into overlapping, paragraph-aware chunks.

    Each chunk is at most ``chunk_size`` characters of fresh content; up to
    ``overlap`` trailing characters of the previous chunk are prepended to the
    next for context continuity (so a chunk may reach ~``chunk_size + overlap``).
    Returns an empty list for empty/whitespace input.
    """
    if not text or not text.strip():
        return []

    text = _normalize(text)
    if len(text) <= chunk_size:
        return [text]

    # Break into paragraph units, further splitting any oversized paragraph.
    paragraphs: list[str] = []
    for para in text.split("\n\n"):
        para = para.strip()
        if not para:
            continue
        if len(para) > chunk_size:
            paragraphs.extend(_split_long_paragraph(para, chunk_size))
        else:
            paragraphs.append(para)

    chunks: list[str] = []
    current = ""
    for para in paragraphs:
        if current and len(current) + len(para) + 2 > chunk_size:
            chunks.append(current)
            if overlap > 0:
                tail = current[-overlap:]
                space = tail.find(" ")          # start overlap at a word boundary
                if space != -1:
                    tail = tail[space + 1:]
                current = (tail + "\n\n" + para).strip()
            else:
                current = para
        else:
            current = (current + "\n\n" + para).strip() if current else para
    if current:
        chunks.append(current)

    return [c.strip() for c in chunks if c.strip()]


def split_document(file_path: str,
                   chunk_size: int = DEFAULT_CHUNK_SIZE,
                   overlap: int = DEFAULT_OVERLAP) -> list[str]:
    """Backward-compatible helper: load a PDF then split it.

    The RAG flow prefers calling ``split_text`` on already-extracted text to
    avoid loading the document twice.
    """
    from rag.document_loader import load_pdf
    return split_text(load_pdf(file_path), chunk_size, overlap)
