# PROMPT: Write an IEEE Paper on "DevAssist AI"

> Copy everything below this line and paste it into the LLM that will write the paper.

---

You are an expert academic writer. Write a complete IEEE-format conference paper (two-column style, standard IEEE section structure: Abstract, Keywords, I. Introduction, II. Related Work, III. System Architecture, IV. Implementation / Methodology, V. Results and Discussion, VI. Limitations and Future Work, VII. Conclusion, References) about the software system described below. Everything described below is a real, fully implemented and deployed system — describe it accurately, do NOT invent features, metrics, or benchmark numbers that are not given here. Where the paper needs evaluation numbers, use only the facts provided in Section 9 (Evaluation Facts) or clearly mark places where the author should insert measured values.

Suggested title (you may refine): **"DevAssist AI: A Unified Full-Stack AI-Powered Developer Assistance Platform with Resource-Governed LLM Access on Free-Tier Cloud Infrastructure"**

Suggested keywords: Large Language Models, Retrieval-Augmented Generation, Developer Tools, Microservices, Code Generation, Online Judge, Token Budgeting, Cloud Deployment.

---

## 1. What the Project Is

**DevAssist AI** is a full-stack, production-deployed AI developer-assistance platform that unifies six developer tools behind a single authenticated web application:

1. **DevAssist AI Global Chat** — a general-purpose AI programming chat assistant with persistent conversation history.
2. **Secret AI** — a document-grounded Q&A assistant (RAG) that answers questions strictly from a user-uploaded PDF.
3. **Code Analyzer** — an AI static-analysis tool that reviews pasted source code and returns structured feedback.
4. **VoidMain** — a competitive-programming platform (online judge) with problems, sample/hidden test cases, verdicts, submission history, and a leaderboard.
5. **Online Compiler** — a multi-language code execution sandbox.
6. **Ignite** — an AI project builder that turns a natural-language description into a complete multi-file web project with live preview, ZIP download, AI-driven editing, and per-user project persistence with resume.

A distinctive engineering contribution of the system is a **resource-governance layer**: a shared daily LLM token pool with per-user hard quotas, enforced atomically in the database, that lets a multi-user application run safely on a single free-tier LLM API key without any user exhausting the shared capacity.

The entire system runs on **free-tier cloud infrastructure** (Render.com, Vercel, Supabase, Groq), and several architectural decisions exist specifically to survive free-tier constraints (cold starts, memory limits, rate limits, build-time limits). This "LLM products under hard resource constraints" angle is a strong framing for the paper.

---

## 2. High-Level Architecture (three independently deployed services)

```
┌─────────────────┐     JWT (Supabase)      ┌──────────────────────┐        ┌─────────────────────┐
│   Frontend       │ ──────────────────────▶ │  Backend Gateway      │ ─────▶ │  AI Engine           │
│   Vanilla HTML/  │      REST + streaming   │  Spring Boot 3 /      │  REST  │  FastAPI / Python    │
│   JS/CSS (Vercel)│ ◀────────────────────── │  Java 21 (Render,     │ ◀───── │  3.11 (Render)       │
└─────────────────┘                          │  Docker)              │        └──────────┬──────────┘
        │                                    └─────────┬─────────────┘                   │
        │ Supabase Auth (login/signup)                 │                                 │ Groq API (LLM)
        ▼                                              ▼                                 │ Wandbox (code exec)
┌─────────────────┐                          ┌──────────────────────┐                   ▼
│  Supabase Auth   │                          │  PostgreSQL           │        ┌─────────────────────┐
│  + Storage       │                          │  (Supabase, PgBouncer │        │  External services   │
│  (PDF documents) │                          │  transaction pool)    │        └─────────────────────┘
└─────────────────┘                          └──────────────────────┘
```

