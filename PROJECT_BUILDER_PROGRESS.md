# Ignite (AI Project Builder) — Progress & Handoff Document

> **Purpose**: complete context for continuing this work in any future session.
> Last updated: 2026-07-06. Feature status: **BUILT + APP-WIDE TOKEN BUDGETS, ALL TESTS GREEN — NOT YET COMMITTED TO GIT.**
>
> ⚠️ This file must NEVER contain real passwords or API keys. Real values live only in
> gitignored files: `local-secrets.ps1` (backend) and `ai-engine/.env` (AI engine).

---

## 1. What Ignite is

"Ignite" is the finalized name (chosen by Sayan, 2026-07-02) for the AI Project Builder:
the user describes a frontend-only project (HTML/CSS/JS) in a prompt; an LLM generates it
file-by-file with live streaming into an editor UI; the user can inspect code, run a live
preview, download a ZIP, and request follow-up edits via chat.

- Spec: `design-mockups/ai-project-builder/SPEC.md` (+ 4 PNG mockups + `mockup.html` in the same folder)
- The display name lives in ONE constant: `FEATURE.NAME` in `frontend/ignite.html`
- Technical identifiers stay `project-builder` (routes) — renaming the display name never touches code paths

## 2. Approved architectural decisions (user-confirmed — do not re-litigate)

1. **LLM calls live in the Python AI engine**, Spring Boot proxies with JWT auth + rate limiting
   (same pattern as `/global-ai`). Frontend never calls the AI engine or LLM vendor directly.
2. **Provider independence is a hard requirement**: providers live in a registry
   (`PROVIDER_REGISTRY` in `ai-engine/core/llm_providers.py`) — switch via
   `PROJECT_BUILDER_PROVIDER=groq|nvidia`; each provider reads its own
   `PROJECT_BUILDER_MODEL_<PROVIDER>` variable. Zero code changes to switch; a future
   provider is one class + one registry entry.
3. **Model**: `openai/gpt-oss-120b` on Groq (active since 2026-07-03 — NVIDIA NIM's deepseek
   queue backed up with ~600 queued requests, causing 90s read timeouts → 502s).
   NVIDIA NIM (`deepseek-ai/deepseek-v4-pro`) remains registered for switch-back;
   `chat_template_kwargs: {"thinking": false}` is sent for DeepSeek models.
4. **Incremental generation**: one `plan` call (strict JSON), then ONE streaming call PER FILE,
   sequential — fits Render free tier ~100s request timeout and NIM rate limits. Client
   orchestrates the loop and retries failed files individually (2 retries with backoff).
5. **v1 is ephemeral**: no DB persistence. Project lives in browser memory; user keeps it via
   client-side ZIP (JSZip CDN). Live preview via blob-URL iframe. Saved-projects history = phase 2.
6. Deferred in v1: "Attach reference" chip (shown disabled, "coming soon").

## 3. Request flow

```
Browser (ignite.html, authFetch + Supabase JWT)
  → Spring Boot /api/project-builder/{plan|generate-file|edit}   (auth + per-user rate limit)
    → FastAPI /project-builder/{plan|generate-file|edit}         (prompts, validation, streaming)
      → core/llm_providers.get_provider()                        (NVIDIA NIM or Groq via env)
```

- Per-user rate limits (protect free NIM credits): `RateLimitService`, in-memory sliding window,
  defaults 6 plans/hour + 120 files/hour, configurable via `ignite.rate-limit.*` in
  `application.properties` (env: `IGNITE_PLANS_PER_HOUR`, `IGNITE_FILES_PER_HOUR`).
- Streaming: Spring proxies bytes unbuffered via `StreamingResponseBody`; errors after headers
  are sent in-band as `ERROR: ...` text (existing frontend convention from /rag-ask).
- Vague prompts: AI engine returns `{"status":"needs_detail","message":...}` instead of junk.

## 4. Files created / modified (all uncommitted as of 2026-07-03)

