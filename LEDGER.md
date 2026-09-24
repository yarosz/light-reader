# Ledger

STATUS: N1 done; next is N2 (reading data store)
LAST SESSION: 2026-09-24

## Done

| # | What | Evidence |
|---|---|---|
| 1 | Pagination spike | property tests (6 × 3,000 cases) + EPUB parser tests (7); font round trip lands on the identical Page |
| 2 | Design settled | `CONTEXT.md`, ADRs 0001–0006 (4 grilling rounds with a product/UX advisor) |
| N1 | This repo | SDK submodule pinned to `v0.1.2`; 13/13 tests; Light's plugin accepts `res/font` (Literata ships in the APK); emulator: Literata renders with true italics; font round trip 3/41 → 4/62 → 5/87 → 3/41 |

Found while doing N1: Literata's descenders crossed line boundaries, leaking a sliver of the previous
Page's last line onto the next Page (clipped-band drawing). Fixed with line height 1.4 and centred,
untrimmed line boxes (`LineHeightStyle`).

## Next

Ordered. Each item ends on its _done-when_.

- **N2 · Reading data store.** One `reading-data.json` in `filesDir`: Shelf + per-Book Place (ADR 0002)
  + per-Tool settings (font step, polarity, pace). Atomic writes (temp + rename, debounced),
  `schemaVersion`, `.bak` of the last good write used on parse failure, finished state (Progress 100%).
  Merge: newer Place wins, keep entries for Books not on the Shelf, ignore unknown fields, never clobber.
  _Done when:_ kill the Tool on a Page, relaunch, lands on the same Page (emulator), and merge rules
  have tests.
- **N3 · Shelf + Catalogues (ADR 0001, 0005).** One Atom parser (OPDS acquisition links and EPUB
  enclosures); shipped Gutenberg + "Standard Ebooks: new releases"; acquisition preference EPUB3 >
  EPUB2, with-images variant; foreground downloads with visible states; Book identity by
  `dc:identifier`; copy-protection detection. OPDS search via the feed's OpenSearch link (hidden when
  absent); Catalogue entry → detail page ("Add to Shelf"); Shelf rows open the Book. Shelf order: in
  progress (recent first), not started, finished. "Edit" in the top bar removes a Book (file deleted,
  Place kept). Error and offline copy. HTTPS only; a typed http:// tries https:// once. Measure a
  190 KB Gutenberg Spine item on the emulator as soon as one opens. _Done when:_ a Book from each
  shipped Catalogue downloads, appears on the Shelf, and resumes its own Place offline.
- **N4 · Chapters + Progress (ADR 0004).** TOC from nav.xhtml / NCX with fallbacks; top bar shows the
  Chapter title; "Contents" lists Chapters; "about N min left in this Chapter" (230 wpm prior, median of
  the last 20 page-turn samples, 2 s–3 min filter, whole minutes under 15, 5-minute buckets above,
  "almost done" under one); percent on the Shelf; end page "The end." + "Back to Shelf" marks the Book
  finished. _Done when:_ Pride and Prejudice shows 61 Chapters across 9 Spine items.
- **N5 · Reading chrome.** Hidden while reading; centre tap reveals an overlay (text never moves): top
  bar (back to Shelf + Chapter title), Progress line, bottom row "A−  A+  Light  Contents". Asymmetric
  tap zones (back 30% / chrome 25% / forward 45%); five font steps 20/24.5/30/36/44 sp; margins 20 dp
  (one constants file); one-line first-run hint; keep the screen on while reading (release after 10
  min without a turn); Page text in semantics; About screen (version, licenses incl. Literata OFL,
  copy-protected explainer + DRM-free sources, repo URL as text, the ADR 0003 no-network sentence).
  _Done when:_ verified with `mise run ui`.
- **N6 · Performance bar.** Open, Chapter jump, and font change ≤ 300 ms P90 on LP3 hardware for a
  190 KB Spine item at 44 sp with hyphenation; page turns do zero layout. Emulator = smoke test only.
  If missed: windowed layout, new ADR.
- **N7 · Tool Manager node** (v1.x): upload your own EPUBs, download/upload `reading-data.json`; the
  change hook merges. Build it, but advertise it only once confirmed live on retail LightOS.
- **CI:** a job that builds from a fresh `git clone --recursive`, proving the public commit builds.

**v1** = N1–N6. **v1.x:** N7, images (inverted line art), the Standard Ebooks full catalogue if granted,
a Light SDK discussion asking for opt-in cleartext on user-entered LAN Catalogues. **v2:** offline
tap-a-word dictionary. **Deferred:** full TalkBack audit (first check whether LightOS ships it),
Gutenberg language filtering. **Not planned unless asked:** sync, bookmarks, highlights, covers on
lists, Shelf search/sort options, per-Book font, reading statistics.
