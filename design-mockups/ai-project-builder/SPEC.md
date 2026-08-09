# AI Project Builder — Feature Spec

> Feature name is TEMPORARY. It will be renamed later — keep the name in ONE config/constant so it can be changed in one place.

## What it does
User writes a detailed prompt describing a **frontend-only** project (HTML/CSS/JS). The LLM generates the complete project file-by-file, shown live in the UI. User can then view any file's code, download everything as a ZIP, and open a live preview.

## LLM Provider
DeepSeek model served via **NVIDIA NIM API** (OpenAI-compatible endpoint, `https://integrate.api.nvidia.com/v1`). API key must come from an environment variable — never hardcoded (see CLAUDE.md rules). Expect strict rate limits: generate files sequentially with retry/backoff, not in parallel.

## UI Reference — mockup images in this folder
Look at these 4 images before building the UI (Claude Code: read them with the Read tool):

1. `ai-project-builder-1-start-screen.png` — Start screen: hero heading, large prompt textarea, chips (attach reference, theme, HTML·CSS·JS), gradient "Generate Project" button, 4 template quick-start cards (Portfolio, Landing Page, Dashboard, Mini Game), model badge "DeepSeek · NVIDIA NIM" in top bar.
2. `ai-project-builder-2-generating.png` — Generating state: left chat column shows user prompt + step checklist (done ✓ / active spinner / pending) + progress bar with file count and ETA. Right workspace: file tree fills in automatically as files are created (NEW badges, "writing…" spinner on current file, pending files dimmed), editor shows code streaming with cursor. Download/Preview buttons disabled while generating.
3. `ai-project-builder-3-complete-code-viewer.png` — Complete state: full file tree; clicking a file opens it in the editor with line numbers + syntax highlighting; editor tabs for open files; buttons: Copy file, Live Preview, Download ZIP (gradient); completion message in chat + suggestion chips; follow-up chat input ("Ask for changes…") for iterating on the generated project.
4. `ai-project-builder-4-live-preview.png` — Preview state: Code/Preview tab toggle; generated site rendered in an iframe styled as a browser window (URL bar, traffic lights); device-size toggle Desktop/Tablet/Mobile; Open in new tab; Download ZIP.

`mockup.html` in this folder is the live HTML version of all 4 screens (open with `?screen=1`…`4`). Reuse its CSS values directly — it already matches the existing DevAssist design system (#0a0a0a dot-grid background, Inter + JetBrains Mono, teal→purple gradient #06f5cd→#7c3aed, glass panels).

## Functional requirements
1. Prompt textarea for the user's detailed project description.
2. File/folder tree panel that populates **automatically in real time** while the LLM creates files.
3. Loading/progress indication during generation (step checklist + progress bar + per-file spinner) until the full project completes.
4. Download button — ZIP of the entire generated project.
5. Live preview — render the generated project in an iframe / new tab.
6. Clicking any file in the tree shows its code (line numbers, syntax highlighting).
7. Extras shown in mockups: follow-up chat to request changes, suggestion chips after completion, copy-file button, device-size toggle in preview, template quick-start cards.

## Integration constraints (from CLAUDE.md — follow them)
- Frontend: vanilla JS only, new page in `frontend/` + mirror copy in `backend/devassist-backend/src/main/resources/static/`. Protect page with `requireAuth()`, use `authFetch()` and `BACKEND_URL` from `auth-guard.js`.
- Backend (Spring Boot, Java 21): controller → service → repository layering, constructor injection, records for DTOs, kebab-case endpoints (e.g. `/api/project-builder`). `@Transactional` at service layer.
- Decide and propose: whether LLM calls go through the existing Python AI engine (`core/llm.py:safe_generate` pattern) or a new provider path for NVIDIA NIM — discuss before implementing.
- Tests required: JUnit 5 + Mockito for Java, pytest with mocked LLM client for Python.
- Never commit the NVIDIA API key. Document the new env var in `render.yaml` and `LOCAL_SETUP_GUIDE.md`.

## Deployment constraint — FREE TIER ONLY
The whole project deploys on free-of-cost services (currently: Render.com free tier for backend + AI engine, Vercel free for frontend, Supabase free for DB/auth). Everything in this feature must fit those limits:
- No paid infra: no Redis, no message queues, no paid storage, no WebSocket services that need a paid plan.
- Render free tier: cold starts, 512 MB RAM, request timeout (~100s on free) — long generations must survive this. Prefer SSE streaming or chunked/polling generation over one giant blocking request.
- Generated projects: keep in memory or Supabase Storage free tier (1 GB) — auto-clean old projects. ZIP is built on demand, not stored.
- NVIDIA NIM free credits are limited — add rate limiting per user, sequential generation, retry with backoff, and a clear "busy, try again" message when quota is hit.

## User-friendliness requirements
- Clear feedback at every moment: never a frozen screen — always show what is happening (spinner, step text, progress).
- Friendly error messages in plain language (not stack traces): quota exceeded, prompt too vague, generation failed with a Retry button.
- Empty states explain what to do (placeholder examples in prompt box, template cards for one-click start).
- Works on smaller screens; sensible keyboard behavior (Enter to submit, Shift+Enter newline).
- If the user's prompt is too short/vague, ask them to add detail instead of generating something bad.

## Process rule for Claude Code
Before writing the plan or any code: **if anything in this spec is ambiguous or a decision is needed, ask the user (Sayan) all clarifying questions FIRST, in one batch.** Wait for answers, then produce the implementation plan, get approval, then code. Do not assume silently.

## Suggested approach
Plan first (architecture + endpoint design + streaming strategy: SSE or polling for file-by-file progress), get approval, then implement in phases: (1) backend/AI-engine generation endpoint, (2) frontend page skeleton from mockup.html, (3) real-time tree + code viewer, (4) ZIP download + live preview, (5) follow-up edit chat.