### AI Engine (Python)
| File | Change |
|---|---|
| `ai-engine/core/llm_providers.py` | NEW — NvidiaNimProvider + GroqProvider + get_provider(), retry/backoff, ProviderError/ProviderBusyError |
| `ai-engine/services/project_builder.py` | NEW — create_plan, generate_file_stream (fence/think stripping), apply_edit, path safety, context budgeting |
| `ai-engine/api/api_server.py` | MODIFIED — JSONResponse import; PROJECT BUILDER (IGNITE) section at end: 3 routes + pydantic models |
| `ai-engine/requirements.txt` | MODIFIED — added `pytest` |
| `ai-engine/tests/{conftest,test_llm_providers,test_project_builder}.py` | NEW — 30 tests, providers always mocked |
| `ai-engine/render.yaml` | MODIFIED — envVars incl. NVIDIA_NIM_API_KEY (sync:false), PROJECT_BUILDER_* |

### Backend (Spring Boot, Java 17 per pom)
| File | Change |
|---|---|
| `controller/ProjectBuilderController.java` | NEW — /api/project-builder/{plan,generate-file,edit} |
| `service/ProjectBuilderService.java` | NEW — proxy + streaming copy + snake_case payload mapping |
| `service/RateLimitService.java` | NEW — per-user sliding window (Clock-injectable for tests) |
| `service/{RateLimitExceededException,AiEngineException,AiEngineBusyException}.java` | NEW |
| `dto/{ProjectPlanRequest,ProjectFileRequest,ProjectEditRequest}.java` | NEW — records |
| `security/JwtUtil.java` | **REWRITTEN** — now verifies BOTH legacy HS256 (shared secret) AND ES256 via Supabase JWKS (`/auth/v1/.well-known/jwks.json`, cached, kid-based). This fixed app-wide auth (see §6). |
| `resources/application.properties` | MODIFIED — `ignite.rate-limit.*` section |
| `resources/static/ignite.html` | NEW — mirror of frontend/ignite.html |
| `resources/static/dashboard.html` | MODIFIED — 🔥 Ignite nav link |
| `src/test/.../{ProjectBuilderControllerTest,ProjectBuilderServiceTest,RateLimitServiceTest,JwtUtilTest}.java` | NEW — 32 tests |

### Frontend
| File | Change |
|---|---|
| `frontend/ignite.html` | NEW — entire feature UI, single file. States: start → workspace(generating→complete) + Code/Preview tabs. JSZip + blob-URL preview + device toggle + edit chat. `FEATURE` constant at top. |
| `frontend/dashboard.html` | MODIFIED — 🔥 Ignite nav link |

### Root / docs
| File | Change |
|---|---|
| `render.yaml` | MODIFIED — ai-engine envVars for Ignite |
| `LOCAL_SETUP_GUIDE.md`, `PROJECT_STATUS.md`, `QUICK_START.md` | MODIFIED — real secrets scrubbed to placeholders + Ignite env vars documented |
| `CLAUDE.md` | MODIFIED — real password removed from the "wrong example" |
| `local-secrets.ps1`, `ai-engine/.env` | (gitignored) — rotated DB password + NVIDIA key + PROJECT_BUILDER_* vars live here |

## 5. Tests — how to run

```powershell
# Python (61 tests)
cd ai-engine; .\venv\Scripts\python.exe -m pytest tests -q

# Java (43 tests)
cd backend\devassist-backend
mvn test "-Dtest=RateLimitServiceTest,ProjectBuilderServiceTest,ProjectBuilderControllerTest,JwtUtilTest,SecurityConfigStreamingTest,UsageControllerTest"
```
All 104 were green as of 2026-07-05. LLM providers and the token-ledger DB are always
mocked in tests — no API credits burned, no database touched.

## 6. Critical fixes made along the way

