# Theme Redesign Spec — Global AI + Secret AI ("Alpha" design)

Goal: restyle `frontend/global.html` and `frontend/secret.html` to match the app-wide DevAssist theme (as used by dashboard.html and ignite.html). Functionality must NOT change — this is a visual redesign plus small UX additions listed below.

## Reference mockups (in this folder — view them with the Read tool)
- `alpha-global-ai.png` — target look for the Global AI page
- `alpha-secret-ai.png` — target look for the Secret AI page

## Design system (shared)
- Background: `#0a0a0a` with dot-grid: `radial-gradient(rgba(255,255,255,0.1) 1px, transparent 1px)`, `background-size: 20px 20px`
- Fonts: Inter (UI), JetBrains Mono (code). Replace Segoe UI.
- Brand gradient: `linear-gradient(135deg, #06f5cd, #7c3aed)`
- Panels: `rgba(255,255,255,0.03)` with `1px solid rgba(255,255,255,0.08)` border, radius 12px
- Accent per page: Global AI = teal `#06f5cd`; Secret AI = purple `#7c3aed` / `#c084fc`
- Extract shared values into a new `frontend/theme.css` with CSS variables; both pages link it. Page accent set via one variable override per page.

## Global AI (`global.html`) — target: alpha-global-ai.png
1. REMOVE the right 360px `.side` info panel ("About Global AI" static cards). Chat becomes full-width (~860px centered column).
2. Move status into top bar as a small pill: "● Online · <current model>".
3. Restyle: sidebar, history items (active = teal tint + teal left border), user bubbles (purple tint), bot headings/bold (teal), code blocks (keep highlight.js, teal header label), input box (teal border glow), send button (brand gradient).
4. ADD action chips under each completed bot response: Regenerate · Follow-up · Copy answer. (Copy answer already exists as copy-btn — restyle into chip row.)
5. Input footer hint: "Enter to send · Shift+Enter new line".
6. Keep ALL existing features working: AI switcher dropdown, model selector, chat history CRUD/pin/search, Export PDF, Local AI modal, typing dots, streaming cursor.

## Secret AI (`secret.html`) — target: alpha-secret-ai.png
1. KEEP the right Document Intelligence panel (upload, progress, doc search, doc list) — restyle only: dashed upload zone with purple border, doc cards as glass panels, selected doc = purple tint + border.
2. "New secret chat" button: subtle style like an active history item (translucent purple bg, thin purple left border, purple text) — NOT a gradient fill.
3. Top bar pills: "◆ Private" and "● Context: <selected doc name>" (teal pill showing which doc is active in this chat).
4. Input box: attached-doc chips row above the textarea ("<doc name> ✕", "+ add doc to context").
5. Selected doc in the panel shows an "IN CONTEXT" badge.
6. Bot answers show source chips underneath when the backend provides source/page info (e.g. "Source: p.4-6"). If the RAG response doesn't currently include page info, add the chip UI but populate with the document name only — do not fake page numbers.
7. Accent purple everywhere Global uses teal. Keep all existing features: upload flow (50MB check, progress, success flash), doc selection, history, model selector, Export PDF, Local AI modal.

## Constraints (CLAUDE.md applies)
- Vanilla JS only. No frameworks.
- Auth: keep `requireAuth()` + `authFetch()` untouched.
- After changing each page, update its mirror copy in `backend/devassist-backend/src/main/resources/static/`.
- No backend/API changes required for this task except NONE — if you think one is needed (e.g. source page info for chips), STOP and ask first.
- Free-tier deployment: no new dependencies, no build step — plain CSS/JS files only.
- Do not break the known-good features; test both pages manually after (list what to verify for me).

## Process
1. Read this spec + both PNG mockups.
2. Ask me all clarifying questions in ONE batch. Wait for answers.
3. Give an implementation plan (files touched, order). Wait for approval.
4. Implement page by page: theme.css first, then global.html, then secret.html, then mirrors.
5. After each page, tell me exactly what to check in the browser before you continue.
