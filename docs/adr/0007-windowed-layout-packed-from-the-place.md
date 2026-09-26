# Windowed layout, packed from the Place

A Spine item is laid out in windows, not all at once. Windows are cut at block (paragraph) boundaries,
preferring table-of-contents Chapter starts, and a Chapter longer than ~10 K characters is split. The
window holding the Place is laid out synchronously; neighbouring windows are laid out in the background.
Pages are packed outward from the Place: the line holding the Place starts a Page, packing runs forward
into later windows and backward into earlier ones as they finish measuring, and a Page may span two
windows (drawn as up to two clipped bands). Line breaking restarts at every paragraph, so block-boundary
seams are exact and spanning costs no reflow.

Measured on LP3 hardware (SM4450, Pride and Prejudice): laying out a whole 112–165 K-character Spine
item takes 475–650 ms warm and up to 1,356 ms cold (~3–4 ms per 1,000 characters in that prototype;
re-measured with the shipped styles, measuring the 164,870-character item alone takes 1,762 ms, ~11 ms
per 1,000, in line with the per-window cost below), and dropping hyphenation saves only 15–25%. That
misses the bar ADR 0004 set. This supersedes that bar, restated here: **the first Page at any Place, and
any font change, in at most 300 ms P90 on the SM4450, warm; page turns do no layout work.** The bar
covers building the styled text, measuring the window(s) and packing the Page, which the app logs as
`firstPageMs`. It doesn't cover the EPUB parse, composition or the first frame.

The worst case is a Place within a Page of a window's end: both windows are laid out before the Page
shows. At 10 K windows that's roughly 4–6% of Places (a Page holds ~400–600 characters of a ~9.5 K
window), and the bar holds at any Place, so this case sets the window size.
Measured on the LP3 (TLP301) in Pride and Prejudice's largest Spine item (164,870 characters), with the
Place 150 characters before the first window's end, P90 of 20 runs unless noted:

| Window size (actual) | Open | Font change |
|---|---|---|
| Whole Spine item (before windows) | 1,940 ms | 870 ms |
| 20 K (12.5–16.7 K), n = 10 | 331 ms | 338 ms |
| 12 K (~11.8 K) | 280 ms (max 284) | 243 ms (max 294) |
| **10 K (9.1–9.8 K)** | **194 ms (max 197)** | **172 ms (max 207)** |
| 8 K (5.5–7.1 K), n = 10 | 136 ms | 142 ms |

Measuring one window takes ~139 ms at ~12.5 K, ~117 ms at ~11.8 K, ~89 ms at ~9.1 K and ~63 ms at ~5.5 K
(P50), so cost scales with size, roughly 10 ms per 1,000 characters. Windows are 10 K: the largest
measured size whose two-window open keeps a real margin under the bar (12 K's worst font change reached
294 ms). With the Place inside one window, open and font change are 111 / 122 ms P90 (max 120 / 156;
195 / 204 ms at 20 K).

Rules that keep it stable:

- Page boundaries are cached within a layout pass, so going back shows the Page just read. Backward
  packing only creates Pages not yet visited in this pass.
- The first Page of a Spine item may be short when it's reached by backward packing (at most once per
  chapter per session, at a heading: a chapter already read this session reuses its forward Pages).
- The measurer is warmed up (a short hyphenated string in every face the book uses) while the book
  opens. It takes ~16–57 ms on the LP3, so the first open pays only for its window(s).
- Hyphenation stays on; the times above include it.
- There's no in-session "Page N of M" (M isn't known until every window is done); Progress (minutes,
  percent) is layout-independent.

## Considered Options

- Whole Spine item layout (D9 as prototyped): misses the bar by 2×.
- Windows whose Pages never span a seam: simpler, but leaves a short Page mid-Chapter every ~25–60 Pages,
  which reads as a typesetting fault.
- Dropping hyphenation to make whole-item layout fit: still 2× over the bar.
- 20 K or 12 K windows: fewer seams, but the two-window open misses the bar at 20 K and clears it by too
  little at 12 K.
