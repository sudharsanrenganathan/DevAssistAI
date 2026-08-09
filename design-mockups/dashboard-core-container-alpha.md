# DASHBOARD CORE NODE — CONTAINER "ALPHA" SNAPSHOT (saved 2026-07-13)

Safe-zone snapshot of the **restructured Core node container** (the frame/card that
holds the 3D core), separate from the 3D-core JS snapshots
(`dashboard-core-alpha.js` / `alpha2` / `alpha3`). This is the glass frame with the
pulsing aura, rotating conic sheen, "◈ NEURAL CORE" badge, and centered gradient
title — at its full 208px width. Restore on request ("restore the alpha core
container"): paste the CSS over the `/* ===== CORE NODE` block and the HTML over the
`<!-- CORE NODE (Center) -->` block in BOTH `frontend/dashboard.html` and
`backend/devassist-backend/src/main/resources/static/dashboard.html`.

## CSS

```css
        /* ===== CORE NODE — structurally distinct from the flat feature nodes ===== */
        .node-core {
            background: rgba(9, 13, 25, 0.6) !important;
            backdrop-filter: blur(4px);
            border: 1.5px solid rgba(6, 245, 205, 0.45);
            border-radius: 20px;
            z-index: 30;
            position: relative;
            overflow: hidden;
            width: 208px !important;
            padding: 12px 14px 14px;
            align-items: center;
            animation: corePulse 3.2s ease-in-out infinite;
        }
        /* pulsing aura ring sets the core apart as the central hub */
        @keyframes corePulse {
            0%, 100% { box-shadow: 0 0 22px rgba(6,245,205,0.12), inset 0 0 22px rgba(124,58,237,0.05); border-color: rgba(6,245,205,0.4); }
            50%      { box-shadow: 0 0 42px rgba(6,245,205,0.30), inset 0 0 30px rgba(124,58,237,0.12); border-color: rgba(6,245,205,0.72); }
        }
        /* slow rotating conic sheen behind the 3D core */
        .node-core::before {
            content: ''; position: absolute; inset: -35%; z-index: 0; pointer-events: none;
            background: conic-gradient(from 0deg, transparent 0deg, rgba(6,245,205,0.16) 55deg, transparent 130deg, rgba(124,58,237,0.16) 215deg, transparent 300deg);
            animation: coreSheen 9s linear infinite;
        }
        @keyframes coreSheen { to { transform: rotate(360deg); } }
        .node-core > * { position: relative; z-index: 1; }
        body.reduce-motion .node-core, body.reduce-motion .node-core::before { animation: none; }
        .core-badge {
            font-size: 9px; font-weight: 800; letter-spacing: 1.6px; color: #06f5cd;
            background: rgba(6, 245, 205, 0.1); border: 1px solid rgba(6, 245, 205, 0.3);
            padding: 3px 11px; border-radius: 20px; margin-bottom: 2px;
        }
        #core-3d-container {
            position: relative;
            width: 100%;
            height: 104px;
            z-index: 10;
            opacity: 1;
            pointer-events: none;
        }
        .core-title { text-align: center; font-size: 13px; font-weight: 800; letter-spacing: 0.3px; color: #f8fafc; margin-top: 2px; }
        .core-title span { background: linear-gradient(135deg, #06f5cd, #7c3aed); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
```

## HTML

```html
            <!-- CORE NODE (Center) -->
            <div class="workflow-node node-core" id="node-core">
                <div class="core-badge">◈ NEURAL CORE</div>
                <div id="core-3d-container"></div>
                <div class="core-title">DevAssist <span>Core</span></div>
            </div>
```
