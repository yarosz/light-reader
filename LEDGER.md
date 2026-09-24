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

## Hardware (2026-09-24, LP3 TLP301, Android 14, LightOS 582)

- The loop works on a real phone over adb (`ANDROID_SERIAL=<serial> mise run ui …`); a device build
  needs `serverPackage = "com.lightos"` in `tool/lighttool.toml`. Font round trip 4/57 → 4/85 → 6/122
  → 4/57: identical Page.
- **Density is 480 dpi** (panel ~419 ppi). The emulator AVD was 420: set it to 480 to match.
- Akkurat ships true italics on retail phones (`/system/fonts/AkkuratLLTT-*Italic.ttf`).
- **N6 bar missed.** Styling + layout + pagination of Pride and Prejudice Spine items of 112–165 K
  characters: 475–650 ms warm, up to 1,356 ms cold; ~3–4 ms per 1,000 characters. Hyphenation costs
  15–25% (165 K item: 820 ms with, 690 ms without, 640 ms without + simple line breaking). Resolved
  with the advisor (round 5): ADR 0007 (windowed layout packed from the Place), ADR 0006 amended,
  `DESIGN.md` (type scale for 480 dpi, page-break rules).

## Next

Ordered. Each item ends on its _done-when_.

- **R · Release path (advisor round 6), in this order:**
  1. ~~Minified-release smoke test~~ **done 2026-09-24**: `assembleRelease` (R8 + resource shrinking)
     dev-signed, on the emulator: fonts survive (paths shortened to `res/<xx>.ttf`, sizes match), paging
     works, font round trip 5/41 → 7/62 → 9/87 → 5/41. Screenshot shows a Page ending "waist-" (a
     Paginator v2 test case).
  2. `mise run light-build`: Light's `lightbuilder prepare` on a fresh clone of a public commit, then
     unsigned `assembleRelease` against a named SDK ref (simulated once by hand: passes).
  3. CI (GitHub Actions, time-boxed to half a day): unit tests + builder simulation against the pinned
     tag on every push/tag (blocking); SDK `main` on a schedule (non-blocking). If the simulation fights
     the runner, ship unit tests only and keep the mise task.
  4. `RELEASING.md` last: versioning (`versionName` semver, `versionCode` +1, tag `vX.Y.Z`, notes-only
     GitHub Release; bump the SDK pin to the newest tag each release), no self-published APKs (the dev
     key is public), rollback = new release with higher `versionCode` carrying old code, pre-release
     checklist (unit tests, emulator loop, LP3 loop, builder simulation, minified release runs,
     upgrade-path test, history scan), sentinel check of Light's first signed artifact on the LP3
     (package/version via aapt, hardware loop) before telling testers, the one-time data wipe when
     moving from dev-signed to Light-signed builds.
  Also: hardware screenshots into the repo while the LP3 is on adb; `SECURITY.md`, `CONTRIBUTING.md`
  (platform rules; DRM-circumvention PRs declined per ADR 0005), issue template (LightOS version, book
  source, title, steps). `REVIEW.md` for Light's reviewers before v1. Ask in #204: which SDK commit the
  builder uses, and whether Tool Manager transfer is live on retail. **Tool id decided:
  `com.yarosz.reader`**, permanent from first publish.
- **P · Paginator v2 (ADR 0007, `DESIGN.md`).** Pure core first: block-boundary windows (prefer
  Chapter starts, split > ~20 K chars); pack outward from the Place; a Page = up to two bands; page-end
  rules (whitespace/paragraph end only, never after a heading, 70% guard, cascade test); page-boundary
  cache per layout pass. Then the Compose side: sync current window, background neighbours (verify
  `TextMeasurer` off the main thread under the plugin), two-band drawing, measurer warm-up, the 480-dpi
  type scale in one constants file (default 20 sp, 17–36), top/bottom margins 12–16 dp, try line height
  1.35 on hardware. Set the emulator AVD to 480 dpi. _Done when:_ property tests cover every rule; on the
  LP3, first Page at any Place and font change ≤ 300 ms P90 warm on Pride and Prejudice; font round trip
  still lands on the identical Page; no Page ends mid-word or on a heading (spot-check screenshots).
- **N2 · Reading data store.** One `reading-data.json` in `filesDir`: Shelf + per-Book Place (ADR 0002)
  + per-Tool settings (font step, polarity, pace). Atomic writes (temp + rename, debounced),
  `schemaVersion`, `.bak` of the last good write used on parse failure, finished state (Progress 100%).
  Merge: newer Place wins, keep entries for Books not on the Shelf, ignore unknown fields, never clobber.
  Never crash on a higher `schemaVersion` than the code knows (rollback safety); test one downgrade.
  Upgrade-path test: install release N over N−1 with a populated store; Shelf and Places survive.
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
- **N6 · Performance bar (ADR 0007).** Re-measure on the LP3 after N3–N5: first Page at any Place and
  font change ≤ 300 ms P90 warm; page turns do no layout. Emulator = smoke test only.
- **N7 · Tool Manager node** (v1.x): upload your own EPUBs, download/upload `reading-data.json`; the
  change hook merges. Build it, but advertise it only once confirmed live on retail LightOS.
- **CI:** a job that builds from a fresh `git clone --recursive`, proving the public commit builds.

**v1** = N1–N6. **v1.x:** N7, images (inverted line art), the Standard Ebooks full catalogue if granted,
a Light SDK discussion asking for opt-in cleartext on user-entered LAN Catalogues. **v2:** offline
tap-a-word dictionary. **Deferred:** full TalkBack audit (first check whether LightOS ships it),
Gutenberg language filtering. **Not planned unless asked:** sync, bookmarks, highlights, covers on
lists, Shelf search/sort options, per-Book font, reading statistics.
