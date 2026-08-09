# DASHBOARD CORE NODE — CONTAINER "ALPHA 2" SNAPSHOT (saved 2026-07-13)

Safe-zone snapshot of the Core node container at the **slightly-shrunk 190px
rounded-rectangle** stage (before the circular boundary). Container-version history:
alpha = 208px rounded rect (`dashboard-core-container-alpha.md`); alpha 2 = this
190px rounded rect. Restore on request ("restore the alpha 2 core container"): paste
the CSS `.node-core` block below over the current one in BOTH
`frontend/dashboard.html` and `backend/devassist-backend/src/main/resources/static/dashboard.html`.
The HTML markup and the badge/title/sheen/pulse rules are unchanged from container-alpha.

## Key `.node-core` values for this version

```css
        .node-core {
            background: rgba(9, 13, 25, 0.6) !important;
            backdrop-filter: blur(4px);
            border: 1.5px solid rgba(6, 245, 205, 0.45);
            border-radius: 20px;
            z-index: 30;
            position: relative;
            overflow: hidden;
            width: 190px !important;
            padding: 11px 13px 13px;
            align-items: center;
            animation: corePulse 3.2s ease-in-out infinite;
        }
        #core-3d-container {
            position: relative;
            width: 100%;
            height: 94px;
            z-index: 10;
            opacity: 1;
            pointer-events: none;
        }
```

(All other core rules — corePulse, coreSheen, .node-core::before, .core-badge,
.core-title, HTML markup — are identical to `dashboard-core-container-alpha.md`.)