- **JWT auth was broken app-wide for this Supabase project**: Supabase signs tokens with ES256
  (asymmetric), old `JwtUtil` only did HS256 → every authenticated route returned 403. Fixed via
  JWKS verification (see §4). Older features never noticed because their routes are `permitAll`
  in `SecurityConfig` — that is itself tech debt worth revisiting (see bug B4).
- **B3 CLOSED**: DB password rotated in Supabase (2026-07-03), verified connecting. Real secrets
  scrubbed from all committable files (they were in CLAUDE.md, LOCAL_SETUP_GUIDE.md,
  PROJECT_STATUS.md, QUICK_START.md). New password only in gitignored files.
- Frontend auth errors now say "log out and log back in (HTTP 401/403)" instead of a vague
  "unexpected response".

## 6b. Session 2026-07-03→04: Groq switch + streaming/preview/limit fixes (all verified live)

1. **Provider switched to Groq `openai/gpt-oss-120b`** — NVIDIA NIM's deepseek queue backed up
   (~600 queued requests → 90s read timeouts → 502s). `core/llm_providers.py` now has a
   `PROVIDER_REGISTRY` dict + `LLMProvider` protocol (new provider = one class + one entry),
   default provider `groq`, unknown names raise. Per-provider model env vars
   `PROJECT_BUILDER_MODEL_GROQ` / `PROJECT_BUILDER_MODEL_NVIDIA` replaced the shared
   `PROJECT_BUILDER_MODEL` (which leaked the wrong model id across providers).
   Updated: `ai-engine/.env`, both `render.yaml`s, `LOCAL_SETUP_GUIDE.md`.
2. **Backend streaming 403 fixed** (plan worked, every generate-file failed "session rejected"):
   - `SecurityConfig`: `.dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` — JwtFilter only
     authenticates the initial REQUEST dispatch; the ASYNC re-dispatch (StreamingResponseBody)
     and ERROR dispatch arrived anonymous. Before this fix EVERY backend error (404/500)
     surfaced to clients as an empty 403 (protected /error page).
   - `ProjectBuilderController.generateFile` now returns `ResponseEntity<StreamingResponseBody>`
     (wildcard `<?>` made Spring route the stream through message converters → 500);
     error paths (400/429) are written as small streamed JSON, same wire format.
   - New `SecurityConfigStreamingTest` (MockMvc drives the real chain incl. asyncDispatch).
3. **Ignite live preview fixed** — sandboxed iframe (opaque origin, correctly no
   allow-same-origin) cannot fetch parent-created blob: URLs → css/js never loaded.
   `buildPreview()` now folds the project into ONE document: css/js inlined, other assets
   as data: URLs (transform verified with node checks). Badge now "GPT-OSS-120B · Groq".
   Mirror copy in backend static/ updated (files identical).
