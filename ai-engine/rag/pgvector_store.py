"""Chunk storage + semantic retrieval for Secret AI, backed by Supabase pgvector.

Uses raw psycopg2 (already a dependency). Embeddings are passed as ``::vector``
string literals, so the pure-Python ``pgvector`` package is NOT required — we
never read the vector column back into Python.

Schema: see db/migrations/2026-08-09_create_document_chunks.sql
"""
import os

import psycopg2
from psycopg2.extras import execute_values


def _connect():
    return psycopg2.connect(os.getenv("DATABASE_URL"))


def _vector_literal(vec) -> str:
    """Format a float sequence as a pgvector literal, e.g. ``[0.1,0.2,0.3]``."""
    return "[" + ",".join(f"{float(x):.8f}" for x in vec) + "]"


def is_indexed(document_key: str) -> bool:
    """True if any chunk already exists for this document."""
    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM document_chunks WHERE document_key = %s LIMIT 1",
                (document_key,),
            )
            return cur.fetchone() is not None
    finally:
        conn.close()


def index_document(document_key: str, chunks, embeddings, user_id=None) -> int:
    """Bulk-insert chunks + embeddings for a document. Idempotent per
    (document_key, chunk_index). Returns the number of rows inserted-or-skipped.
    """
    if not chunks:
        return 0
    if len(chunks) != len(embeddings):
        raise ValueError("chunks and embeddings must be the same length")

    rows = [
        (document_key, user_id, i, chunk, _vector_literal(emb))
        for i, (chunk, emb) in enumerate(zip(chunks, embeddings))
    ]
    conn = _connect()
    try:
        with conn.cursor() as cur:
            execute_values(
                cur,
                """INSERT INTO document_chunks
                       (document_key, user_id, chunk_index, content, embedding)
                   VALUES %s
                   ON CONFLICT (document_key, chunk_index) DO NOTHING""",
                rows,
                template="(%s, %s, %s, %s, %s::vector)",
            )
        conn.commit()
        return len(rows)
    finally:
        conn.close()


def retrieve(document_key: str, query_embedding, k: int = 6) -> list[str]:
    """Return the top-``k`` chunks for ``document_key`` by cosine similarity to
    ``query_embedding``, re-ordered by document position for a coherent context.
    """
    vec = _vector_literal(query_embedding)
    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT chunk_index, content
                       FROM document_chunks
                      WHERE document_key = %s
                      ORDER BY embedding <=> %s::vector
                      LIMIT %s""",
                (document_key, vec, k),
            )
            rows = cur.fetchall()
    finally:
        conn.close()

    rows.sort(key=lambda r: r[0])          # document order, not similarity order
    return [r[1] for r in rows]


def delete_document(document_key: str) -> None:
    """Remove all chunks for a document (call when the document is deleted)."""
    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_chunks WHERE document_key = %s", (document_key,))
        conn.commit()
    finally:
        conn.close()
