# Windowed layout, packed from the Place

A Spine item is laid out in windows, not all at once. Windows are cut at block (paragraph) boundaries,
preferring table-of-contents Chapter starts, and a Chapter longer than ~20 K characters is split. The
window holding the Place is laid out synchronously; neighbouring windows are laid out in the background.
Pages are packed outward from the Place: the line holding the Place starts a Page, packing runs forward
into later windows and backward into earlier ones as they finish measuring, and a Page may span two
windows (drawn as up to two clipped bands). Line breaking restarts at every paragraph, so block-boundary
seams are exact and spanning costs no reflow.

Measured on LP3 hardware (SM4450, Pride and Prejudice): laying out a whole 112–165 K-character Spine item
takes 475–650 ms warm and up to 1,356 ms cold (~3–4 ms per 1,000 characters), and dropping hyphenation
saves only 15–25%. That misses the bar ADR 0004 set. This supersedes that bar, restated here: **the first
Page at any Place, and any font change, in at most 300 ms P90 on the SM4450, warm; page turns do no
layout work.** A 20 K window is ~60–80 ms plus pagination.

Rules that keep it stable:

- Page boundaries are cached within a layout pass, so going back shows the Page just read. Backward
  packing only creates Pages not yet visited in this pass.
- The first Page of a Spine item may be short when it's reached by backward packing (at most once per
  opening, at a heading).
- The measurer is warmed up (a short string) when the Shelf appears, so the first open pays only for its
  window.
- Hyphenation stays on; its cost fits inside the window budget.
- There's no in-session "Page N of M" (M isn't known until every window is done); Progress (minutes,
  percent) is layout-independent.

## Considered Options

- Whole Spine item layout (D9 as prototyped): misses the bar by 2×.
- Windows whose Pages never span a seam: simpler, but leaves a short Page mid-Chapter every ~25–60 Pages,
  which reads as a typesetting fault.
- Dropping hyphenation to make whole-item layout fit: still 2× over the bar.
