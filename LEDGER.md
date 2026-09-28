# Ledger

STATUS: N1, the release path, P (Paginator v2) and N2 (reading data store) done; N3 code complete (pure core, Shelf, and the Catalogue screens in N3c, on review)
RELEASE HOLD: Don't tag a release until N3c merges (Add is a label on main until then).
LAST SESSION: 2026-09-27

## v1 user flow

1. **Shelf** (the first screen): the Books on this phone, in progress first. Tap one to read it at its
   Place. "Edit" removes a Book (its Place is kept).
2. **Add** → the list of Catalogues: Project Gutenberg, "Standard Ebooks: new releases", your own, and
   "Add a Catalogue".
3. **Catalogue** → search and browse as text → a Book's detail page → **Add to Shelf** (downloads it).
4. **Reading**: tap the right side or press volume down to turn the page. A centre tap shows the
   controls: back to the Shelf, the Chapter title, time left, A− A+, Light/Dark, **Contents**.
5. **Contents** lists the Chapters; tap one to jump there.

Everything above is v1 (N2–N5 below). v2 adds the tap-a-word dictionary.

## Done

| # | What | Evidence |
|---|---|---|
| 1 | Pagination spike | property tests (6 × 3,000 cases) + EPUB parser tests (7); font round trip lands on the identical Page |
| 2 | Design settled | `CONTEXT.md`, ADRs 0001–0006 (4 grilling rounds with a product/UX advisor) |
| N1 | This repo | SDK submodule pinned to `v0.1.2`; 13/13 tests; Light's plugin accepts `res/font` (Literata ships in the APK); emulator: Literata renders with true italics; font round trip 3/41 → 4/62 → 5/87 → 3/41 |
| R1 | Minified release runs | `assembleRelease` (R8 + resource shrinking), dev-signed, on the emulator: fonts survive (paths shortened to `res/<xx>.ttf`), paging works, font round trip 5/41 → 7/62 → 9/87 → 5/41 |
| R2 | Light-builder simulation | `mise run light-build`: `lightbuilder prepare` on a clone of the committed HEAD, then an offline, unsigned `assembleRelease` against the pinned SDK in a dedicated Gradle home |
| R3 | CI gate (#1) | Actions `build` (unit tests + builder simulation on a fresh runner) required on every PR; weekly `sdk-main` job; `mise run ci` posts `signoff/emulator` and `signoff/lp3` from a font round trip on each device |
| R4 | Release docs (#2) | `RELEASING.md`, `SECURITY.md`, `CONTRIBUTING.md`, issue template, LP3 screenshots in `docs/screenshots/` |
| #3 | Phone builds bind to LightOS (#4) | `serverPackage = "com.lightos"` committed (Light builds releases from it); `scripts/emulator-build.sh` swaps in the emulator's package for emulator builds only; a unit test and `light-build.sh` guard the committed line; `signoff/lp3` green on the fix |
| P | Paginator v2 (#6–#9) | Page-end rules in both directions, 480 dpi type scale, portrait lock; 10 K windows packed from the Place (ADR 0007). LP3, Pride and Prejudice 165 K-character chapter, P90: open 111 ms, font change 122 ms (was 1,940 / 870); seam-adjacent open 194 ms. `mise run perf` reproduces it. Parser follow-ups #10, #11 |
| N2 | Reading data store | `reading-data.json` per ADR 0002 (Place = Spine item, block, offset, snippet; Books keyed by `dc:identifier`), atomic replace with a separate `.bak`, a `.corrupt` copy of an unparseable file, debounced saves plus a flush on pause, merge tests; 105 unit tests, 19 mutations caught. Emulator and LP3: kill and relaunch lands on the same Page, the font step persists, a corrupt file opens at the `.bak` Place; emulator: main's build over it and back keeps the Place, a schemaVersion 2 file keeps its unknown fields |

Found while doing N1: Literata's descenders crossed line boundaries, leaking a sliver of the previous
Page's last line onto the next Page (clipped-band drawing). Fixed with line height 1.4 and centred,
untrimmed line boxes (`LineHeightStyle`).

## Hardware (2026-09-24, LP3 TLP301, Android 14, LightOS 582)

- The loop works on a real phone over adb (`ANDROID_SERIAL=<serial> mise run ui …`; `mise run tool-lp3`
  installs); a plain build already targets the phone (see #3 above). Font round trip 4/57 → 4/85 → 6/122
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

- **R · Release path, remaining.** R1–R4 and #3 are done (above). Left: `REVIEW.md` for Light's
  reviewers before v1; ask in #204 which SDK commit the builder uses and whether Tool Manager transfer is
  live on retail; at the first Light-signed build, the sentinel check in `RELEASING.md`. **Tool id:
  `com.yarosz.reader`**, permanent from first publish.
- **N2 follow-ups.** With N3, and required before any edition switch: a Place must re-find its
  snippet in other Chapters when it isn't in the same-id Chapter, not only when the Spine item is
  gone. Gutenberg's two editions of a Book share `dc:identifier` (`http://www.gutenberg.org/1342`)
  but not their Spines (16 against 9 items), and reuse idrefs such as `item5` for different text, so
  a same-id Chapter can hold other text entirely. (Done in N3's pure core: a Book with no
  `dc:identifier` is hashed over its Spine documents' CRC-32 and length, pinned by a test; with no
  `dc:title` it takes its file name, or a download its Catalogue entry's title.) With N4: the end page's
  "Back to Shelf" sets Finished, and turning back or jumping away from the end clears it. At the
  first release after N2: the upgrade-path test (release N over N-1 with a populated store).
- **N3 · Shelf + Catalogues (ADR 0001, 0005).** One Atom parser (OPDS acquisition links and EPUB
  enclosures); shipped Gutenberg + "Standard Ebooks: new releases"; acquisition preference: no
  images or unmarked over with images, then EPUB3 > EPUB2, until images ship (flip
  `PREFER_IMAGES_EDITION` in `Catalogue.kt`), and one "Add to Shelf" per Book across its page's
  entries (`bestDownload`); foreground downloads with visible states; Book identity by
  `dc:identifier`; copy-protection detection. OPDS search via the feed's OpenSearch link, or its ready
  Atom template as Calibre gives (hidden when absent); Catalogue entry → detail page ("Add to Shelf"); Shelf rows open the Book. Shelf order: in
  progress (recent first), not started, finished. "Edit" in the top bar removes a Book (file deleted,
  Place kept). Error and offline copy. HTTPS only; a typed http:// tries https:// once. Measure a
  190 KB Gutenberg Spine item on the emulator as soon as one opens. _Done when:_ a Book from each
  shipped Catalogue downloads, appears on the Shelf, and resumes its own Place offline.
  Pure core done (no UI yet): `Atom.kt` (feeds, OpenSearch), `Catalogue.kt` (shapes, shipped
  Catalogues, acquisition preference), `Network.kt` (`HttpsUrl`, `Transport`, typed failures),
  `Download.kt` (temp, validate, rename; `DownloadState`), copy-protection in `Epub.kt`.
  Shelf done (`DESIGN.md` "Shelf"): the first screen, a tap opens the Reader at the Place and back
  returns; order, second-line states, empty state, Edit with inline confirmation, removal (file
  deleted, Place kept, a running download cancelled), missing file (downloads again from the Book's
  source, or can only be removed). `BookEntry` gained `source`, `author` and `addedAt` (additive,
  ADR 0002). `ShelfOwner.of(filesDir)` is the process's one owner of the reading data, its saver,
  which files exist and the downloads (LightOS can recreate the activity in the same process without
  clearing old view models); the Shelf and Reader view models are views onto it, and `ReadingStore`
  saves are exclusive per directory besides. `ShelfOwner.download` runs foreground downloads through
  `HttpsTransport` and `Downloader` into filesDir, where every Book lives, and ends as Done, Failed or
  Removed, never a cancellation. A download lands (renames) on the main thread, where removals delete,
  so a removed download never leaves a file and a removal never deletes one that landed after it. A
  retryable failure stays as a row, a permanent one returns to the caller, or stays on the row that
  downloaded its missing file again. A re-download under a new `dc:identifier` moves the row's Place.
  dev-start opens `files/alice.epub` past the Shelf and writes nothing; `ci.sh` pushes the test
  fixture when a device has none (and removes it after), checks `reading-data.json` is byte-identical
  after each round trip, and checks a plain launch renders the Shelf ("shelf rows=" in logcat).
  XHTML's named entities are rewritten as numeric references before parsing: Android's Expat drops
  an undeclared entity silently (seen on the emulator), where the JVM's parser reported it.
  Catalogue screens done (N3c, `DESIGN.md` "Catalogues"): the list (Edit removes any Catalogue,
  "Add back" restores a shipped one, the offline line from the SDK's `LightConnectivity`), pages
  with search (the SDK's LP3 keyboard editor) and "More", the Book detail page matched to the Shelf
  by source, "Add a Catalogue" (fetched before saving, named by the feed's title), and every
  failure's D14/D15 copy. User-added and removed Catalogues are an additive `catalogues` field in
  `reading-data.json`, merged per URL by the newer change. D15 applied: UntrustedCertificate is
  retryable. Emulator: a Gutenberg Book (Pride and Prejudice) and a Standard Ebooks Book downloaded,
  showed on the Shelf, and each reopened at its own Place offline after a force-stop.
  Follow-up: `ci.sh` checks the size of the alice fixture it pushed, since a host-side cut can still
  install a truncated file.
  Hardening done: every XML document (feeds, OpenSearch, container, OPF, encryption, chapters) goes
  through one untrusted-XML parser (`Xml.kt`: no external entity or DTD is ever read, DOCTYPEs still
  parse); caps on feeds (8 MB), one text construct (64 K characters), package XML (4 MB), chapters
  (32 MB), and Books (300 MB, with 16 MB always left free); a Downloader deletes stale
  `download-*.part` files a killed process left; `UntrustedCertificate` for a certificate failure;
  redirects followed in code, https only. An EPUB2 chapter's XHTML named entities (such as
  `&nbsp;`) read as their characters from a built-in table, with no DTD read. A Catalogue row's
  second line (`CatalogueEntry.byline`) is the author, else a one-line `<content>` of at most 80
  characters that isn't a "Key: value" pair, which gives Gutenberg's list rows their authors (the
  summary stays for the detail page). `bestDownload` answers only for one Book's page: null unless
  every entry has the same title.
  N3c's screens, labels and copy (the Catalogue list, a Book's detail page and whether it is already
  on the Shelf, Add a Catalogue, restoring removed shipped Catalogues) are specified in DESIGN.md
  under "Catalogues", which N3c adds. That section is the one place they live. Two engineering
  notes: a Catalogue entry matches a Book when any acquisition link on the entry's page equals the
  Book's source, passing `replacing` so the Place is kept; and whether a Catalogue is shipped is
  decided by its URL.
- **Rename to the glossary (pre-N4 refactor PR).** Behaviour-preserving renames so the code says what
  `CONTEXT.md` says: `Chapter` → `SpineItem` (and the package reader's `SpineItem` → `SpineRef`);
  `Position` → `SpinePoint(item, char)`; `Transfer`/`TransferState` → `Download` with a nested
  `Status`; `DownloadState` cleanup (`progress` → `fraction`, drop unused states); `Epub.kt`'s `Book`
  → `OpenBook` and `BookEntry` → `Book`; `ShelfOwner`'s local `finished` → `ended`; the docs' chapter
  wording where it means a Spine item. _Done when:_ the "renamed in the pre-N4 refactor" group in
  `docs/domain-ignore.txt` is gone and `scripts/domain-drift.sh` reports 0 unresolved.
- **N4 · Chapters + Progress (ADR 0004).** TOC from nav.xhtml / NCX with fallbacks; top bar shows the
  Chapter title; "Contents" lists Chapters; "about N min left in this chapter" (230 wpm prior, median of
  the last 20 page-turn samples, 2 s–3 min filter, whole minutes under 15, 5-minute buckets above,
  "almost done" under one); percent on the Shelf: a fraction stored with each Place, additive under
  ADR 0002; end page "The end." + "Back to Shelf" sets Finished, and turning back or jumping away from
  the end clears it. A Spine item with no table-of-contents entry is labelled by its first heading,
  else "Chapter N" counted over listed Chapters, never "Section N"; front matter shows the Book title.
  A copy fix rides on this first reading-view change: `ReaderScreen.kt`'s "This book has no text."
  becomes "This Book has no text." (`DESIGN.md` "Copy"), and its line in `docs/domain-ignore.txt`
  goes. _Done when:_ Pride and Prejudice shows 61 Chapters across 9 Spine items.
- **N5 · Reading chrome.** Hidden while reading; centre tap reveals an overlay (text never moves): top
  bar (back to Shelf + Chapter title), Progress line, bottom row "A−  A+  Light  Contents". Asymmetric
  tap zones (back 30% / chrome 25% / forward 45%); the five font steps and margins from `DESIGN.md`
  (17/20/24.5/30/36 sp, default 20; one constants file); one-line first-run hint; keep the screen on
  while reading (release after 10 min without a turn); Page text in semantics; About screen (version,
  licenses incl. Literata OFL, copy-protected explainer + where to find DRM-free Books, repo URL as text, the
  ADR 0003 no-network sentence). The detail page's CopyProtected line then points to About's list.
  _Done when:_ verified with `mise run ui`.
- **N6 · Performance bar (ADR 0007).** Re-measure on the LP3 after N3–N5: first Page at any Place and
  font change ≤ 300 ms P90 warm; page turns do no layout. Emulator = smoke test only.
  Found in N3: opening a Book parses the whole Book first, and the bar doesn't cover that parse. On the
  LP3 debug build Alice's `parseMs` was 926; Pride and Prejudice takes about 2.8 s on the emulator.
  Not a regression. Measure a release build with `mise run perf` (it prints `parseMs`), then consider
  a lazy per-Spine-item parse.
- **N7 · Tool Manager node** (v1.x): upload your own EPUBs, download/upload `reading-data.json`; the
  change hook merges. Build it, but advertise it only once confirmed live on retail LightOS.

**v1** = N1–N6. **v1.x:** N7, images (inverted line art), the Standard Ebooks full catalogue if granted,
a Light SDK discussion asking for opt-in cleartext on user-entered LAN Catalogues. **v2:** offline
tap-a-word dictionary. **Deferred:** full TalkBack audit (first check whether LightOS ships it),
Gutenberg language filtering. **Not planned unless asked:** sync, bookmarks, highlights, covers on
lists, Shelf search/sort options, per-Book font, reading statistics, forget a removed Book (needs a
tombstone, because merge is a union).
