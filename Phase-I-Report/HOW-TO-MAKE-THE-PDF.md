# How to produce the 26-page PDF

## 1. Add the college logo (30 seconds)

Save the Sathyabama logo as **`sathyabama-logo.png`** in this same folder.
Easiest source: open the template PDF, right-click the logo → save image, or screenshot and crop it.

If the file is missing the cover shows a dashed box instead — everything else still works.

## 2. Open the report

Double-click **`DevAssist-AI-Phase-I-Report.html`** (opens in Chrome or Edge).

A small badge appears in the top-left corner:

- **Green — "26 pages — no overflow, ready to print"** → go to step 3.
- **Red — "CONTENT OVERFLOW on sheet N"** → tell me the sheet number and I'll trim that page. The badge never prints.

## 3. Print to PDF

Press **Ctrl + P**, then set exactly these:

| Setting | Value |
|---|---|
| Destination | **Save as PDF** |
| Pages | All |
| Layout | **Portrait** |
| Paper size | **A4** |
| Margins | **None** |
| Scale | **Default** (100%) |
| Headers and footers | **unchecked** |
| Background graphics | **checked** |

> **Margins must be "None".** The 1.25 in left / 1 in top-right-bottom margins are already built into
> the page itself. Letting the browser add its own margins on top will shrink the text block and can
> push the document past 26 pages.
>
> **Background graphics must be on** — table header shading and the figure frames depend on it.

Save it as `DevAssist-AI-Phase-I-Report.pdf`. Confirm the page count reads **26**.

## 4. Replace the four screenshot placeholders

Page 17 has four dashed frames (Fig. 5.1 – 5.4). Take the screenshots, then either:

- **Easy:** send them to me and I'll embed them, or
- **Manual:** save them in this folder and replace each `<div class="shot" …>…</div>` block in the HTML with
  `<img src="fig-5-1.png" style="width:100%;height:52mm;object-fit:cover;border:1pt solid #555">`

Keep `height:52mm` so the page count stays at 26.

---

## Formatting applied

| Rule | Value |
|---|---|
| Paper | A4 (210 × 297 mm), portrait |
| Margins | Left 1.25 in (31.75 mm); top, right, bottom 1 in (25.4 mm) |
| Body font | Times New Roman 12 pt |
| Line spacing | 1.5 |
| Alignment | Justified |
| Chapter heading | 14 pt bold, ALL CAPS, centred, "CHAPTER n" on its own line above |
| Section heading | 12 pt bold, left aligned |
| Sub-section heading | 12 pt bold italic, left aligned |
| Table / figure text | 10.5 pt, single-ish spacing (1.25) |
| Captions | 11 pt, centred, below figures and below tables |
| Page numbers | Bottom centre; front matter in roman (ii–vii), body in arabic (1–19) |
| Cover page | Unnumbered |

## Page map (26 sheets)

| Sheet | Content | Printed number |
|---|---|---|
| 1 | Cover page | — |
| 2 | Bonafide Certificate | ii |
| 3 | Declaration | iii |
| 4 | Acknowledgement | iv |
| 5 | Abstract | v |
| 6 | Table of Contents | vi |
| 7 | List of Figures / Abbreviations | vii |
| 8–10 | Chapter 1 — Introduction | 1–3 |
| 11–13 | Chapter 2 — Literature Survey | 4–6 |
| 14–16 | Chapter 3 — Aim and Scope | 7–9 |
| 17–21 | Chapter 4 — System Design and Methodology | 10–14 |
| 22–24 | Chapter 5 — Implementation and Results | 15–17 |
| 25 | Chapter 6 — Conclusion and Future Work | 18 |
| 26 | References | 19 |

## Things to check before submitting

1. Head of the Department name on the certificate page — currently blank, fill it in.
2. Dean and HOD names in the Acknowledgement — verify they are current.
3. Project start date on the certificate reads "December 2025 to August 2026" — correct if wrong.
4. Both register numbers: Sudharsan R — 43110923, Sayan Bhowmick — 43110850.