- **Frontend** (`frontend/`): pure HTML/JavaScript/CSS, deliberately **no framework**. Deployed as static files on Vercel. Every protected page enforces authentication at `DOMContentLoaded` via a shared `auth-guard.js` (`requireAuth()`), and all backend calls go through a shared `authFetch()` wrapper that automatically attaches the Supabase JWT. The frontend is also mirrored into the Spring Boot `resources/static/` folder so the backend can serve the app self-contained.
- **Backend Gateway** (`backend/devassist-backend/`): Spring Boot 3, Java 21, Maven, layered Clean Architecture (Controller → Service → Repository → Entity, with DTOs as Java records and constructor injection throughout). It is the single trust boundary: it validates Supabase JWTs (custom `JwtFilter` + `JwtUtil`), applies per-user authorization, persists all application state to PostgreSQL, and proxies AI requests to the AI Engine. Deployed on Render.com as a Docker container.
- **AI Engine** (`ai-engine/`): FastAPI, Python 3.11. Stateless with respect to the database (all persistence goes back through the backend); holds only in-memory caches guarded by `threading.Lock` (RAG document cache, per-session doc-chat history, global-chat history). All LLM traffic funnels through a single choke-point function `safe_generate()` in `core/llm.py`, which is where token budgeting is enforced. Deployed on Render.com (start command: `uvicorn api.api_server:app`). Heavy libraries (FAISS, sentence-transformers) are imported lazily inside function bodies so the server cold-starts instantly on the free tier.

**Infrastructure**: PostgreSQL via Supabase with PgBouncer transaction pooling; Supabase Auth for identity (email/password, JWT issuance); Supabase Storage for uploaded PDFs; Groq API for LLM inference (default model `openai/gpt-oss-120b`, with a registry of alternates: `openai/gpt-oss-20b`, `groq/compound`, `groq/compound-mini`, `qwen/qwen3-32b`, `meta-llama/llama-4-scout-17b-16e-instruct`); Wandbox as the remote compilation/execution sandbox for the online judge.

A `KeepAliveService` in the backend pings the AI engine every 14 minutes to prevent Render free-tier cold starts (spin-down after inactivity).

---

## 3. Feature Details (describe each as a subsystem in the paper)

### 3.1 Authentication & User Management
- Supabase Auth handles signup/login and issues JWTs.
- The Spring Boot backend verifies the JWT signature (`SUPABASE_JWT_SECRET`) in a custom `JwtFilter`; `/api/auth/me` returns the authenticated identity, `/api/auth/sync` mirrors the Supabase user into the local `users` table so relational features (chats, submissions, projects) can reference it.
- Authorization principle: the owner identity (email) is always derived **from the JWT server-side**, never trusted from the request body. Cross-user access to another user's resources returns 404 (existence is not leaked).
- A small number of endpoints are deliberately public (health checks; two read-only history endpoints that the AI Engine itself calls back without a JWT).

### 3.2 DevAssist AI Global Chat
- Frontend page `global.html`; backend `GlobalChatController` at `/api/global-chat/*` (create chat, list all, get by id, post message, pin, rename, delete); AI Engine endpoints `/global-ai` (primary) and `/local-ai` (fallback).
- Full chat persistence in PostgreSQL: multi-chat sidebar, pinning, renaming, deletion, and history reload across sessions. Responses render with a typing animation.
- Conversation memory: recent history is included in the prompt so the assistant is context-aware across turns.
- Users can select among the registered Groq models per request.

### 3.3 Secret AI — Document Q&A (Retrieval-Augmented Generation)
- Frontend page `secret.html`. Upload flow: PDF → backend `/ai/upload/{userId}` → stored in Supabase Storage + `documents` table. Document management: list (`GET /ai/documents/{userId}`) and delete (`DELETE /ai/documents/{id}`).
- Question flow: `/ai/rag` (and per-document `/ai/rag/{docId}`) → AI Engine `/rag-ask`, which returns a **streamed plain-text response** (chunked `StreamingResponse`), so answers render progressively in the UI.
- **Retrieval architecture — resource-constrained lexical RAG (a core contribution of the system).** The pipeline is deliberately embedding-free so it fits a 512 MB free-tier instance with zero compiled ML dependencies and no model downloads at runtime:
  1. *Extract*: PDF text extraction with `pypdf` (documents fetched from Supabase Storage with URL sanitization and corruption repair).
  2. *Chunk*: pure-Python paragraph-aware splitter, ~900 characters per chunk with 150-character overlap.
  3. *Index*: an Okapi **BM25** index over the tokenized chunks (lowercasing, word tokenization, stopword removal) — built once per document and cached in memory under a lock, keyed by the document's storage URL so repeat questions reuse the index.
  4. *Retrieve*: for each question, the top-k (k=6) chunks are selected by BM25 score using the question plus the latest conversational turn as the query, then re-ordered by their position in the document (not by score) so the assembled context reads coherently.
  5. *Generate*: the selected chunks (bounded to a configurable context budget, default 8,000 characters) are passed to the LLM in "document-only" mode — the system prompt constrains answers to the supplied document content. All calls go through the token-governed `safe_generate()` choke point.
