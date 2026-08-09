# PROMPT: Write an IEEE conference paper about the following project

You are writing an IEEE-format conference paper (two-column, ~6 pages) about a real, deployed full-stack AI developer-assistance platform called **DevAssist AI**, built by Sayan Siddarth. Everything below is factual information about the implemented system — do not invent features that are not listed. Write in formal academic style with Abstract, Keywords, I. Introduction, II. Related Work, III. System Architecture, IV. Methodology / Implementation (one subsection per feature), V. Results & Discussion, VI. Limitations, VII. Conclusion & Future Work, References. Ask me for any measurements/metrics you need rather than fabricating numbers.

---

## 1. Project Overview

DevAssist AI is a unified, web-based AI-powered developer assistance platform aimed at students and developers. It integrates six features in one application: general AI chat, private document Q&A (RAG), AI code analysis, an online compiler, a competitive-programming platform, and an AI project generator. It is deployed entirely on free-tier cloud infrastructure, demonstrating that a multi-service LLM platform can run at zero infrastructure cost.

## 2. Technology Stack

- **Frontend:** Pure HTML/CSS/vanilla JavaScript (no framework). Monaco editor for code editing. Deployed on Vercel (static hosting). Dark themed UI (Inter + JetBrains Mono, teal #06f5cd → purple #7c3aed brand gradient).
- **Backend:** Spring Boot 3 (Java 21), layered Clean Architecture (Controller → Service → Repository → Entity), constructor injection, DTOs as Java records. Dockerized, deployed on Render.com.
- **AI Engine:** Python 3.11 FastAPI microservice, deployed on Render.com. All LLM calls go through a single `safe_generate` wrapper with a provider abstraction layer.
- **LLM Providers (switchable via one env var):**
  - Groq API — model `openai/gpt-oss-120b` (fast inference)
  - NVIDIA NIM API — model `deepseek-ai/deepseek-v4-pro` (OpenAI-compatible endpoint)
- **Database & Auth:** Supabase — PostgreSQL (via PgBouncer transaction pooling), Supabase Auth (JWT), Supabase Storage for uploaded documents.
- **Code execution sandbox:** Wandbox public API (compiles/runs user code for the Online Compiler and VoidMain judge).
- **RAG stack:** sentence-transformers `all-MiniLM-L6-v2` embeddings + FAISS vector index; custom document loader and text splitter; heavy libraries lazy-imported at request time so the free-tier server cold-starts fast.
- **Resilience:** a KeepAliveService in the backend pings the AI engine every 14 minutes to prevent Render free-tier cold starts; retry-with-backoff around LLM calls; per-user token usage ledger (token_ledger service + Usage API) for quota control on free LLM tiers.

## 3. Architecture

Three independently deployed services:

```
Browser (Vercel static frontend)
   │  HTTPS + Supabase JWT (authFetch attaches token)
   ▼
Spring Boot Backend (Render, Docker)  ── JPA ──► Supabase PostgreSQL
   │  REST                                        (per-user isolation by
   ▼                                               Supabase UUID on every row)
FastAPI AI Engine (Render)
   ├─► Groq / NVIDIA NIM (LLM inference, provider-switchable)
   ├─► FAISS + sentence-transformers (RAG)
   └─► thread-locked in-memory caches (rag_cache, chat histories)
```

Security model: every user is identified by their Supabase UUID; every database entity (chats, documents, submissions, projects) carries the user id and all endpoints filter by it; JWT is validated by a Spring Security filter; all secrets come from environment variables only.

## 4. Features (one paper subsection each)

### 4.1 Global AI — general-purpose chat assistant
ChatGPT-style assistant for any programming/study question. Streaming responses with typing indicator, markdown + syntax-highlighted code blocks (highlight.js), copy buttons, multi-model selector (user can switch LLM per chat), persistent chat history with pin/rename/delete/search (entities: GlobalChatThread, GlobalMessage), export conversation to PDF (jsPDF), and an optional "Local AI" mode that guides the user to run Llama 3 locally via Ollama and connects to it — giving privacy-conscious users a fully offline option.

### 4.2 Secret AI — private document Q&A (RAG)
User uploads a document (PDF/DOCX/TXT, ≤50 MB) → stored per-user → text extracted, chunked by a custom splitter, embedded with all-MiniLM-L6-v2, indexed in FAISS. Questions retrieve top-k chunks and the LLM answers grounded in the retrieved context only. Per-user document library with search and selection; separate private chat history per document (entities: Document, SecretMessage, ChatThread). Called "Secret" because answers never draw from outside the user's own uploaded documents.

### 4.3 Code Analyzer
User pastes code (Java, Python, C, C++, JavaScript) into a Monaco editor. The LLM returns structured analysis rendered in separate panels: detected errors, improvement suggestions, an optimized version of the code (with copy + version-control diff view comparing original vs optimized), current vs optimal time complexity (e.g. O(n²) → O(n)), and a Chart.js performance graph visualizing the complexity difference. Token-limit control lets users cap request size.

### 4.4 Online Compiler
Browser IDE (Monaco) supporting multiple languages; code is compiled and executed remotely through the Wandbox sandbox API; stdin support and output/error display. Serves as a zero-setup practice environment.

### 4.5 VoidMain — competitive programming platform
LeetCode-style judge: curated problem set (VmProblem), users submit solutions which are executed against expected output via the sandbox, verdicts stored (VmSubmission), per-user history, and a leaderboard (VmLeaderboard) ranking users. Includes its own compiler page and problem browser.

### 4.6 Ignite — AI Project Builder (newest feature)
From a single natural-language prompt, the system generates a complete multi-file frontend project (HTML/CSS/JS only). Pipeline: (1) LLM produces a project plan/architecture (file list) via a `plan` endpoint; (2) files are generated sequentially with live progress streamed to the UI — the file tree populates in real time with a step checklist and progress bar; (3) user can open any generated file in a syntax-highlighted code viewer, iterate via follow-up chat messages, download the whole project as a ZIP, or open a live in-browser preview of the generated site with desktop/tablet/mobile viewport toggle. Projects are persisted per user (IgniteProject entity). Sequential generation with retry/backoff respects free-tier LLM rate limits.

## 5. Engineering contributions worth highlighting in the paper

1. **Zero-cost deployment architecture** — full multi-service LLM platform on free tiers (Vercel + Render + Supabase + free LLM APIs), with concrete mitigations for free-tier constraints: keep-alive pinging against cold starts, lazy imports for fast boot, sequential LLM calls with backoff, per-user token ledger.
2. **Provider-abstraction layer** — one env-var switch between Groq and NVIDIA NIM at runtime; the platform survived a real provider outage (NVIDIA NIM silently queueing requests) by flipping one config line, no code change.
3. **Strict multi-tenant isolation on shared free infrastructure** — Supabase UUID as universal isolation key across all six features.
4. **Hybrid privacy model** — cloud LLMs for convenience, RAG restricted to user-owned documents, and an optional local Ollama path for full offline privacy.
5. **Unified design system** across six functionally different tools (chat, RAG, analysis, IDE, judge, generator) with per-feature accent colors on a shared dark theme.

## 6. Honest limitations (for the Limitations section)

- Free-tier LLM quotas cap throughput; generation latency depends on third-party APIs (90 s timeout with 3 retries observed).
- Ignite generates frontend-only projects (no backend code generation yet).
- RAG index and chat caches are in-memory on the AI engine, so they reset on service restart (Render free tier restarts frequently).
- Code execution relies on the public Wandbox API (availability not guaranteed).
- Output normalization in the judge can cause false Wrong-Answer verdicts on multi-line-output problems (known issue).
- No horizontal scaling; single free-tier instance per service.

## 7. Future work (real roadmap)

Theme unification rollout across all pages; backend generation in Ignite; persistent vector store (e.g. pgvector on Supabase); replacing silent free-tier failures with user-visible quota messages; mobile-responsive layouts; more judge problems and contest mode.

## 8. Suggested paper metadata

- Title candidates: "DevAssist AI: A Zero-Cost, Multi-Service LLM Platform for Developer Assistance" / "An Integrated AI Developer Assistant on Free-Tier Cloud Infrastructure"
- Keywords: Large Language Models, Retrieval-Augmented Generation, Developer Tools, Code Analysis, Cloud Computing, Microservices, FAISS, Competitive Programming, AI Code Generation
- The author can supply screenshots (design mockups and live pages exist) and can measure: response latency per provider, RAG retrieval accuracy on sample docs, Ignite generation time per project size, token usage per feature — ask for these instead of inventing numbers.
