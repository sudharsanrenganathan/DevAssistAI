# RAG Implementation Plan — Lightweight BM25 Retrieval for Secret AI

**Status: APPROVED, NOT YET IMPLEMENTED** (user decision 2026-07-10).
This is the **first work item** the next time project work starts (see
`PROJECT_BUILDER_PROGRESS.md` §9). Do not start without the user asking to begin project work.

---

## 1. Why

Production Secret AI (`/rag-ask`) does **no retrieval** today. It extracts the whole PDF with
`pypdf`, truncates to the first **30,000 characters**, and stuffs that into the LLM prompt
("document-only" mode). Consequences:

- Content past ~30K chars (~10–15 pages) is **invisible** — questions about later pages fail.
- Every question spends ~7.5K prompt tokens on mostly irrelevant text — expensive against the
  22K/user/day token budget enforced by `services/token_ledger.py`.
- The FAISS/sentence-transformers pipeline in `rag/vector_store.py` is dead code: its deps
  (`sentence-transformers`, `faiss`, `torch`, `langchain_text_splitters`) were removed from
  `requirements.txt` (commit `91712fe`) because they broke Render free-tier builds and OOM-killed
  the 512 MB instance. Nothing in the deployed flow calls it (backend never hits `/upload-doc`).

**Goal**: real chunk-level retrieval that runs on Render free tier — pure Python, no compiled
ML dependencies, no model downloads at runtime.

## 2. Approach — BM25 lexical retrieval

Chosen: **BM25 (Okapi)** over document chunks via the `rank-bm25` package (pure Python, only
depends on numpy which is already installed). No embeddings, no vector DB, ~zero memory overhead.

Rejected alternatives:
- FAISS + MiniLM embeddings — proven OOM/build-timeout on free tier (that's why it was removed).
- sklearn TF-IDF — compiled wheels are fine but heavier than needed; BM25 outranks TF-IDF for QA retrieval anyway.
- Remote embedding APIs — adds a vendor, latency, and cost; against the single-provider design.

## 3. Pipeline design

```
PDF (Supabase Storage URL)
  └─ download → pypdf extract (existing document_loader.load_pdf)
       └─ chunk: pure-Python splitter, ~900 chars/chunk, 150 overlap, paragraph-aware
            └─ index: BM25Okapi over tokenized chunks (lowercase, \w+ regex, small stopword list)
                 └─ per question: retrieve top-k=6 chunks (query = question + last user turn)
                      └─ assemble context ≤ RAG_CONTEXT_CHARS (default 8000),
                         chunks ordered by document position (not score) for coherence
                           └─ safe_generate() — token ledger, budgets, retries all unchanged
```

Key behaviors:
- **Small-doc bypass**: if full text ≤ RAG_CONTEXT_CHARS, skip retrieval, send whole text
  (retrieval adds nothing for short docs).
- **Fail-open fallback**: any retriever error → fall back to current truncated full-text mode
  (same philosophy as the token ledger). Env flag `RAG_MODE=bm25|fulltext` (default `bm25`)
  for instant rollback without redeploy logic changes.
- **Cache fix**: key `rag_cache` by the **remote Supabase URL**, not the local temp path —
  today every question re-downloads to a fresh `remote_<uuid>.pdf` so the cache never hits.
  New cache value: `{"chunks": [...], "bm25": <BM25Okapi>, "text_len": int}` under the existing
  `rag_cache_lock`.
- **`/upload-doc` alignment**: currently builds the dead FAISS tuple → crash if ever called
  (imports missing) and cache-format mismatch with `/rag-ask`. Rebuild it to produce the same
  BM25 cache structure, or remove it (backend never calls it — removal needs user approval per
  CLAUDE.md).

## 4. File-by-file changes

| File | Change |
|---|---|
| `ai-engine/rag/text_splitter.py` | Replace langchain splitter with pure-Python paragraph-aware splitter (900/150). No new deps. |
| `ai-engine/rag/retriever.py` | **NEW** — `build_index(chunks) -> BM25Okapi`, `retrieve(index, chunks, query, k=6) -> list[str]`, tokenizer + stopwords. |
| `ai-engine/rag/vector_store.py` | Dead FAISS code — propose deletion (needs explicit user approval) or leave untouched. |
| `ai-engine/api/api_server.py` | `/rag-ask`: URL-keyed cache, build/reuse BM25 index, retrieve top-k, small-doc bypass, `RAG_MODE` flag, fail-open fallback. `/upload-doc`: align or remove (ask user). |
| `ai-engine/requirements.txt` | Add `rank-bm25`. |
| `ai-engine/tests/test_text_splitter.py` | **NEW** — chunk size bounds, overlap, paragraph preservation, empty/short input. |
| `ai-engine/tests/test_retriever.py` | **NEW** — synthetic multi-topic doc: query retrieves the correct chunk; k > n_chunks; empty query. |
| `ai-engine/tests/test_rag_ask.py` | Extend — mocked Groq: retrieval path used for long docs, bypass for short docs, fulltext fallback on retriever error, quota error convention unchanged (`ERROR: [quota:scope]`). |

No backend (Java) or frontend changes required — the `/ai/rag` contract and streaming response
format are unchanged.

## 5. Verification checklist (definition of done)

1. All new + existing pytest green (mocked Groq — no live API calls in tests).
2. Local run: upload a PDF **longer than 30K chars**, ask a question about content **past the
   old 30K cutoff** → correct answer (impossible before; this is the headline gain).
3. Repeat question on same doc → cache hit (no re-download/re-index; check logs).
4. `RAG_MODE=fulltext` restores old behavior exactly.
5. Token meter check: prompt tokens per question drop from ~7.5K to ~2–2.5K (record actual
   numbers — **needed as evaluation data for the IEEE paper**, see §6).
6. Render deploy builds fast (no compiled deps) and memory stays flat.

## 6. Measurements to record for the IEEE paper (while implementing)

- Prompt tokens/question: before (fulltext-30K) vs after (BM25 top-6) on the same doc+questions.
- End-to-end answer latency, same comparison.
- Long-document coverage: N questions about content past 30K chars — accuracy before (expected
  ~0) vs after.
- Index build time + memory for a representative PDF (e.g., 100 pages).

## 7. Estimated scope

~2 sessions: one for splitter + retriever + tests, one for `/rag-ask` integration,
verification, and measurements. No DB migration, no deploy-config change beyond
`RAG_MODE`/`RAG_CONTEXT_CHARS` env vars (both optional with safe defaults).