- **Adaptive bypass and fail-open design**: documents that fit entirely within the context budget skip retrieval and are passed whole (retrieval adds nothing for short documents); any retriever failure falls back automatically to a bounded full-text mode (first 30,000 characters) rather than failing the request — the same availability-over-strictness philosophy as the token ledger. A `RAG_MODE` environment flag can force the fallback mode for instant rollback.
- **Design evolution (honest engineering narrative for the Discussion section)**: the first prototype used `all-MiniLM-L6-v2` sentence-transformer embeddings with a FAISS `IndexFlatL2` vector index. On the free tier, PyTorch + FAISS caused build timeouts and out-of-memory kills, so the system moved first to bounded full-text stuffing (30K-char cap — losing tail content of long documents and spending ~7.5K prompt tokens per question), and finally to the BM25 lexical pipeline above, which restores genuine chunk-level retrieval — full-document coverage regardless of length, at a fraction of the token cost — with pure-Python dependencies. This embeddings→nothing→BM25 arc is strong material for the paper: semantic retrieval is not always affordable, and lexical retrieval is a principled, measurable middle ground.
- Per-session conversational memory: chat history is keyed by session id, held in a lock-guarded dict, seeded from the database on first request of a session, and the last 5 Q/A pairs (answers truncated to 300 chars) are prepended to each prompt inside a `<conversation_history>` tag.
- Robustness engineering: automatic detection and in-place repair of base64-corrupted PDF uploads; URL re-encoding for Supabase Storage paths containing spaces; remote-document download with validation; typed in-band error strings (`ERROR: <Type>: <message>`) streamed to the client.
- Doc-chat sessions themselves are persisted via `/ai/session/*` and `/ai/messages/{sessionId}` endpoints (create, list, rename, delete sessions; fetch messages).

### 3.4 Code Analyzer
- Frontend page `code-analyzer.html`; backend `POST /ai/analyze` (JWT-protected) proxies to AI Engine `POST /code-analyze`.
- The engine prompts the LLM to return structured analysis of pasted code (issues/bugs, quality feedback, improvement suggestions), parsed and rendered as organized sections in the UI.

### 3.5 VoidMain — Competitive Programming Judge
- Frontend pages: `voidmain.html` (hub), `vm-problems.html` (problem list with difficulty tiers), `vm-compiler.html` (solve/editor page), `vm-history.html` (submission history), `vm-leaderboard.html`.
- Backend `VoidMainController` at `/vm/*`:
  - `GET /vm/problems`, `GET /vm/problems/{id}` — problem bank (statement, constraints, samples) stored server-side.
  - `POST /vm/run` — executes the user's code against **sample** test cases only; returns per-case results (input, expected, actual, PASS/FAIL/RUNTIME_ERROR/TLE) and totals. For Java, `class Solution` is transparently rewritten to `class Main` for execution.
  - `POST /vm/submit` — judges against **hidden** test cases with fail-fast semantics; verdicts: `Accepted`, `Wrong Answer`, `Compilation Error`, `Runtime Error`, `Time Limit Exceeded`. Output comparison uses a `normalize()` function (whitespace/newline normalization). Each submission (verdict, passed/failed counts, execution time, timestamp) is persisted, and the leaderboard is updated.
  - `GET /vm/submissions/{username}`, `GET /vm/leaderboard`, `GET /vm/stats/{username}` — history, ranking, and per-user statistics.
  - `POST /vm/execute` — the free-form multi-language online compiler (also used by `online-compiler.html`).
