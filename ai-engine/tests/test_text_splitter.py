"""Tests for the pure-Python paragraph-aware text splitter."""
from rag.text_splitter import split_text


def test_empty_and_whitespace_return_empty():
    assert split_text("") == []
    assert split_text("   \n\n  \t ") == []


def test_short_text_is_single_chunk():
    text = "Hello world. This is a short document."
    assert split_text(text, chunk_size=900, overlap=150) == [text]


def test_chunks_respect_size_bound():
    text = "\n\n".join(f"Paragraph {i}. " + "word " * 40 for i in range(20))
    chunks = split_text(text, chunk_size=900, overlap=150)
    assert len(chunks) > 1
    for c in chunks:
        # size + overlap (+ the "\n\n" join) is the worst-case bound
        assert len(c) <= 900 + 150 + 2


def test_oversized_single_paragraph_is_hard_split():
    text = "x" * 3000  # one giant token: no paragraph or sentence breaks
    chunks = split_text(text, chunk_size=900, overlap=0)
    assert len(chunks) >= 4
    for c in chunks:
        assert len(c) <= 900


def test_overlap_duplicates_trailing_context():
    text = "\n\n".join(f"Block {i}. " + "lorem ipsum dolor " * 20 for i in range(10))
    total_no_overlap = sum(len(c) for c in split_text(text, chunk_size=800, overlap=0))
    total_with_overlap = sum(len(c) for c in split_text(text, chunk_size=800, overlap=150))
    assert total_with_overlap > total_no_overlap


def test_paragraphs_preserved_when_they_fit():
    p1 = "First paragraph is about cats and their habits."
    p2 = "Second paragraph is about dogs and their habits."
    chunks = split_text(f"{p1}\n\n{p2}", chunk_size=900, overlap=150)
    assert len(chunks) == 1
    assert p1 in chunks[0] and p2 in chunks[0]


def test_all_original_words_present_across_chunks():
    text = "\n\n".join(f"Topic {i} discusses item{i} in detail." for i in range(50))
    chunks = split_text(text, chunk_size=300, overlap=50)
    joined = " ".join(chunks)
    for i in range(50):
        assert f"item{i}" in joined
