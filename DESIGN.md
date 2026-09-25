# Design notes

Tunable values and typesetting rules. Decisions that are hard to reverse live in `docs/adr/`; these are
constants and rules we expect to adjust from measurements.

## Type scale (LP3: 1080×1240, density 480 → 360 dp wide; panel ~419 ppi)

1 sp = 3 px ≈ 0.52 pt on the panel. Column = 360 dp − 2 × 20 dp margins ≈ 165 pt. Literata averages
about half an em per character. Sizes stay in **sp** so the system large-text setting scales the Reader.

| Step | sp | ≈ pt | ≈ chars/line | Note |
|---|---|---|---|---|
| 1 | 17 | 8.8 | 38 | |
| 2 | **20** | 10.3 | 33 | **default**: first step above 30 cpl, where hyphenation stops being constant |
| 3 | 24.5 | 12.6 | 27 | measured 26 on hardware |
| 4 | 30 | 15.5 | 22 | |
| 5 | 36 | 18.5 | 18 | large print |

At 20 sp a Page holds about 13 lines (40–60 words of dialogue-heavy text, a turn every 10–15 s at
230 wpm), so vertical space is precious: side margins 20 dp, top and bottom margins 12–16 dp, no footer
while reading. Line height 1.35 with `LineHeightStyle(Center, Trim.None)` (the trim setting is what
stops descenders leaking across Pages); verified leak-free on the LP3, where 1.4 was the fallback. All
of these live in `Typesetting.kt`.

Reader sets no orientation lock, so it follows the system auto-rotate setting, which LightOS doesn't
let users change (no rotation control in LightOS, and quick settings aren't reachable from its
launcher). Reading lying on one side can therefore flip the Page, and the reader can't turn that off.
We accept that over the alternative: a portrait lock (`orientation = "portrait"` in
`tool/lighttool.toml`) makes Android letterbox Reader whenever the app area is shorter than it is
wide, and the LP3 has 5 dp of margin (365 × 360 dp), so any new inset would pillarbox every reader.
Rotating keeps the Place and relays out like a font change; landscape (1240×1008 px) gives longer but
fewer lines. Revisit if Light allows `orientation = "nosensor"` (natural orientation without the
fixed-orientation letterbox) or a runtime orientation API, or if readers report flips.

## Page-break rules (pure, property-tested)

- A Page may end only at a line whose break falls at whitespace or a paragraph end, which rules out
  soft-hyphen breaks ("trou-/ble") and hard-hyphen compounds ("well-/known"). Defined on the source
  text, not on layout internals.
- A Page never ends on a heading; the heading moves to the next Page with its text.
- Guard: those rules give way if the Page would fall below 70% full. Consecutive hyphenated lines (a
  cascade) are an explicit test case.
- No widow or orphan rules in v1. At 26–38 characters per line most paragraphs are one to three lines,
  so the rules would fire constantly and cost a line each time.
