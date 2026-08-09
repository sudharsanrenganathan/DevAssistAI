# DevAssist AI — Phase I Report: how to produce the PDF and the Word file

One source file, `DevAssist-AI-Phase-I-Report.html`, produces both outputs.

---

## First: add the college logo (30 seconds)

Save the Sathyabama logo as **`sathyabama-logo.png`** in this folder.
Open the template PDF, right-click the logo → save image, or screenshot and crop.

Missing logo = dashed box on the cover. Everything else still works.

---

## A. The PDF (this is the 26-page submission copy)

1. Double-click `DevAssist-AI-Phase-I-Report.html` — it opens in Chrome or Edge.
2. Look at the badge in the top-left corner:
   - **Green, "26 pages — no overflow, ready to print"** → continue.
   - **Red, "CONTENT OVERFLOW on sheet N"** → that sheet's text box is too full. Tell me the number and I'll trim it. The badge never prints.
3. Press **Ctrl + P** and set exactly this:

| Setting | Value |
|---|---|
| Destination | **Save as PDF** |
| Layout | **Portrait** |
| Paper size | **A4** |
| Margins | **None** |
| Scale | **Default** (100 %) |
| Headers and footers | **unchecked** |
| Background graphics | **checked** |

> **Margins must be "None"** — the 1.25 in / 1 in margins are already inside the page.
> Letting the browser add more will shrink the text block and break the 26-page count.
>
> **Background graphics must be on** — table header shading and figure frames need it.

4. Save as `DevAssist-AI-Phase-I-Report.pdf` and confirm it reads **26 pages**.

---

## B. The Word file

**`DevAssist-AI-Phase-I-Report.doc`** — double-click it. It opens straight in Word.

Then **File → Save As → Word Document (\*.docx)** to convert it to a modern `.docx`.
That's it.

It carries A4 paper, 1.25 in left / 1 in other margins, Times New Roman 12 pt, 1.5 line spacing,
justified body text, all 26 sheets separated by real Word page breaks, an unnumbered cover, and an
automatic centred page-number footer.

> Word may show a "this file is in a different format" prompt on open — choose **Yes**. That's
> normal for this format.

### What to check after opening

- **Page count.** Should land on 26. Word's line-breaking differs slightly from a browser's, so a
  dense page can spill by a line. If one does, cut a sentence rather than changing the font size.
- **Figures.** The six diagrams live beside the document as `fig-4-1.svg` … `fig-4-6.svg`. Word 365
  imports SVG fine. If a figure shows as a broken-image box, copy that figure from the PDF (path A)
  and paste it over the box — the caption is already in place. **Keep the `.svg` files in the same
  folder as the `.doc`.**
- **Page numbers.** Word applies one continuous sequence, 1 onward, with the cover blank. If your
  department wants roman numerals on the front matter: cursor at the top of Chapter 1 →
  **Layout → Breaks → Next Page** → **Insert → Page Number → Format Page Numbers** → restart at 1,
  then set the earlier section to lower-case roman.

**If you only need to hand in a PDF, use path A and ignore all of this.**

---

## Why the footer was overwriting text — and what changed

Each sheet used to be one box with padding. CSS padding does not clip overflow, so when a page
held more text than it had room for, the extra ran down into the bottom margin and straight
through the page number.

Each sheet is now two nested boxes: an outer A4 sheet, and an inner text box of a **fixed 238 mm**
that clips its own contents. The text block ends 263.4 mm down the page; the page number sits at
284 mm. There is now a permanent **16 mm of clear space** between them, on every page, and it
cannot be consumed by content.

Alongside that structural fix, these pages were shortened so nothing gets clipped: **6, 7, 8, 9,
16, 17, 18** and the references page. Tables were tightened from 10.5 pt to 10 pt, the screenshot
frames from 52 mm to 46 mm, and the reference list to 9.9 pt so all twenty entries hold one page.

---

## Formatting applied

| Rule | Value |
|---|---|
| Paper | A4 (210 × 297 mm), portrait |
| Margins | Left 1.25 in (31.75 mm); top, right, bottom 1 in (25.4 mm) |
| Text block | 152.85 mm wide × 238 mm tall, hard-clipped |
| Body font | Times New Roman 12 pt |
| Line spacing | 1.5 |
| Alignment | Justified |
| Chapter heading | 14 pt bold, ALL CAPS, centred, "CHAPTER n" on its own line above |
| Section heading | 12 pt bold, left |
| Sub-section heading | 12 pt bold italic, left |
| Table text | 10 pt |
| Captions | 11 pt centred, below figures and below tables |
| References | 9.9 pt, IEEE numbered style |
| Page numbers | Bottom centre; roman ii–vii, arabic 1–19; cover unnumbered |

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

## Before you submit

1. **Head of the Department name** on the certificate page — currently blank.
2. **Dean and HOD names** in the Acknowledgement — verify they are current.
3. **Project period** on the certificate reads "December 2025 to August 2026" — correct if wrong.
4. **Register numbers**: Sudharsan R — 43110923, Sayan Bhowmick — 43110850.
5. **Four screenshots** on page 17 (Fig. 5.1 – 5.4). Send them to me and I'll embed them, or replace
   each `<div class="shot" …>…</div>` with
   `<img src="fig-5-1.png" style="width:100%;height:46mm;object-fit:cover;border:1pt solid #555">`.
   Keep `height:46mm` so the page count holds.