- Code execution is delegated to **Wandbox**, a remote sandboxed compilation/execution service, so no untrusted code ever runs on the application servers — an important security property to highlight.

### 3.6 Ignite — AI Project Builder (the flagship feature)
- Frontend page `ignite.html`; backend `ProjectBuilderController` (`/api/project-builder/plan|generate-file|edit`) and `IgniteProjectController` (`/api/project-builder/projects` CRUD); AI Engine `services/project_builder.py` + `core/llm_providers.py`.
- **Incremental generation pipeline**: one LLM call produces a structured project *plan* (file list + descriptions); then **one streaming LLM call per file** generates each file's content, streamed token-by-token through the Spring Boot proxy (`ResponseEntity<StreamingResponseBody>`, async dispatch) to the browser. This bounds every individual request's context size and latency — critical under Groq free-tier limits (8K tokens/min, 30 requests/min, 200K tokens/day per API key).
- **Provider abstraction**: `llm_providers.py` defines an `LLMProvider` protocol and a `PROVIDER_REGISTRY`; adding a vendor is one class + one registry entry. Groq (`openai/gpt-oss-120b`) is active; NVIDIA NIM remains registered as a switch-back option (it was abandoned when its DeepSeek queue jammed with ~600 queued requests causing 90-second timeouts). Per-provider model env vars (`PROJECT_BUILDER_MODEL_GROQ`, `PROJECT_BUILDER_MODEL_NVIDIA`).
- **Live preview**: generated projects render in a sandboxed iframe. Because the sandbox's opaque origin cannot fetch the parent page's `blob:` URLs, the preview builder inlines CSS/JS and converts assets to `data:` URLs.
- **AI editing**: the user selects a file and describes a change in natural language; the edit endpoint regenerates that file.
- **ZIP export** of the complete project in the browser.
- **Project persistence & resume**: an `ignite_projects` table (owner email from JWT; plan and files stored as JSON) with full CRUD; the UI auto-saves after the plan, after each generated file, on completion, and on edits (a save queue). A "MY PROJECTS" start screen lists saved projects; resuming re-enters the generation loop, which skips already-completed files — so a session interrupted by a quota limit or disconnect continues where it left off.
- Context budget per request is env-tunable (default ~10,000 chars); HTTP 413 from the provider is classified as "busy" and retried appropriately; backend async timeout is 300 s.

### 3.7 Token Governance (the paper's strongest technical contribution)
- **Problem**: all users share one free-tier LLM API key with a hard 200K-tokens/day provider limit. Any single user could exhaust it for everyone.
- **Design**: a daily **shared global pool with per-user hard quotas**, three env-tunable numbers: provider capacity (200K/day), an **emergency reserve** (10K, never spent — it absorbs post-paid overshoot, since actual usage is only known *after* a call completes), giving a derived spendable global pool (190K/day), plus a per-user daily cap (~22–25K, env-tunable).
- **Enforcement — two-phase**:
  1. *Pre-flight admission*: before every LLM call, cost is estimated as `len(prompt_chars)/4 + max_completion_tokens`; if the estimate would breach either the user cap or the global pool, the request is rejected immediately with HTTP 429 and `status:"quota"` (distinct from `"busy"` for provider errors — the frontend stops retrying only on quota).
  2. *Post-paid recording*: providers report exact usage (for Groq streaming, from the `x_groq.usage` field of the final stream chunk) via a `usage_callback`; the ledger records it.
- **Atomicity**: a two-table ledger (`user_daily_usage`, `global_daily_usage`) is updated with **atomic dual upserts in a single database transaction**, safe under concurrency through PgBouncer transaction pooling. The ledger **fails open** on DB errors (availability over strict enforcement — a deliberate, documented choice).
- **Scope**: enforcement covers *every* LLM feature app-wide (global chat, doc chat, code analyzer, Ignite) because all calls funnel through the single `safe_generate()` choke point. All token-consuming routes require a JWT.
- **In-band stream errors**: since a stream already returning 200 cannot change its status code, quota breaches mid-stream emit a tagged in-band line `ERROR: [quota:user|global] ...` that the frontend parses to stop retries and show the message.
- **User-facing transparency**: `GET /api/usage` returns remaining user/global budget with a breakdown; a 🔋 battery-style usage meter is displayed on all four AI feature pages.

