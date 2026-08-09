

# Circuit — Feature Spec

> Feature name: **Circuit** (final, chosen by Sayan 2026-07-12). Keep the display name in ONE
> constant (`FEATURE.NAME` at the top of `frontend/circuit.html`) so it can be renamed in one place.
> Technical identifiers (routes, files) use `circuit`.

## What it does
User asks a question — an algorithm ("binary search"), a concept ("how does HTTPS work"), or a
plan ("roadmap to become a frontend developer") — and Circuit answers with a **visual, animated,
step-by-step diagram** instead of a wall of text. The user can play/pause/step through the
explanation (like a video player), click steps to jump, and **interact with the output** (e.g.
change the binary-search target and re-run). Works for programming AND non-programming topics.

## Core architectural idea — LLM returns a JSON "circuit spec", frontend renders it
The LLM must NOT return HTML/JS. It returns a strict JSON structure the frontend knows how to
render (same philosophy as Ignite's plan call). Proposed spec shape:

```json
{
  "title": "Binary Search",
  "viz_type": "array | flowchart | bars | sequence | text-steps",
  "intro": "one-paragraph chat explanation",
  "data": { "...viz-type-specific payload, e.g. array values + target..." },
  "steps": [
    { "short": "step list label",
      "note": "caption shown under the canvas (may contain <b>/<span class=mono>)",
      "state": { "...viz-type-specific: lo/hi/mid, node index, bar order, highlight ids..." } }
  ],
  "suggestions": ["follow-up chip 1", "chip 2"],
  "interactive": { "kind": "none | target-input | ...", "label": "target" }
}
```

- **v1 renderers (build these 3, in this order)**: `array` (binary search & friends: cells,
  pointers, dim/highlight/found), `bars` (sorting), `flowchart` (roadmaps / processes: ordered
  stages with tags + durations). Everything else falls back to `text-steps` (numbered steps with
  captions, still playable) — so ANY question gets a usable answer even without a dedicated renderer.
- Local interactivity (e.g. new binary-search target) is computed **client-side** when the viz
  type supports it (array/bars renderers can regenerate steps in JS without a new LLM call —
  zero extra tokens). A "re-ask the LLM" fallback covers the rest.
- Validate the JSON in the AI engine (pydantic) before returning; on invalid JSON retry once with
  a "fix your JSON" reprompt, then return a friendly error.

## UI Reference — `mockup.html` in this folder (INTERACTIVE — open it in a browser)
The mockup is fully working with 3 canned demos; the real feature replaces the canned data with
the LLM circuit-spec. Reuse its CSS directly (it matches the DevAssist design system: #0a0a0a
dot-grid, Inter + JetBrains Mono, teal→purple gradient #06f5cd→#7c3aed, glass panels). It shows:

1. **Start screen** — hero, prompt textarea (Enter submits), feature chips, 4 example cards,
   token meter + model badge in topbar.
2. **Workspace** — left chat column (user msg → AI intro → clickable numbered step list synced
   with the player → suggestion chips → follow-up input) + right canvas (viz area, caption bar,
   player: restart/prev/play-pause/next, scrubber, step counter, speed 0.5/1/2×; keyboard
   Space/←/→) + Export SVG, Copy steps, New question buttons.
3. **The three renderers** described above, including the "play with it" target input for arrays.

## Request flow (same pattern as Ignite — reuse it)
```
Browser (circuit.html, authFetch + Supabase JWT)
  → Spring Boot POST /api/circuit/ask        (JWT auth + per-user rate limit)
    → FastAPI POST /circuit/ask              (prompt template, JSON validation, retry-once)
      → core/llm.py safe_generate()          (token ledger: budgets checked/recorded — mandatory)
```
- **Auth**: route is `authenticated()` in SecurityConfig (NOT permit-all); backend forwards
  `authentication.getName()` as `user_email`; engine 429 quota/busy responses pass through
  (same convention as /global-ai: `{"status":"quota","scope",...}`).
- **No streaming needed in v1** — the circuit spec is one JSON response (a plan-sized call,
  typically < 2K tokens). If generation feels slow, add streaming later; don't build it now.
- **Rate limit**: reuse `RateLimitService`, e.g. 20 circuits/hour/user
  (`circuit.rate-limit.*` in application.properties, env `CIRCUIT_ASKS_PER_HOUR`).
- **Persistence (v1: NO)** — circuits are ephemeral; "My circuits" gallery is phase 2
  (copy the IgniteProject pattern when approved).

## Functional requirements
1. Prompt box → one LLM call → rendered animated diagram with the player.
2. Player: play/pause, step fwd/back, restart, scrubber, speed, step counter, caption per step;
   step list in chat is clickable and stays in sync.
3. At least the 3 renderers + text-steps fallback; unknown/vague prompts get a
   `{"status":"needs_detail"}` response (same convention as Ignite), shown as a friendly chat msg.
4. Follow-up chat: new question re-runs the flow in the same session; simple parameter follow-ups
   (e.g. "target = 7") are handled client-side when possible.
5. Export SVG/PNG of the current canvas state; Copy-steps (plain-text numbered steps).
6. Token meter (🔋) in topbar, refreshed after each call — same as other pages.

## Integration constraints (from CLAUDE.md — follow them)
- Frontend: vanilla JS, new `frontend/circuit.html` + byte-identical mirror in
  `backend/devassist-backend/src/main/resources/static/`; `requireAuth()` at DOMContentLoaded;
  all calls via `authFetch()`; never hardcode the backend URL; add nav link on dashboard (+mirror).
- Backend: Java 21, Clean Architecture (controller → service), constructor injection, records
  for DTOs, kebab-case endpoints, `@Transactional` at service layer (not needed in v1 — no DB).
- AI engine: route handler in `api/api_server.py` stays thin; prompt building + JSON validation
  in a new `services/circuit.py`; ALL LLM calls through `core/llm.py:safe_generate` (never the
  Groq client directly); heavy imports lazy; add any new package to `requirements.txt` (expect none).
- Tests required: pytest (mock the LLM; valid spec, invalid-JSON retry, quota passthrough,
  needs_detail) + JUnit (controller auth/rate-limit/happy path, service proxy mapping).
  Existing suites must stay green.
- Free tier: no new deps, no DB, no streaming infra — one JSON call fits Render limits easily.

## User-friendliness
Never a frozen screen (loading state on the canvas while the LLM thinks: pulsing "drawing your
circuit…" skeleton); friendly plain-language errors (quota, busy, vague prompt) with Retry;
empty states show example prompts; Enter submits / Shift+Enter newline; works at laptop widths.

## Process rule for Claude Code
Before writing any code: read this spec + `mockup.html` + `CLAUDE.md`, then **ask Sayan all
clarifying questions in ONE batch**, wait for answers, produce a short implementation plan,
get approval, then build in phases:
(1) AI engine `/circuit/ask` + prompt + validation + tests →
(2) backend proxy + rate limit + tests →
(3) frontend page from mockup (renderers + player) →
(4) polish: export, follow-ups, token meter, dashboard link, mirror sync.
