# DASHBOARD CORE NODE — CONTAINER "ALPHA 3" SNAPSHOT (saved 2026-07-13)

Safe-zone snapshot of the Core node container as a **190px perfect CIRCLE**.
Container-version history: alpha = 208px rounded rect; alpha 2 = 190px rounded rect;
alpha 3 = this 190px circle. Restore on request ("restore the alpha 3 core
container"): set the `.node-core` block below in BOTH `frontend/dashboard.html` and
`backend/devassist-backend/src/main/resources/static/dashboard.html`.

```css
        .node-core {
            background: rgba(9, 13, 25, 0.6) !important;
            backdrop-filter: blur(4px);
            border: 1.5px solid rgba(6, 245, 205, 0.45);
            border-radius: 50%;
            z-index: 30;
            position: relative;
            overflow: hidden;
            width: 190px !important;
            height: 190px;
            padding: 0;
            align-items: center;
            justify-content: center;
            gap: 3px;
            animation: corePulse 3.2s ease-in-out infinite;
        }
```

(All other core rules — corePulse, coreSheen, ::before sheen, badge, title, HTML —
unchanged from container-alpha.)