4. **Groq free-tier token limits handled** — gpt-oss-120b on-demand: 30 req/min, 1K req/day,
   **8K tokens/min, 200K tokens/day**; TPM counts input + reserved max_tokens ("Requested
   8127, Limit 8000" killed late/context-heavy files, i.e. the JS):
   - `MAX_CONTEXT_CHARS` 24000→10000, env-tunable (`PROJECT_BUILDER_CONTEXT_CHARS`);
     `MAX_FILE_TOKENS` env-tunable (`PROJECT_BUILDER_MAX_FILE_TOKENS`, default 4096).
     Guard test asserts chars/4 + max_tokens + 1000 ≤ 8000.
   - Groq 413 `rate_limit_exceeded` now classified `ProviderBusyError` → 429 busy → frontend
     auto-retries (was hard 502 "file failed").
   - `spring.mvc.async.request-timeout=300000` (streams outlived Tomcat's ~30s default
     during rate-limit retries → AsyncRequestTimeoutException).

Tests after this session: **38 pytest + 34 JUnit green** (the only red is the pre-existing
`DevassistBackendApplicationTests` context-load test which needs live DB creds).
Still nothing committed; Render env vars still not updated.

## 6c. Session 2026-07-05: token-usage tracking + daily budgets (§9 step 1 — DONE)

Implemented exactly as approved 2026-07-04; **61 pytest + 43 JUnit green**.

- **DB**: `db/migrations/2026-07-05_create_token_usage.sql` — `token_usage(user_email, date,
  tokens_used)` PK (user_email, date), RLS enabled with no policies (engine connects as owner).
  ✅ APPLIED to Supabase 2026-07-06 (user-approved); verified live — `GET
  /project-builder/usage` returns 200 with zeroed ledger + limits.
- **AI engine**: new `services/token_ledger.py` (check_budget / record_usage / get_usage,
  UTC days, FAILS OPEN on DB errors). Providers accept `usage_callback` — Groq reads exact
  usage from `x_groq.usage` on the final stream chunk (`response.usage` non-stream); NIM
  reads the `usage` field. `project_builder.{create_plan,generate_file_stream,apply_edit}`
  take `user_email`, check budget BEFORE each call, record after. Budgets:
  `PROJECT_BUILDER_USER_DAILY_TOKENS` (20K) / `PROJECT_BUILDER_GLOBAL_DAILY_TOKENS` (180K).
  Over-budget → 429 `{"status":"quota","scope":"user"|"global","message":...}` (distinct
  from "busy"). New route `GET /project-builder/usage?user_email=`.
- **Backend**: all 3 engine payloads now carry `user_email`; engine 429 quota/busy messages
  pass through to clients (`AiEngineBusyException(message)`); streamed quota errors are
  tagged in-band `ERROR: [quota:scope] message`. New `UsageController` →
  `GET /api/usage` (JWT-protected) proxying the engine via `ProjectBuilderService.getUsage`
  (strict URI-template encoding — "+" in emails survives).
- **Frontend** (`ignite.html`, mirror synced byte-identical): 🔋 topbar meter
  "X / 20,000 tokens today" (amber ≥80%, red at limit), refreshed on load / after plan /
  after each file / after edits; `[quota:...]` stream errors stop the retry loop and show
  the engine's message (user-quota vs app-capacity wording comes from the engine).
- **Docs**: budget env vars added to both `render.yaml`s and `LOCAL_SETUP_GUIDE.md`.

## 6d. Session 2026-07-06: token budgets extended to ALL LLM features (user-approved)

User decision: one daily wallet per user across the whole app (was Ignite-only), login
required on every token-consuming route. Budgets raised to **22K/user/day, 190K global/day**
(8 users × 22K = 176K; the leftover 14K is deliberately reserved — the user "has a plan"
for it, do not spend it). Verified live: /global-ai call recorded 576 tokens in the ledger.

- **AI engine**: `core/llm.py:safe_generate` is the single choke point — takes `user_email`,
  calls `check_budget` before / `record_usage` after (reads `response.usage.total_tokens`).
  `QuotaExceededError` + `quota_response()` now module-level in `api_server.py`. Routes
  `/global-ai` + `/code-analyze` → 429 `{"status":"quota","scope",...}`; `/rag-ask` +
  `/local-ai` fallback → in-band `ERROR: [quota:scope] message` (Ignite stream convention).
  `AIRequest`/`RagRequest`/`CodeRequest` gained `user_email`. `token_ledger.py`:
  `FEATURE_NAME "Ignite"` → `SERVICE_NAME "DevAssist AI"`, defaults 22000/190000
  (env names unchanged: `PROJECT_BUILDER_*_DAILY_TOKENS`).
- **Backend**: `/api/global-chat/{ask,local-ask}`, `/ai/rag`, `/ai/rag/{docId}`,
  `/ai/ask/**`, `/ai/analyze` now `authenticated()` in SecurityConfig (matched BEFORE the
  permit-all subtrees) and un-skipped in JwtFilter; controllers forward
  `authentication.getName()` as `user_email` and pass engine 429s through (JSON body for
  ask/analyze; `[quota:` in-band text mapped to 429 for rag). ⚠ `GET /api/global-chat/{id}`
  and `/ai/messages/{id}` MUST stay public — the AI engine itself calls them (no JWT) to
  load chat history. `CodeAnalyzerController` refactored to constructor-injected RestTemplate.
- **Frontend** (mirrors synced byte-identical): 🔋 meter added to `global.html`,
  `secret.html`, `code-analyzer.html` (ignite.html already had it; tooltip now app-wide);
  each refreshes after AI calls and shows the engine's message on 429.
  `secret.html` `/ai/rag` call switched `fetch` → `authFetch` (mandatory now).
- **Tests**: 72 pytest (+11: `test_safe_generate_ledger.py`, `test_api_quota.py`;
  test_token_ledger defaults updated to 22K/190K) and 53 JUnit (+10:
  `GlobalChatControllerTokenTest`, `CodeAnalyzerControllerTest`, `AiControllerRagTokenTest`)
  all green. Only pre-existing red: `DevassistBackendApplicationTests` (needs live DB).

## 6e. Session 2026-07-06 (later): production cleanup — dead code + junk files (user-approved deletions)

User gave blanket approval to delete unused files this one time. Deleted (all verified
unreferenced by grep before removal; 72 pytest + 53 JUnit green after; services smoke-tested):

- **Root junk**: curl_err.txt, curl_out.txt, run.log, startup.log, test_backend_api.py,
  test_doc.txt, start-devassist.bat (was broken — never passed DB password; docs updated
  to point at run-local.ps1).
- **Backend junk**: compile_output.txt, filelist.txt, resources_list.txt, run_output.txt,
  HELP.md (Initializr boilerplate).
- **Backend dead code**: TestController.java (`/test` demo), UserController.java
  (`/api/user/profile` placeholder), AiIntegrationService.java (tech-debt B6),
  AIService.java (hardcoded 127.0.0.1), dto/AiRequest.java, utils/FileProcessor.java
  (+ empty utils/ folder), entity/Message.java + repository/MessageRepository.java
  (only self-referenced). `User.password` field/ctor/getter/setter removed (kept the
  users table column untouched; ddl-auto=none).
- **AI engine**: test_api.py, test_groq.py (manual scripts), sample.pdf,
  rag/question_answering.py (FAISS path, bypassed in prod; its lone import in
  api_server.py was unused). Also removed dead `get_chat_history()` in api_server.py
  and the three `__main__` demo blocks in rag/ that referenced sample.pdf;
  numpy import moved inside create_vector_store (lazy-import rule).
- **Frontend**: update_logo.py (one-off script; both frontend + static mirror copies).

KEPT deliberately: AuthController/AuthService/User/UserRepository (working Supabase user
sync — no frontend caller today but functional, deleting is a product decision);
all frontend pages; .kiro/ specs; design-mockups/; docs.

🐛 NEW BUG found during cleanup (not fixed — needs task): the AI engine loads doc-chat
history via `GET {BACKEND_URL}/api/messages/{id}` (api_server.py, rag-ask route) but NO
backend controller maps `/api/messages/**` → history load fails silently on every doc
chat. Candidates: point it at `/api/chat/{id}` (ChatController, secret_messages) or add
the missing mapping. Also noted in CLAUDE.md tech-debt list.

## 6f. Session 2026-07-06 (later still): Shared Global Token Pool with Per-User Hard Quota

User-specified algorithm, implemented and verified live. Values: provider 200K/day,
emergency reserve 10K (NEVER spent — absorbs post-paid in-flight overshoot), usable pool
190K (= provider − reserve, derived), per-user 25K/day. Nothing pre-allocated: tokens
leave the pool only after the provider reports actual consumption.

- **DB** (`db/migrations/2026-07-06_create_daily_usage_tables.sql`, APPLIED + seeded from
  token_usage): `user_daily_usage(user_email, usage_date, prompt/completion/total,
  updated_at)` + `global_daily_usage(usage_date, totals, updated_at)` — global pool is an
  O(1) atomic counter, not a SUM. Rows keyed by UTC date → daily reset automatic. User
  identity = email (this app's user id everywhere; spec's "user_id" adapted). Old
  token_usage table KEPT — drop needs approval once prod verified.
- **token_ledger.py rewritten**: env `AI_PROVIDER_DAILY_TOKENS` / `AI_EMERGENCY_RESERVE_TOKENS`
  / `AI_USER_DAILY_TOKENS` / optional `AI_GLOBAL_DAILY_TOKENS` override (legacy
  PROJECT_BUILDER_* honored as fallback). `check_budget(email, estimated_tokens)` rejects
  pre-flight if estimate (chars/4 + reserved max_tokens, via `estimate_tokens()`) would
  exceed either budget. `record_usage` upserts BOTH rows in ONE transaction with additive
  `x = x + delta` updates — concurrency-safe, ledgers can't drift. Still FAILS OPEN.
- **Wiring**: `safe_generate` records full {prompt,completion,total} and passes real
  estimates; project_builder's 3 call sites moved check after prompt assembly →
  `_check_budget_for(messages, max_tokens, email)`. Provider-agnostic unchanged: providers
  only report usage dicts (Groq/NIM today, any future provider = same contract).
- **/project-builder/usage** response: same old keys (meter-compatible) + new
  user/global prompt/completion breakdown + emergency_reserve.
- **Tests**: 80 pytest + 53 JUnit green. Live-verified: one /global-ai call moved user
  576→1146 and global 10560→11130 (both +570 = 512 prompt + 58 completion, atomic).
- Render env for deploy (replaces PROJECT_BUILDER_*_DAILY_TOKENS in §9 step 3):
  `AI_PROVIDER_DAILY_TOKENS=200000`, `AI_EMERGENCY_RESERVE_TOKENS=10000`,
  `AI_USER_DAILY_TOKENS=25000`.

## 6g. Session 2026-07-06 (evening): per-user PROJECT PERSISTENCE + resume (user-requested)

v1's "ephemeral only" decision (§2.5) is superseded: projects now auto-save to the DB per
user, so a quota-stopped build can be resumed tomorrow. Verified live (full CRUD via a
minted legacy-HS256 JWT — JwtUtil accepts HS256 signed with SUPABASE_JWT_SECRET, handy
for API smoke tests).

- **DB**: `db/migrations/2026-07-06_create_ignite_projects.sql` — APPLIED. One row per
  project: user_email owner, name/slug/summary, prompt, plan JSON, files JSON
  ({path: content}, done files only — pending files are derived from the plan), status
  generating|complete, timestamps. plan/files are text-serialized JSON (switch to jsonb
  later via ALTER if needed).
- **Backend** (chat-storage pattern, Clean Architecture): `IgniteProject` entity +
  `IgniteProjectRepository` (findByIdAndUserEmail = ownership) + `IgniteProjectService`
  (JSON (de)serialization, defaults, ProjectNotFoundException) + `IgniteProjectController`
  under `/api/project-builder/projects` (CRUD; JWT required — not in permit-all; owner
  ALWAYS from token, never the body; cross-user ids answer 404). List returns summaries
  without file payloads.
- **Frontend** (`ignite.html`, mirror synced): auto-save via a serialized `saveQueue`
  (POST create after plan → PUT after EVERY finished file → status complete at finish →
  after edits). Start screen gains "MY PROJECTS" cards (name/summary/status chip/date,
  ✕ delete w/ confirm). `openProject()` restores full state (plan+files, edit-added files
  re-attached to the plan); complete projects land ready (preview/edit); incomplete ones
  offer **⚡ Resume generation** → rebuilds the step list (done files pre-checked) and
  re-enters `generateAllFilesFrom(0)`, which skips done files. beforeunload warning now
  only fires for UNSAVED work (projectId null).
- **Tests**: 63 JUnit (+10: IgniteProjectServiceTest, IgniteProjectControllerTest) +
  80 pytest green.

## 7. Known bugs remaining (from 2026-06-28 analysis, re-verified 2026-07-02)

Open: **B1** (upload-doc vector-store crash), **B2** (rag_cache tuple/string mismatch),
**B4** (VoidMain username spoofable — `/vm/submit` trusts request body), **B5** (global chat
double-stored), **B6** (dead AiIntegrationService.java — deletion needs user approval),
**B7** (normalize() collapses newlines → false Wrong Answers), **B8** (Windows OCR paths),
**B9** (no rate limit on old AI endpoints). Fixed: B3, B10. Details in Claude's memory
(`project_known_bugs.md`) and CLAUDE.md's tech-debt section.

## 8. How to run locally

```powershell
.\run-local.ps1     # starts AI engine :8000, backend :8080, frontend :5500
# → http://localhost:5500/index.html → login → 🔥 Ignite in sidebar
```
(`start-devassist.bat` was deleted 2026-07-06 — it never passed the DB password; run-local.ps1 is the only launcher.)

## 9. EXACT NEXT STEPS (in order)

1. ~~**BUILD: token-usage tracking + daily budgets**~~ — **DONE 2026-07-05**; migration
   **APPLIED to Supabase 2026-07-06** and verified live (see §6c). Budgets are enforced.
   **EXTENDED 2026-07-06 to ALL LLM features** at 22K/user, 190K global (see §6d).
2. **BUILD: real RAG retrieval for Secret AI (BM25)** — user-approved 2026-07-10; full plan in
   `RAG_IMPLEMENTATION_PLAN.md` at repo root. **This is the FIRST work item when project work
   next starts.** Background: production `/rag-ask` does no retrieval (30K-char full-text
   stuffing; FAISS pipeline is dead code with deps removed from requirements.txt). Plan =
   pure-Python BM25 over chunks (`rank-bm25`), URL-keyed cache, small-doc bypass,
   `RAG_MODE` fail-open fallback. Record before/after token + accuracy measurements — they
   feed the IEEE paper (`IEEE_PAPER_PROMPT.md`).
3. **Commit everything** — user must say "commit". Suggested: one commit for the JWT/security
   fixes + secret scrubbing, one for the Ignite feature, one for the Groq switch + fixes,
   one for token budgets. Nothing is committed yet.
4. **Deploy**: user updates Render dashboard —
   devassist-backend: `SPRING_DATASOURCE_PASSWORD` (new rotated value);
   devassist-ai-engine: `DATABASE_URL` (new password), `GROQ_API_KEY`, `NVIDIA_NIM_API_KEY`,
   `PROJECT_BUILDER_PROVIDER=groq`, `PROJECT_BUILDER_MODEL_GROQ=openai/gpt-oss-120b`,
   `PROJECT_BUILDER_MODEL_NVIDIA=deepseek-ai/deepseek-v4-pro`,
   `AI_PROVIDER_DAILY_TOKENS=200000`, `AI_EMERGENCY_RESERVE_TOKENS=10000`,
   `AI_USER_DAILY_TOKENS=25000` (token-pool vars, see §6f).
   Then push to trigger deploys; verify Ignite on production URLs.
5. **Remaining bug fixes** (B1/B2 recommended first — see §7) after user picks scope.
6. Phase-2 ideas (not approved yet, ask first): ~~saved-projects history in Supabase~~
   (DONE 2026-07-06, see §6g), "Attach reference" upload, multi-page preview navigation,
   rate-limit UI countdown.

## 10. Session-resume protocol

If the user says **"shall we start"** / **"start"** (or similar) in a fresh session: read
this file, check `git status` to see whether commits happened, then continue from §9 —
step 2 (RAG retrieval, plan in `RAG_IMPLEMENTATION_PLAN.md`) is the agreed starting point
as of 2026-07-10.
Claude's persistent memory also holds: `project_ignite_feature.md` (decisions + status),
`project_known_bugs.md` (bug list). Trust this file over memory if they disagree —
this one is updated last.
