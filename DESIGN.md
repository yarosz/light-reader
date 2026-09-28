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

Reader is locked to portrait (`orientation = "portrait"` in `tool/lighttool.toml`), like LightOS
itself: its main activity declares the same portrait `screenOrientation`, so its tools never rotate
mid-use, and LightOS gives users no rotation control to stop a flip. Android letterboxes a
portrait-locked app whenever its area is shorter than it is wide; the LP3 has 5 dp of margin
(365 × 360 dp), the same limit LightOS's own UI lives within. The emulator must use gesture navigation
to match (a three-button bar leaves 341 × 360 dp and pillarboxes Reader).

## Page-break rules (pure, property-tested)

- A Page may end only at a line whose break falls at whitespace or a paragraph end, which rules out
  soft-hyphen breaks ("trou-/ble") and hard-hyphen compounds ("well-/known"). Defined on the source
  text, not on layout internals.
- A Page never ends on a heading; the heading moves to the next Page with its text.
- Guard: those rules give way if the Page would fall below 70% full. Consecutive hyphenated lines (a
  cascade) are an explicit test case.
- No widow or orphan rules in v1. At 26–38 characters per line most paragraphs are one to three lines,
  so the rules would fire constantly and cost a line each time.

The rules hold in both directions. A Page packed backward (reached by turning back past the Place of
the current pass) ends where the Page below it starts, so its start is chosen so that the line above
it is a legal end, with the same 70% guard: every Page end is legal unless the guard fired. What
remains asymmetric: a backward pass may tile a stretch differently (but as legally) from a forward
one, and a cold backward crossing may leave a short first Page in the Chapter (ADR 0007).

## Paragraph indent

A paragraph gets a first-line indent only when it follows another paragraph (Standard Ebooks' p + p
convention); the first paragraph after a heading, caption or verse, or a chapter's first block, starts
flush, judged on block kinds, never on window boundaries.

## Shelf

The Tool's first screen: the Books on this phone. Product rulings from the advisor (2026-09-27); copy
is verbatim, and `Shelf.kt` holds it.

**Top bar.** `LightTopBar` with "Reader" in the centre. The left slot is "Edit", free because the Shelf
is the root screen; it reads "Done" while editing and is hidden when the Shelf is empty. The right
button is "Add", which opens the list of Catalogues.

**Order.** Books in progress by most recently read, then never-opened Books by date added, then
finished Books at the bottom. The top row is "continue reading", so there is no separate row. Within
never-opened, the newest is first, and a download that hasn't arrived yet counts as added when it
started. A Book stored before the Shelf existed has no date and sorts as the oldest.

**Row.** Text only, with no covers on the Shelf or in Catalogue lists. The title is on the first line
and one state on the second. One tap opens the Book at its Place. Titles are shown verbatim, never
title-cased: a Book from a Catalogue takes the Catalogue entry's title, any other its `dc:title`, and
downloading a Book again from the Shelf keeps the title it has. A title wraps to at most two lines,
set tighter (1.2) than body copy.

| State | Second line | Tap |
|---|---|---|
| In progress | "author · 42%" (see below) | opens the Book |
| Never opened | "not started" | opens the Book |
| Downloading | "downloading…" | nothing |
| Failed, retryable | "download failed · tap to retry" | downloads again |
| Failed for good, downloading again from the Shelf | "can't download again · copy-protected", "· not an EPUB", "· needs https" or "· certificate not trusted" | nothing; the Book can only be removed |
| Finished | "finished" (see below) | opens the Book |
| File missing, source known | "file missing · tap to download again" | downloads again, keeps the Place |
| File missing, no source | "file missing" | nothing; the Book can only be removed |

Until N4 computes Progress, an in-progress row shows the author alone, never a placeholder percent,
and "finished" waits for N4 too, so a finished Book reads like one in progress. When there is no author,
the state stands alone, so an in-progress Book with no author has no second line. A running download
wins over a Book's file, the file over a failed download (a Book that is here stays readable
offline), and both over its reading state. Opening a Book counts as reading: it records the first
Page's Place, so a Book opened but never paged reads as in progress, not "not started".

**Empty.** "Nothing on your Shelf yet." with a text button "Add a Book". Text labels, not glyphs.

**Edit.** Every row's trailing edge becomes "Remove", in secondary text. Tapping it turns the row
itself, inline and not as a modal, into the Book's title, then "Remove from Shelf? Your place is kept
if you add it again.", then "Remove" and "Cancel". A confirmation whose row goes away (a download
that arrives, or fails) is cleared. Row taps don't open Books while editing. Removal deletes the file and keeps the Place
record, and removing a Book that is downloading cancels the download. Removing the last Book leaves
Edit.

**Downloads.** Foreground only, with a visible state; no background service in v1. Only retryable
failures (Unreachable, HttpError, DiskError) leave a row reading "download failed · tap to retry".
Permanent failures (CopyProtected, NotAnEpub, and also NoHttps and UntrustedCertificate, which a
retry can't fix) of a download from a Catalogue show their copy on the Book's detail page and add
nothing to the Shelf. A copy-protected Book must never become a row that can't be read. When
downloading a missing file again from the Shelf fails for good, the Book's row says why ("can't
download again · …", table above) and can then only be removed. That state lives in memory: after a
relaunch the row reads "file missing · tap to download again" again, and a tap tries once more.

**Offline.** Nothing changes on the Shelf, because everything there works offline: no rows are
removed and nothing is greyed out. "You're offline. Your Shelf still works." is one line of
secondary text at the top of the Catalogue list, shown when Add is tapped offline.

**Missing file.** A Book whose file is gone reads "file missing · tap to download again", and the tap
downloads it again from the Book's source, keeping its Place. If the download declares a different
`dc:identifier` (Calibre mints a new one on every conversion), the Book keeps its row: its Place,
date added and title move to the new identifier, and the Place resolves as well as the new edition
allows. A Book with no known source (a future
N7 import) reads "file missing" and can only be removed.
