# Ledger

STATUS: N1, the release path, P (Paginator v2), N2 (reading data store) and N3 (Shelf + Catalogues) done, the code renamed to the glossary, and N4 (Chapters + Progress) done, released as v0.1.0; next is N5 (Reading controls)
LAST SESSION: 2026-09-29

## v1 user flow

1. **Shelf** (the first screen): the Books on this phone, in progress first. Tap one to read it at its
   Place. "Edit" removes a Book (its Place is kept).
2. **Add** → the list of Catalogues: Project Gutenberg, "Standard Ebooks: new releases", your own, and
   "Add a Catalogue".
3. **Catalogue** → search and browse as text → a Book's detail page → **Add to Shelf** (downloads it).
4. **Reading**: tap the right side or press volume down to turn the page. A centre tap shows the
   controls: back to the Shelf, the Chapter title, time left, A− A+, **Contents**.
5. **Contents** lists the Chapters under their Parts; tap one to jump there.

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
| P | Paginator v2 (#6–#9) | Page-end rules in both directions, 480 dpi type scale, portrait lock; 10 K windows packed from the Place (ADR 0007). LP3, Pride and Prejudice 165 K-character Spine item, P90: open 111 ms, font change 122 ms (was 1,940 / 870); seam-adjacent open 194 ms. `mise run perf` reproduces it. Parser follow-ups #10, #11 |
| N2 | Reading data store | `reading-data.json` per ADR 0002 (Place = Spine item, block, offset, snippet; Books keyed by `dc:identifier`), atomic replace with a separate `.bak`, a `.corrupt` copy of an unparseable file, debounced saves plus a flush on pause, merge tests; 105 unit tests, 19 mutations caught. Emulator and LP3: kill and relaunch lands on the same Page, the font step persists, a corrupt file opens at the `.bak` Place; emulator: main's build over it and back keeps the Place, a schemaVersion 2 file keeps its unknown fields |
| N3 | Shelf + Catalogues (#15, #16, #17, #19) | One Atom parser (OPDS, OpenSearch), https only with typed http:// tried once, redirects followed in code, every XML document through one untrusted-XML parser with size caps; foreground downloads into filesDir owned by the process's `ShelfOwner`, copy-protected EPUBs refused; the Shelf (order, Edit, missing files), the Catalogue list, pages with search and "More", Book detail matched by source, Add a Catalogue, every failure's D14/D15 copy (`DESIGN.md` "Shelf", "Catalogues"); an additive `catalogues` field in `reading-data.json`; 310 unit tests. Emulator: a Gutenberg Book (Pride and Prejudice) and a Standard Ebooks Book downloaded, showed on the Shelf, and each reopened at its own Place offline after a force-stop |
| G | Rename to the glossary (pre-N4) (#20) | Behaviour-preserving renames so the code says what `CONTEXT.md` says (`SpineItem`, `SpinePoint`, `Book`; `SpineRef` and `OpenBook` are parser names; `Download` waits for the pre-N4 domain pass); `reading-data.json` keys and the log lines `scripts/perf.sh` reads unchanged; 310 unit tests, as before; `scripts/domain-drift.sh` 0 unresolved |
| D4 | Pre-N4 domain pass (#21) | Glossary: Download, Front matter, Chapter runs to the next one (leaf entries only), Finished set past the last Page, Page no longer leans on "reading session"; renames `DownloadState.Finished` → `Outcome`, stored-Book `entry` → `book`, `DevStart.offset` → `char`; `Download` moves to `Download.kt`. Kept as they are: `Reading`'s `offset` (a layout-internal string index; `open` agrees with `enter`), `RowTap.Download` (it starts a Download), "file" in row copy (the Book's file), `Pass.item` (KDoc added), `CataloguePage` (Page's own note). `scripts/domain-drift.sh` 0 unresolved; tag `domain-pass/n4` on the merge commit |
| N4 | Chapters + Progress (#22–#32) | Chapters from the table of contents (leaf entries, ADR 0004); the top line names the Chapter the Page goes by, the footer the minutes left in it, the Shelf a percent; the end page comes before Back matter and sets Finished; Contents jumps to a Chapter and back to the Shelf; after a size change a Page starts on a whole word, never above the Place's Chapter (ADR 0007 clarified); div and table text, and Standard Ebooks' dedications, epigraphs and forewords, reach the Page; a QA walkthrough's fixes (#27–#32). 501 unit tests; `mise run ci` green on the emulator and the LP3 |
| D5 | N4 closing domain pass (#33) | Glossary: Part (new), and a Page goes by one Chapter; amended Front matter (the table of contents decides, not the Book's marking; capitalised as a term), Back matter (a trailing run; a Place can open in it), Spine item (documents that aren't reading matter are left out), Place (the heading and Chapter rules, the Contents jump), Progress (the minutes follow the Page), Finished (what clears it). `contentsAt`'s `place` → `point`; Edition capitalised in comments; `Pass` KDoc says what its anchor is; AGENTS.md names closing-pass tags. ADR 0007's #30 edit is a clarification of the same decision; no new ADR. `scripts/domain-drift.sh` 0 unresolved; tag `domain-pass/n4-close` on the merge commit |
| v0.1.0 | First release (#39) | Tag `v0.1.0` on the merge commit; `versionName` 0.1.0, `versionCode` 1, as committed (never published before); `light-sdk` at v0.1.2, Light's newest tag. Everything through N4, the User-Agent (#35) and the README screenshots (#36, #37). The GitHub Release carries notes only (RELEASING.md); Light's portal submission waits for the portal |

Found while doing N1: Literata's descenders crossed line boundaries, leaking a sliver of the previous
Page's last line onto the next Page (clipped-band drawing). Fixed with line height 1.4 and centred,
untrimmed line boxes (`LineHeightStyle`).

Found while doing N3: Android's Expat drops an undeclared XML entity silently (seen on the emulator)
where the JVM's parser reports it, so XHTML's named entities are rewritten as numeric references
before parsing.

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
- **N2 follow-ups.** With N3, and required before any Edition switch: a Place must re-find its
  snippet in other Spine items when it isn't in the same-id Spine item, not only when the Spine item is
  gone. Gutenberg's two Editions of a Book share `dc:identifier` (`http://www.gutenberg.org/1342`)
  but not their Spines (16 against 9 items), and reuse idrefs such as `item5` for different text, so
  a same-id Spine item can hold other text entirely. (Done in N3's pure core: a Book with no
  `dc:identifier` is hashed over its Spine documents' CRC-32 and length, pinned by a test; with no
  `dc:title` it takes its file name, or a download its Catalogue entry's title.) With N4: showing
  the end page sets Finished, and turning back from it, or jumping to a Chapter of the text, clears it. At the
  first release after N2: the upgrade-path test (release N over N-1 with a populated store).
- **N3 follow-ups.** `ci.sh` checks the size of the alice fixture it pushed, since a host-side cut
  can still install a truncated file. Images: flip `PREFER_IMAGES_EDITION` in `Catalogue.kt` when
  images ship. In the images EPUB, drop caps are `<img alt="M">` inside a `<p>`, and the `img` branch
  only fires outside a block, so the letter is lost; handle it before the flip. The "This Book has no
  text." copy fix rides on N4 (below) or the next reading-view change, whichever lands first. A
  connect timeout on a typed http:// address reads NoHttps only on a VALIDATED network, which the SDK
  can't report today, so it is Unreachable; revisit if `LightConnectivity` gains validation.
- **N4 · Chapters + Progress (ADR 0004).** TOC from nav.xhtml / NCX with fallbacks; top bar shows the
  Chapter title; "Contents" lists Chapters; "about N min left in this chapter" (230 wpm prior, median of
  the last 20 page-turn samples, 2 s–3 min and 600 wpm filters, whole minutes under 15, 5-minute buckets
  above, "almost done" under one); percent on the Shelf: a fraction stored with each Place, additive under
  ADR 0002; end page "The end." + "Back to Shelf"; showing it sets Finished, and turning back from
  it, or jumping to a Chapter of the text, clears it. With a usable table of contents, a Chapter runs to the next Chapter, so an unlisted Spine item
  continues the Chapter before it, and a Page opening on the heading of a Part that isn't a Chapter goes by the Chapter after it when only headings come between; with none, each Spine item is a Chapter
  labelled by its first heading, else "Chapter N", never "Section N". Front matter shows the Book title
  and no minutes line. Settled in the pre-N4 domain pass (the N4 review may revisit):
  - The stored key is `progress` on the Place: the share (0–1) of all the Book's characters before the
    Place, Front matter and Back matter included; Chapter minutes are never stored. A Place saved before N4 has none,
    and the Shelf shows no percent for it until the next page turn.
  - Nested tables of contents: only leaf entries are Chapters (ADR 0004's nearest leaf), so a "Part
    One" page belongs to the Chapter before it, or to Front matter, though a Page opening on it goes by
    the Chapter after it when only headings come between (`pagePoint`).
  - Back matter: Back matter always starts a Chapter, listed or not (N4 (c)). Unmarked trailing material is text: listed, it
    is its own Chapter; unlisted, it continues the last one. Finished needs the last Page of the text. Back matter (`CONTEXT.md`) starts at
    `OpenBook.textEnd`: Project Gutenberg's `pg-footer`, or a trailing run of Spine items marked
    `backmatter`. The end page follows the last Page of the text, which ends exactly there.
  - Showing the end page sets Finished; leaving it, by system back or "Back to Shelf", keeps it. The Place stays on the last
    Page (the end page is not a Page), so a Finished Book reopens there.
  - Reading-speed samples belong to the reader: the last 20 are kept in memory across Books, not saved.
  - The Spine-item counter (`ReaderScreen.kt`, `${shown.pass.item + 1}/…`) goes when the top bar shows
    the Chapter title, and its ignore line with it. The perf key `chapters=` counts Spine items; log
    real Chapters under a new key.
  - Two Chapters starting at the same point are both kept; the Place belongs to the later, so the top
    bar shows its title. Contents lists both, and either lands on the same Page.
  - Labels are the Chapter's title, never a computed "Chapter N of M" (ADR 0004's example predates the
    listed title page).
  - A label that ends with its Chapter's first heading, after a caption, is titled by the heading
    ("CHAPTER III.").
  - N4 (c), Contents: tapping the top line opens it (the screen's top 48 dp, so the top of the Page no
    longer turns). One row per Chapter, the current one marked "you're here" (on the end page, the last
    Chapter of the text); a jump lands on a Page starting exactly at the Chapter, laid out afresh, as the
    Place, untimed; it clears Finished in the text and keeps it in Back matter. Opening Contents drops
    the Page's timing. The volume keys stay LightOS's there until N5.
  - Leaving a Book: every Reader state shows a way to the Shelf, since the back gesture can't be seen.
    Contents has "Shelf" on the right of its bar (ignoring taps for half a second, so a double tap on
    the top line stays in Contents); "Opening…", "Couldn't open this Book." and "This Book has no
    text." have "Back to Shelf" under them, as the end page does. `scripts/ci.sh` checks that Contents'
    Shelf reaches the Shelf on the emulator and the LP3.
  - Font changes: a Place that falls on the tail of a hyphenated word ("hor-/rors") or under a heading no
    longer starts the Page mid-word or strands the heading; the Page starts on the word's first line or
    the heading (30% guard), the Place unmoved (`pack`). Chapter jumps are exempt (`exact`). The walk
    never goes above the Place's Chapter's start, or the headings right before it (`pageFloor`), so a
    font change after a jump keeps the top line, Contents and minutes on that Chapter; a Page opening on
    a Chapter's heading goes by the line under it (`leadEnd`). The walk may take the line holding a
    mid-line Chapter start, so a word hyphenated across it stays whole; a Page opening on a Part's
    heading right above its first Chapter's goes by the Part, as a jump there chose, and so does one
    opening on an unlisted heading or caption above the Part's; a Page of only a Chapter's headings goes
    by that Chapter, never by Back matter, and a Part's title page of its own Spine item by the Chapter
    after it. Follow-up: a jump to a mid-line Chapter start, then a font change, can go by the Chapter
    before; fixing it means storing the Chapter start as the Place.
  - Reading from a Catalogue: "Read" on a Book's detail page closes the Catalogue's pages and list and
    opens the Reader over the Shelf, so every way out of a Book lands on the Shelf. Checked by hand on
    the emulator; `scripts/ci.sh` doesn't cover it (it needs a live Catalogue).
  - Text outside the known blocks (loose `<div>` text, tables, `<pre>` line breaks) reaches a Page
    (`DESIGN.md` "What a Book shows"): Gutenberg's HTML contents table, War and Peace's cipher table, the
    whole license. A table of inline text is one block (split at rows past a window, and at a row where
    the license starts); one whose cells hold blocks is read block by block. Each paragraph in a list
    item is its own block; hidden, `<svg>` and `<math>` text is skipped; Spine items marked `linear="no"`
    are skipped unless a table of contents lists them. Gutenberg's end-of-book lines now sit between
    `textEnd` and the listed license, so the first Chapter after Back matter's start moves back to it when
    no Chapter already starts there and no heading comes between (no second, "Chapter N" row). Standard
    Ebooks Books keep their `backmatter` Spine items (colophon, uncopyright) as Back matter, and their
    dedication, epigraph, foreword and the rest of what they mark `frontmatter`: only the title page, half-title, imprint and
    table of contents are dropped. Listed ones are Chapters, and the Book opens at its start, the first of
    them (Alice on its epigraph; `scripts/ci.sh`'s dev start moves to Chapter I, the text it opened on).
    Existing Places are re-found by their text (ADR 0002), nearest their stale block and offset: the
    snippet anywhere in the Spine item, else, within a window of 4 blocks before to 128 blocks or 32,000
    characters after, the snippet passing over the line breaks and whole blocks the parser now adds inside
    it (a list item's paragraphs that ran together, a caption now read between two blocks), else the
    nearest spot matching 20 or more of its characters, or a heading whose next paragraph changed. A Place
    lands at its block's start when its text is gone (text the parser now skips, or a new Edition's) or
    has moved past the window, and where two spots match its snippet alike (list items that open alike)
    only the stale spot tells them apart, so it can land on the other. The search runs off the main
    thread. The Shelf percent can shift slightly on the next page turn, as the Book has more
    characters. Pride and Prejudice keeps 63 Chapters in both Gutenberg Editions, War and Peace 385 with
    one license row. The images EPUB 3 has 8 Spine items, the last Gutenberg's cover wrapper page, whose
    "back" link shows after the license: Gutenberg doesn't mark it `linear="no"`, so it stays (drop it
    before the images flip, with the drop caps). _Decided:_ the owner wants dedications, epigraphs and
    forewords readable, so a listed one is a Chapter, as `CONTEXT.md` says of a listed preface.
  - QA fixes, Catalogue and Shelf: failed Catalogue fetches log their URL (no query, user info or
    fragment) and cause under `Reader`; a typed host that doesn't resolve on a connected phone reads
    "Couldn't find that address. Check the spelling.", still with Retry; a download that fails
    Unreachable while the phone reports no internet connection reads "download failed while offline ·
    tap to retry"; "Add" ends the Shelf's Edit; Add a Catalogue's placeholder is secondary text; a
    detail page names the author as the list row did when that row is the Book and names the same
    person. A pre-N4 Book with no author gets one when next opened, if its file names one (already so).
    Note: Gutenberg search's intermittent HttpError is its own. The search template points at
    m.gutenberg.org, which intermittently answers 504 (after about 5 s) instead of its usual 301 to
    www.gutenberg.org; a retry a little later works. Fetching www directly is a possible follow-up.
  - Footer and speed fixes from the N4 walkthrough: a Page read faster than 600 wpm gives no speed
    sample (skimming had dragged the Progress line near zero for as long as the Tool ran); "Opening…"
    has "Back to Shelf" (a long Book takes seconds to open); the Progress line falls back to a short
    form ("about 10 min left", "under 1 min left") when the full one doesn't fit, as at font scale 1.5;
    A− and A+ show disabled at the smallest and largest sizes.
  _Done when:_ Pride and Prejudice as the Tool downloads it (Gutenberg's
  no-images EPUB 2) shows its 61 novel Chapters, "Chapter I." to "CHAPTER LXI.", plus the title page and
  license its table of contents lists, across 15 Spine items; the images EPUB 3 gives the same 63
  Chapters across 8.
- **N5 · Reading controls.** Hidden while reading; a centre tap shows the controls (text never moves):
  top bar (back to Shelf + the Chapter title, with its Part as a running head), Progress line, bottom row
  "A−  A+  Contents". Asymmetric tap zones (back 30% / controls 25% / forward 45%); the six font sizes
  (15/17/20/24.5/30/36 sp, default 20; N5 adds the 15 sp row to `DESIGN.md`) and margins from `DESIGN.md`
  (one constants file); one-line first-run hint; keep the screen on while reading (release after 10 min
  without a turn); Page text in semantics; About screen (version, licenses incl. Literata OFL,
  copy-protected explainer + where to find Books without copy protection, repo URL as text, the ADR 0003
  no-network sentence). The detail page's CopyProtected line then points to About's list.
  _Done when:_ verified with `mise run ui`, including CopyProtected's pointer to About's list.
  From the N4 QA walkthrough, for N5 to settle: long Books' Contents (War and Peace: 385 rows, "CHAPTER
  I" ×17 with no Part named on the row or the top line, ~70 flings end to end; show the enclosing Part
  where titles repeat, and a way to the top or bottom; settled below); and the top line doesn't read as a
  control (the overlay's top bar replaces its job, or a cue until then). Parked: identical rows in search
  results and on the Shelf ("Alice's Adventures in Wonderland / Lewis Carroll" ×3: different identifiers,
  so separate Books once added) want a telling detail; Catalogue author forms keep titles of nobility
  ("graf Leo Tolstoy"), which could be dropped as life dates are.
  Decided before N5: no top line while reading (the controls' top bar takes its job and names the Part as
  a running head, "BOOK TWO: 1805 · CHAPTER I"); Contents lists Parts as heading rows, as a printed
  contents page does, and a Part row goes to its heading (nested tables of contents keep their Parts; a
  Part heading row, in nested tables of contents only, is never "you're here"; in flat ones the Part is
  already a Chapter row, never a second one, and is current like any Chapter); no Light/Dark button in
  v1; "Reader" on the Shelf opens About; the first-run hint shows once ever; a sixth, 15 sp font size.
  Still open: a way to the top or bottom of a long Contents (measure the scrollbar's track tap first).
  Parts in Contents (nested tables of contents) landed first. Finding Parts listed beside their Chapters
  (the running head's "BOOK TWO: 1805" in a flat table of contents) lands with the controls: the survey's
  R2h rule (a page of only headings, in a run of two or more; a repeating title or a uniquely titled peer
  ends it) leaves a Book numbered straight through its Parts ("CHAPTER I" to "CHAPTER LXXXVI" under
  "BOOK I" to "BOOK VIII") with no Parts, since every title is unique; settle that, and the CONTEXT Part
  entry's "falls under the Part listed last before it", against what the running head shows.
  Found at v0.1.0: a failed search on a Catalogue whose search template puts the terms in the URL path
  (Calibre's `/opds/search/{searchTerms}`) logs them, since the log redaction strips only the query;
  cut the path of a search result's URL too (`Network.kt`), and DESIGN's fetch-failure line then holds.
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