### 3.8 Supporting subsystems
- **Activity logging**: `/api/activity/log` + `/api/activity/recent/{userId}` record and surface recent user actions on the dashboard.
- **Dashboard** (`dashboard.html`): the authenticated hub linking all features.
- **Health endpoints** on both services for uptime monitoring and keep-alive.
- **Resilience in `safe_generate`**: up to 3 retries with backoff on provider failure, then a graceful degraded response ("AI temporarily unavailable") instead of an exception to the user.

---

## 4. Data Model (PostgreSQL via Supabase)

Tables (describe as an ER-style overview): `users` (synced from Supabase Auth); global-chat `chats` + messages; doc-chat sessions + messages; `documents` (uploaded PDF metadata + storage path); `vm_submissions`; VoidMain leaderboard; `user_activity`; `ignite_projects` (owner_email, plan JSON, files JSON, status); `user_daily_usage` and `global_daily_usage` (the token ledger, keyed by date). Row-Level Security is enabled on Supabase-managed tables. Schema changes are applied through explicit migration scripts (`ddl-auto=none`), never Hibernate auto-DDL.

---

## 5. Security Model (worth its own subsection in the paper)

- Single trust boundary at the Spring Boot gateway; Supabase-issued JWT verified on every protected route by a custom filter.
- Owner identity always derived server-side from the JWT; cross-user resource access returns 404.
- All secrets injected via environment variables — no credential defaults in config files.
- Untrusted code execution fully externalized to Wandbox sandbox.
- Streaming endpoints required explicit Spring Security handling of ASYNC and ERROR dispatchers (a subtle real-world bug: without permitting those dispatchers, *every* backend error surfaced as an empty 403 — a good "lessons learned" anecdote).
- CORS locked to the deployed frontend origins.

---

## 6. Notable Engineering Decisions Forced by Free-Tier Constraints (great Discussion-section material)

1. Lazy imports of heavy ML libraries for instant cold start on Render.
2. Retrieval redesigned from FAISS vector search (build timeouts + OOM on 512 MB) to a pure-Python BM25 lexical pipeline — restoring true chunk-level retrieval with zero compiled dependencies (Section 3.3).
3. Incremental per-file generation in Ignite to fit Groq's 8K tokens/min window.
4. The entire token-governance layer (Section 3.7) existing because one API key is shared by all users.
5. Keep-alive pinging between services to defeat free-tier spin-down.
6. Provider registry abstraction after a real vendor outage (NVIDIA NIM queue jam) forced a same-day provider swap.
7. Removal of heavy compiled dependencies (PyTorch/FAISS) from `requirements.txt` to resolve Render build timeouts.

---

## 7. Software Engineering Practices (for the Methodology section)

- Clean Architecture with strict layer boundaries in the backend; SOLID principles; constructor injection; Java records for DTOs; Java 21 features (pattern matching, text blocks).
- PEP 8, full type hints, async route handlers in the AI engine; all shared mutable state lock-guarded.
- Testing: JUnit 5 + Mockito for Java (services unit-tested with mocked repositories; controllers integration-tested), pytest for Python (Groq client mocked — no live API calls in tests).
- Three-service CI-less deployment: Vercel (static), Render Docker (backend), Render Python runtime (engine); configuration via `render.yaml` and environment variables.

---

## 8. Technology Stack Summary Table (include in the paper)

| Layer | Technology |
|---|---|
| Frontend | HTML5, CSS3, Vanilla JavaScript (no framework), Vercel |
| API Gateway / Core | Spring Boot 3, Java 21, Maven, Spring Security (JWT), Spring Data JPA, Docker, Render.com |
| AI Service | Python 3.11, FastAPI, Uvicorn, Groq SDK, Render.com |
| LLM | Groq — `openai/gpt-oss-120b` (default) + 5 registered alternates |
| RAG | `pypdf` extraction, pure-Python paragraph-aware chunker (900/150 overlap), Okapi BM25 (`rank-bm25`) top-k retrieval; FAISS + `all-MiniLM-L6-v2` was the abandoned first prototype |
| Database | PostgreSQL (Supabase) via PgBouncer transaction pooling, Row-Level Security |
| Auth | Supabase Auth (JWT) |
| File storage | Supabase Storage |
| Code execution | Wandbox remote sandbox |

---

## 9. Evaluation Facts You May Use (do NOT invent others)

- Test suites at last verification: **80 pytest tests and 63 JUnit tests passing**.
- Token ledger verified live: atomic increments confirmed under real traffic; a real global-chat call recorded 576 tokens end-to-end.
- Quota enforcement verified live: usage endpoint returns 200 with correct remaining budgets; migrations applied with RLS active.
- Groq free-tier limits the system operates within: 30 requests/min, 1K requests/day, 8K tokens/min, 200K tokens/day (shared per API key).
- Ignite resume verified: interrupted generations continue, skipping completed files.
- RAG evaluation (the author will measure these during implementation and insert real numbers — leave clearly marked placeholders for them): prompt tokens per question, full-text-30K mode vs BM25 top-6 mode on the same document/questions (expected roughly ~7.5K → ~2–2.5K tokens); end-to-end answer latency for both modes; long-document coverage — accuracy on questions about content beyond the 30K-character cutoff (full-text mode cannot answer these by construction; BM25 mode can); BM25 index build time and memory for a representative ~100-page PDF.
- If the paper needs any other latency/throughput/accuracy numbers, insert placeholders like "[measured: X ms]" for the author to fill in — do not fabricate.

---

## 10. Honest Limitations (use in Limitations & Future Work)

- Retrieval is lexical (BM25), not semantic — paraphrased questions with little word overlap against the document can miss the relevant chunk; hybrid lexical+embedding retrieval on paid infrastructure is future work.
- The judge's output `normalize()` collapses newlines, which can mis-verdict multi-line-output problems.
- One known integration gap: the AI engine loads doc-chat history from a backend path that is not currently mapped, so history seeding fails silently for doc chats (fix pending a design decision).
- In-memory caches (RAG text, chat history) are per-instance — horizontal scaling would need a shared cache (e.g., Redis).
- Token estimation (chars/4) is a heuristic; the emergency reserve absorbs the estimation error, but tokenizer-exact pre-flight counting is future work.
- Free-tier cold starts still add first-request latency despite keep-alive.
- Future work candidates: hybrid BM25+embedding retrieval, WebSocket-based collaboration, more judge languages, provider auto-failover, per-feature budget sub-allocations, exact tokenizer-based admission control.

---

## 11. Writing Instructions

- Frame the contributions as: (1) a unified multi-tool AI developer platform on a three-service microservice architecture; (2) an atomic, fail-open, two-phase (estimate-then-settle) token-governance scheme for multi-tenant LLM access over a single shared API key; (3) an embedding-free BM25 retrieval-augmented generation pipeline that delivers full-document Q&A coverage within a 512 MB memory envelope and measurably reduces per-question token cost; (4) incremental streaming project generation with persistence/resume under strict rate limits; (5) a case study of engineering LLM products on zero-cost infrastructure.
- Related Work should position against: GitHub Copilot / general AI coding assistants, ChatGPT-style document Q&A / RAG systems, online judges (LeetCode/Codeforces-style), and AI app-builders (e.g., v0, GPT Engineer) — noting DevAssist AI's differentiator is unifying all of these behind one auth, one budget governor, and one deployment, on free infrastructure.
- Use formal academic tone, passive-voice-sparing IEEE style, numbered sections, and include at least one architecture figure description (you may describe the figure in text for the author to draw), one data-flow description for the token ledger, and the technology table from Section 8.
- Target length: a standard 6–8 page IEEE conference paper.
