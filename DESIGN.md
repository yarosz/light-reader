# Design notes

Tunable values and typesetting rules. Decisions that are hard to reverse live in `docs/adr/`; these are
constants and rules we expect to adjust from measurements.

## Type scale (LP3: 1080×1240, density 480 → 360 dp wide; panel ~419 ppi)

1 sp = 3 px ≈ 0.52 pt on the panel. Column = 360 dp − 2 × 20 dp margins ≈ 165 pt. Literata averages
about half an em per character. Sizes stay in **sp** so the system large-text setting scales the Reader.

| sp | ≈ pt | ≈ chars/line | Note |
|---|---|---|---|
| 15 | 7.7 | 44 | |
| 17 | 8.8 | 38 | |
| **20** | 10.3 | 33 | **default**: first size above 30 cpl, where hyphenation stops being constant |
| 24.5 | 12.6 | 27 | measured 26 on hardware |
| 30 | 15.5 | 22 | |
| 36 | 18.5 | 18 | large print |

At 20 sp a Page holds about 13 lines (40–60 words of dialogue-heavy text, a turn every 10–15 s at
230 wpm), so vertical space is precious: side margins 20 dp, top and bottom margins 12–16 dp, no footer
while reading. Line height 1.35 with `LineHeightStyle(Center, Trim.None)` (the trim setting is what
stops descenders leaking across Pages); verified leak-free on the LP3, where 1.4 was the fallback. All
of these live in `Typesetting.kt`. The Page fills the screen inside those margins, and the controls show
over it (see "Reading").

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
- A Page never ends on a heading, or on the caption split out of the heading after it; they move to the
  next Page with the heading's text.
- Guard: those rules give way if the Page would fall below 70% full. Consecutive hyphenated lines (a
  cascade) are an explicit test case.
- No widow or orphan rules in v1. At 26–38 characters per line most paragraphs are one to three lines,
  so the rules would fire constantly and cost a line each time.

The rules hold in both directions. A Page packed backward (reached by turning back past the Place of
the current pass) ends where the Page below it starts, so its start is chosen so that the line above
it is a legal end, with the same 70% guard: every Page end is legal unless the guard fired, or the
Place guard (below). What remains asymmetric: a backward pass may tile a stretch differently (but as
legally) from a forward one, and a cold backward crossing may leave a short first Page in the Spine
item (ADR 0007).

At the Place the break and heading rules hold for the Page above, within a guard of their own. After a
font change the Place can fall on a line that starts mid-word (the tail of "hor-/rors") or right under
a heading; the Page then starts on the nearest line above whose predecessor is a legal end (a break that
isn't a heading), so the word is whole, a heading comes with its text, and the Page before ends legally;
the Place sits a few lines (at most 30% of a Page) down its Page. Place guard: if that line is more than
30% of a Page above the Place's line (a long cascade), or the Place's line wouldn't then fit, the Place's
own line starts the Page and the Page above ends where it must. The walk also stops at the first line of
a layout window, so a window cut right after a heading keeps the Place's line, and it never goes above
the line holding the Place's Chapter's start, so the Page can't open in the Chapter before and go by it
(one exception: a jump to a Chapter starting mid-line stores the Page's start as the Place, inside the
Chapter before, so a later font change can name that Chapter):
when the Place's line holds the Chapter's start, that line starts the Page, and a Place on the line
under it (a Chapter starting mid-line on a line ending "hor-") still gets the whole word. A heading, or
run of headings, right before a Chapter's start with no text between counts as the Chapter's (a table
of contents may point at the paragraph after it), so the walk still takes it and the heading stays with
its text; a heading another Chapter starts in (a Part's, right above its first Chapter's) stays that
Chapter's own. The Place
itself doesn't move, so repeated font changes can't drift it. A Chapter jump is exempt: its Page starts
on the Chapter start's own line (Contents).

## Paragraph indent

A paragraph gets a first-line indent only when it follows another paragraph (Standard Ebooks' p + p
convention); the first paragraph after a heading, caption or verse, or a Spine item's first block, starts
flush, judged on block kinds, never on window boundaries.

## What a Book shows

- A Book that marks its body matter (Standard Ebooks marks every Spine item's `<body>`) drops the Spine
  items that aren't reading matter, even when the table of contents lists them: those whose `<body>`, or
  an element directly in it, has the `epub:type` `titlepage`, `halftitlepage`, `imprint` or `toc`.
  Everything else is kept, so the reader can read the dedication, epigraph, foreword, introduction or
  preface Standard Ebooks marks `frontmatter`. Those the table of contents lists are Chapters, in Contents
  before the first of the text; one it doesn't list is Front matter. A newly added Book opens at its
  start, as every Book does: for Standard Ebooks the first of those (Alice opens on its epigraph), as a
  Gutenberg Book opens on its title page, rather than skipping to Chapter I. A trailing run of
  `backmatter` items (colophon, uncopyright, endnotes) is Back matter, listed at the end of Contents. A
  Book that marks no body matter keeps every Spine item. Spine items with no text are dropped either way.
- Spine items marked `linear="no"` (auxiliary content, such as a cover wrapper) are skipped, unless a
  table of contents lists them as a Chapter (an entry with none nested under it) or every one is. A
  publisher's EPUB may mark its notes `linear="no"`; listed, they stay, in Spine order, so a contents entry
  pointing at them still works. An unlisted one is gone: a Place stored in it opens at the Book's start.
  `parseEpub` in `Epub.kt`.
- Parts. A table of contents entry with entries nested under it is a Part over the Chapters among them,
  never a Chapter itself. Its heading is where its href points, or its first Chapter's start when it has
  no href, when the href names no Spine item kept (an image-only divider page, a `linear="no"` one), when
  its fragment isn't there, or when where it points isn't after the Chapter before and at or before its
  first. It is no Part when it has no label, is named for
  the Book, or names a Spine item dropped as not reading matter: Standard Ebooks nests every Book under its
  half title. One over no Chapter is dropped, and a Chapter keeps only the outermost 8 Parts nesting it, so
  a crafted table of contents nested thousands deep still opens quickly. A Part listed beside its Chapters
  in the table of contents is a Chapter like any other: the running head names no Part over the Chapters
  after it (see "Reading"), as nothing in a flat list says which Chapters it holds. `parseEpub` in `Epub.kt`.
- Every piece of text in a kept Spine item reaches a Page, except what is never shown: `<head>`,
  `<script>`, `<style>`, `<svg>`, `<math>`, `<noscript>`, `<template>`, and any element marked `hidden` or
  `aria-hidden="true"`, with everything in them. The known blocks are paragraphs, headings, list items,
  definition terms and descriptions, figure captions and `<pre>`; an image's alt text is a caption of its
  own. A known block inside a list item or a definition is a block of its own, so each paragraph of a
  Standard Ebooks endnote is one. Text outside the known blocks, such as text sitting directly in a
  `<div>`, is a paragraph of its own up to the next element that isn't inline (an image with no alt text
  counts as inline). Italic and bold around a block or a run of loose text carry into it.
- A table whose cells hold only inline text is one paragraph with a line per row and its cells joined by
  " · ": a space alone would run the cells together, and aligned spacing doesn't survive proportional
  type. A cell with no text, or only no-break spaces, adds nothing. Rows as lines of one block set the
  table as a list, with no indent on each row. Once a table's paragraph passes 10,000 characters (a layout
  window, `WINDOW_CHARS`), its next row starts a new paragraph, which takes a first-line indent; so does a
  row where Project Gutenberg's license starts (`pg-footer`), since Back matter starts at a block. A table
  whose cells hold a known block, a heading or an image with alt text is read block by block, as if its
  cells were `<div>`s, so its headings stay headings. A table inside a known block adds its rows to that
  block, a line per row, cells joined the same way.
- `<br>` and the line breaks in a `<pre>`, even one inside another block, are line breaks; no-break
  spaces are kept, so a monospaced table built from them keeps its rough alignment. `XhtmlHandler` in
  `Epub.kt`.

## Copy

Copy capitalises Book, Shelf and Catalogue; "place" is lowercase; chapter is lowercase in running copy
and capitalised only in a "Chapter N" title; Edition, Spine item and Place-as-a-term never appear in
copy. In the Tool, copy says "copy-protected" and "without copy protection", never "DRM" or "DRM-free"
(ADR 0005); the README and the store listing may say "DRM-free". New copy uses the typographic
apostrophe and quotes (’, “ ”); older copy converts as it changes.

The first-run hint's lines, one per step (see "Reading"): "Tap here for the next page", "Tap here to go
back", "Tap the middle for controls".

## Reading

Product rulings from the advisor (N4 and N5, 2026-09-29); copy is verbatim, and `Progress.kt` holds it. A
Book slow to open reads "Opening…" (see **Opening**). One that can't be opened reads "Couldn't
open this Book." (the reason goes to the log), and one with no text reads "This Book has no text." Under
each is "Back to Shelf", as on the end page. System back (the LP3's back gesture) leaves the Reader from
anywhere, to the Shelf, which the Reader always opens over (see "Catalogues"), but a Tool can't show it,
so every Reader state has a visible way to the Shelf: "Back to Shelf" where there is no Page, and on a
Page the controls' back. The one exception is the blank first moment of an open, where only system back leaves.

**Opening.** A Book opens on its Place's Spine item, or, never opened, on the first that could start it: only
that is read before the first Page shows, and the rest of the Book comes in behind it, well within a second on
the LP3 (ADR 0009). Before the first Page the reading view shows only its blank background: "Opening…" (with
"Back to Shelf") shows once the open has taken 500 ms, and then stays at least 500 ms, so it never flashes; the
Page is laid out once it goes, so a slow or held open shows "Opening…", then the Page after its layout. Most
Books open within 500 ms and show no "Opening…". "Couldn't open this Book." and "This Book has no
text." show at once. Until the whole Book is in, the Page, turns within that Spine item and font changes work
as ever, and the running head names the Chapter its table of contents lists there (before the first one listed,
the Book's title, as in Front matter). The Progress line is blank, as in Front matter. A turn past the Spine
item's first or last Page waits, then turns, and every turn after it follows in order; a forward turn onto the
end page waits too, so it never shows early. The list icon waits without a sign and opens Contents once the
Book is in; a second tap adds nothing. The latest tap wins: showing the controls drops the turns still waiting,
and a turn, or anything else that hides the controls, drops a Contents still waiting. The Place, and a new
Book's place on the Shelf, are saved once the Book is in. When it is, the Page stays as it is (it lays out
afresh only where the whole Book sets it otherwise) and the Progress line fills in. A Spine item further on
that can't be opened takes the Page away, and "Couldn't open this Book." shows; it stays until the Book is
opened again from the Shelf.

**Layout.** The Page alone: it fills the screen inside the margins, and nothing else shows while reading
until a centre tap shows the controls, but for the first-run hint (below). The screen is three full-height
columns, margins included: a tap in the left 30% turns back, one in the right 45% turns forward, and one in
the 25% between shows the controls (`Typesetting.kt`); to a screen reader they are the buttons "Previous
page", "Show controls" and "Next page". They are there once a Page or the end page shows, and not while the
controls show. The volume keys turn too, down forward and up back. A Page goes by its start, or, when a
Chapter starts later in its first line (a table of contents may point mid-line, or at a Part's heading
right above its first Chapter's), by the last Chapter starting there, so a jump names the Chapter chosen. A
Page that opens on headings, with no Chapter starting in its first line, goes by the first Chapter starting
in them (of two starting at one point, the later), else by its first line under them: a Page opening on an
unlisted heading ("VOLUME I", or an illustration's caption) above a Part's heading goes by the Part, and a
Page opening on a Chapter's heading, with the Chapter's start on the next line, goes by that Chapter. A
Page of only headings goes by the Chapter starting where it ends, whose headings they are, unless Back
matter starts there; the end of a Spine item counts as the next one's start, so a Part's title page that is
a Spine item of its own goes by the Part's first Chapter. The Page takes the whole height, so Pages re-pack
at the Place, which a layout change never moves.

**Controls.** A centre tap shows the controls over the Page, which stays as it is under them: a top bar
(the SDK's `LightTopBar`: back on the left, which leaves the Reader as system back does and is "Back to
Shelf" to a screen reader, the running head in the centre, and on the right the SDK's list icon, which
opens Contents and is "Contents" to a screen reader), and at the bottom one 48 dp row: "A−" and "A+" at
its ends, each with 12 dp of padding at the sides and filling the row's height as its tap target, and the
Progress line between them. A 1 dp rule in secondary text parts each block from the Page, and a tap on a
block's blank space does nothing. The running head names the Chapter the Page goes by, verbatim: its
title on one line, in the SDK's one-line title, ellipsised at the end; when the table of contents nests
the Chapter in a Part, the nearest Part's title is a line above it, the two in the SDK's two-line title,
each ellipsised, so a long Part never hides the Chapter. A Part listed beside its Chapters is a Chapter
of its own, named only on its own Pages. In Front matter, and on the end page, it is the Book's title as
the Shelf shows it. At the smallest size "A−", and at the largest "A+", is drawn in secondary text,
ignores taps, and is a disabled button to a screen reader. The Progress line is one line, in Detail and
secondary text, centred; when its full form, measured as drawn, doesn't fit between "A−" and "A+" (large
system text), it shows its short form (below), ellipsised only if even that doesn't fit; with no line (in
Front matter, on the end page, in Back matter, in a Chapter that reads in under a minute, or while the Book
is opening behind its first Page) the space between "A−" and "A+" is blank. A tap anywhere else, the Page included, hides the controls without
turning (to a screen reader, "Hide controls", the one button over the Page while they show); every turn
hides them, so a volume key turns and hides them (on the end page volume down only hides them); a font
change keeps them; opening Contents keeps them, so back from Contents returns to the Page as it was,
controls and all, while a Chapter or Part chosen there shows its Page without them; the Tool pausing while
reading hides them (a pause in Contents leaves them as they were), and the reading view always opens
without them. Showing them keeps the Page's timing, the time they show counting as time on the Page.
"Chrome" and "overlay" are not names for them.

**Progress line.** The minutes left in the Chapter: the words from the point the Page goes by to the Chapter's
end (the next Chapter's start, or the end of the Book's text if that comes first), divided by the
reading speed. A word is a whitespace-separated run of text. The words are indexed once, off the main
thread, behind the Book's first Page (see "Opening"), so a turn counts them without reading any text. On
the raw minutes m:

| m | Line | Short form |
|---|---|---|
| under 1 | "almost done with this chapter" | "under 1 min left" |
| 1 to under 15 | "about ⌈m⌉ min left in this chapter" | "about ⌈m⌉ min left" |
| 15 and over | "about ⌈m/5⌉×5 min left in this chapter" | "about ⌈m/5⌉×5 min left" |

"chapter" stays lowercase (see "Copy"). There is no line in Front matter, on the end page, in a Chapter
whose whole text reads in under a minute at the current speed, or while the Book is opening behind its first
Page.

**Reading speed.** 230 words a minute until there are 5 samples, then the median of the newest 20 or
fewer. A sample is the words on a Page divided by the time on it. It counts only when the Page was
reached by a forward turn of one Page and left by one, holds at least 20 words, was on screen for 2 s
to 3 min, and was read at 600 words a minute or slower: faster is a skim, or a hunt for a passage (50
words every 2.5 s is 1,200), and a few minutes of it would drag the Progress line to nothing for as long
as the Tool runs. A back turn, a font change or relayout, opening Contents, a Chapter jump,
reopening the Book, or the Tool pausing drops the running timing. Samples belong to the reader: they are
shared across Books, kept in memory for as long as the Tool runs, and never saved.

**End page.** A forward turn (a tap in the right 45%, or volume down) from the Page that reaches the end
of the Book's text shows the end page: "The end." centred, and at the bottom "Back to Shelf", which
leaves the Reader as system back does, kept out of the middle so a centre tap there shows the controls.
The controls' running head shows the Book's title, with no Progress line. Forward does nothing there;
back (a tap in the left 30%, or volume up) returns to the last Page. The end page is not a Page, so the
Place stays on the last Page. The last Page of the text ends where Back matter starts, however short that
leaves it, so Back matter never shares a Page with the text.

**Back matter.** From the text, reached only through Contents, never by turning past the end page. Inside
it, turns work as anywhere else: the running head shows its Chapter's title, the controls have no
Progress line, forward on the Book's last Page does nothing, and no second end page shows. A Place saved
there opens there. Back matter's first row starts where Back matter does: the first Chapter listed in it
moves back there when no Chapter starts there and no heading comes between (Gutenberg's `*** END OF THE
PROJECT GUTENBERG EBOOK … ***` lines before its license), else an untitled row takes its first heading.

**Contents.** The controls' list icon opens it, from a Page, the end page, Front matter or Back matter,
and drops the running timing even when back then returns without a jump. It is a screen titled "Contents"
with the bar's back on the left, and one row per Chapter in order, each the Chapter's title verbatim at
full strength, at most two lines, with a row for each Part as below; Back matter's rows follow the text's
with no divider. As a printed contents page sets them, a Part the table of contents nests Chapters under
("What a Book shows") has a row of its own before its first Chapter's, outermost first, its title
verbatim in the SDK's Heading style, at most two lines, marked as a heading for a screen reader (The
Brothers Karamazov: "Part I", "Book I: The History of a Family", then "I: Fyodor Pavlovitch Karamazov").
A Part listed beside its Chapters is already a row, its Chapter's, so it gets no second. The current row
alone has the detail line "you’re here", with a typographic apostrophe: the Chapter the Page goes by, as the running head names it (of
two starting at one point, the later), or on the end page the last Chapter of the text. A Part's heading
row is never current, and in Front matter no row is. The list opens with the row before the current one
at the top, so the current row is second, or with the current row at the top when it is first; with no
current row it opens at the top. When the current Chapter is the first of its Part, the row before is the
Part's, so the list opens on that Part's heading. The list has a top and a bottom; "start" and "end" stay
the Book's and a Chapter's. Tapping a row goes to the Page that starts at that Chapter's start, or at the
Part's heading, laid out afresh from there so its heading tops the Page. A Part's heading Page goes by
the Part's first Chapter when only headings come between them, else by the Chapter before it, or by none
in Front matter (a Part's epigraph or introduction reads under the Chapter before). Back matter's first
row may open on Back matter's opening lines instead. A jump lays out afresh even for the current Chapter,
and even when that line starts mid-word (a Chapter anchored inside a paragraph): a jump never moves the
start up to a whole word as a font change does. The Pages before it may tile differently, as after a font
change. That Page is the Place, it is untimed, and the jump leaves the end page. Back, from the bar or
the system, returns to the reading view and changes nothing else; Contents has no way straight to the
Shelf, so a double tap on the list icon can't leave the Book. The volume keys stay LightOS's on this
screen.

**Finished.** Showing the end page sets Finished, and the back turn from it clears it; leaving it
either way keeps it. Setting or clearing it re-stamps the Place, the same Place with a newer time, so
a merge with an older copy of the file keeps the change. A Finished Book opens at its Place, the last
Page of the text. The first back turn clears Finished, and a forward turn shows the end page again. A
Contents jump to a Chapter or Part of the text clears it too, even to the last Chapter, whose first Page it
lands on; a jump into Back matter keeps Finished and never sets it. A back turn inside Back matter keeps
Finished; the one onto the last Page of the text clears it. A font change keeps Finished, and so does
reopening the Book and leaving at once. At
another font size a Finished Book's Place may land before the last Page, and forward turns reach the
end page without clearing it.

**Keep awake.** The screen stays on while the reading view shows a Page or the end page, until 10 minutes
pass with no tap, a screen reader's included, or volume key press there, then follows the phone's own
timeout. It never stays on while the Tool is paused.

**First-run hint.** A walkthrough of the three tap zones on the reading view, until the reader has taken it
once across all Books: a touch dot, like Android's "Show taps", pressing in a zone at mid-height (a filled
circle 40 dp across in the content colour at 62%, with a faint 1 dp ring, fading in, pressing to 82% and
back, then resting at 75%, every 2.4 s), and one line of copy just below it in the SDK's Detail style, in a
box inverted from the Page: the content colour as its fill and the background colour as its text, so white
with black text on the LP3's black, and no rule. It is centred on the dot but kept inside the margins. It
appears 1 s after its step can show: once the Book's first Page shows, after each step moves on, and when
the controls hide or the end page is left. Before it appears every tap and key does what it always does,
and a tap in the step's zone doesn't move it on. Three steps, each waiting for a tap in its zone: forward
first ("Tap here for the next page"), the first need and the largest zone; back second ("Tap here to go
back"), which turns back, and moves on even on the Book's first Page, where it can't turn; the middle last
("Tap the middle for controls"), which opens the controls and ends it. While the guide shows, the
walkthrough must be followed: a tap in its step's zone does what it always does and moves it on, a tap in
another zone does nothing, and the volume keys do nothing either (LightOS still doesn't get them, so the
volume stays), as it teaches the taps. Both still count as a press for "Keep awake". Once it ends, every
tap and key does what it always does again. System back leaves the Reader as ever. It is saved as dismissed
only at that last tap (`readingHintDismissed`): leaving the Book, or the Tool pausing while reading,
mid-walkthrough saves nothing, and it starts again from the first step, while a pause in Contents leaves it
at its step. It hides while the controls show and returns at its step when they hide; it never shows on the
end page, and a tap there doesn't move it on. Never in a dev-start session. The guide takes no taps. To a
screen reader it is only the line of copy, as plain text, announced as each step appears and too small to
hide a tap zone's button; while it shows, the two zones its step doesn't point at are disabled buttons, as
a tap there does nothing.

**Stored.** Every Place write stores `progress` (additive, ADR 0002): the share (0–1) of all the
Book's characters before the Place, Front matter and Back matter included, to 4 decimals. The Shelf's percent
comes from it (see "Shelf"). The minutes left in a Chapter are never stored.

## Shelf

The Tool's first screen: the Books on this phone. Product rulings from the advisor (2026-09-27); copy
is verbatim, and `Shelf.kt` holds it.

**Top bar.** `LightTopBar` with "Reader" in the centre, which opens About. The left slot is "Edit",
free because the Shelf is the root screen; it reads "Done" while editing and is hidden when the Shelf is
empty. The right button is "Add", which opens the list of Catalogues.

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
| Failed, Unreachable while the phone reports no internet connection | "download failed while offline · tap to retry" | downloads again |
| Failed for good, downloading again from the Shelf | "can't download again · copy-protected", "· not an EPUB" or "· needs https" | nothing; the Book can only be removed |
| Failed for good, the source needs a login (401) | "can't download again · needs a login" | nothing; the Book can only be removed |
| Finished | "author · finished" (see below) | opens the Book |
| File missing, source known | "file missing · tap to download again" | downloads again, keeps the Place |
| File missing, no source | "file missing" | nothing; the Book can only be removed |

The percent is the Place's `progress`, floored. Under 1%, or with no `progress` (a Place saved before
N4, until the next page turn), the author stands alone. When there is no author, the state stands
alone ("42%", "finished"), so an in-progress Book with neither has no second line until it is next
opened, if its file names one: opening records the author from the Book's file (`dc:creator`), and
the Shelf never opens a file to find one. A running download
wins over a Book's file, the file over a failed download (a Book that is here stays readable
offline), and both over its reading state. Opening a Book counts as reading: it records the first
Page's Place once the whole Book is in (see "Reading"), so a Book opened but never paged reads as in
progress, not "not started".

**Empty.** "Nothing on your Shelf yet." with a text button "Add a Book". Text labels, not glyphs.

**Edit.** Every row's trailing edge becomes "Remove", in secondary text. Tapping it turns the row
itself, inline and not as a modal, into the Book's title, then "Remove from Shelf? Your place is kept
if you add it again.", then "Remove" and "Cancel". A confirmation whose row goes away (a download
that arrives, or fails) is cleared. Row taps don't open Books while editing. Removal deletes the file and keeps the Place
record, and removing a Book that is downloading cancels the download. Removing the last Book leaves
Edit, and so does "Add": the Shelf is browsing when the reader comes back from the Catalogues. (The
Catalogue list's Edit needs no such rule: while editing, its only way out is back, which closes it.)

**Downloads.** Foreground only, with a visible state; no background service in v1. Only retryable
failures (Unreachable, HttpError other than 401, DiskError, and UntrustedCertificate, D15) leave a
row: "download failed · tap to retry", or the offline form (below). An untrusted certificate is retryable because public
Wi-Fi intercepts TLS until the reader signs in to it. Permanent failures (CopyProtected, NotAnEpub,
and also NoHttps and a 401, which a retry can't fix, since sign-in doesn't exist) of a download from a
Catalogue show their copy on the Book's
detail page and add nothing to the Shelf. A copy-protected Book must never become a row that can't be read. When
downloading a missing file again from the Shelf fails for good, the Book's row says why ("can't
download again · …", table above) and can then only be removed. That state lives in memory: after a
relaunch the row reads "file missing · tap to download again" again, and a tap tries once more.
A download that fails as Unreachable while the phone reports no internet connection reads "download
failed while offline · tap to retry", so a retry tapped offline visibly answers (it fails at once, too
fast for "downloading…" to show). It is in the past tense because the row stays after the phone
reconnects. Any other failure, or one when the phone can't report, reads "tap to retry".

**Offline.** Nothing changes on the Shelf, because everything there works offline: no rows are
removed and nothing is greyed out. "You're offline. Your Shelf still works." is one line of
secondary text at the top of the Catalogue list, shown while the phone reports no internet
connection (see "Catalogues"). The one offline wording on the Shelf is a download's that failed
offline, "download failed while offline · tap to retry", which stays true once the phone reconnects.

**Missing file.** A Book whose file is gone reads "file missing · tap to download again", and the tap
downloads it again from the Book's source, keeping its Place. If the download declares a different
`dc:identifier` (Calibre mints a new one on every conversion), the Book keeps its row: its Place,
date added and title move to the new identifier, and the Place resolves as well as the new Edition
allows. If a Book with the new identifier is already on the Shelf, the two rows become that one: it
keeps its own title and date added, takes whichever Place is newer, and the old row leaves the
Shelf. A Book with no known source (one imported through the Tool Manager, below) reads "file
missing" and can only be removed.

**Adding your own Books.** Owner's rulings (2026-10-08); copy is verbatim, and `Shelf.kt` holds it.
LightOS's Tool Manager (the phone's page in a computer's browser) lists Reader as "Reader", with one
page, "Add Books": an upload page whose header reads "EPUB files without copy protection. Each one
appears on Reader’s Shelf." and whose button reads "Choose EPUB files". It takes several files at
once. The page is one-way: Reader moves each Book it accepts out of the shared folder into its own
storage, so nothing stays there and no Book is stored twice, and the browser never lists what was
uploaded. Removing a Book stays a phone action (Edit, then Remove). Nothing in the Tool mentions the
page yet (not About, the README or the empty Shelf): it is advertised only once it is confirmed to
work on retail LightOS (LEDGER N7).

An uploaded file is imported, not downloaded, but it passes the checks a download passes (a zip, not
copy-protected, a package Reader can read, at most 300 MB) and gets the download's file name, so a
Book is the same Book whichever way it came. It goes on the Shelf with its `dc:title` (else its file
name) and `dc:creator`, as a never-opened Book added now, with no source. A Book whose identifier is
already stored keeps its row and its Place, even after a removal; its file is replaced, and its title
becomes the file's. That holds for a different file that declares the same identifier too: it is the
same Book (CONTEXT "Book"), so it replaces the stored one's file and keeps its Place. A file stored
under an older name (one opened before files were named by identifier) is deleted then, unless
another Book names it. One that came from a Catalogue keeps its source, so a missing file can still
be downloaded again.

Reader imports whenever LightOS reports an upload (about 3 s after a file's last write) and whenever
the Shelf shows, each time scanning the whole folder, one pass at a time. LightOS sends its report to
Reader's process even with no screen open, but gives it no way to reach Reader's files, so the report
imports only once a screen has opened in that process; otherwise the files wait for the Shelf. LightOS
drops a report that comes while a pass runs, so a pass that took files scans again, and before it
ends it lists the folder once more and scans again if a file came or changed since its last look.
Each file is checked as found, and moved or deleted only if nothing has written to it since; one that
changed waits for the next look. Each file:

- **Still arriving.** LightOS writes an upload in place under its own name, so a partial file sits in
  the folder while it arrives, and stays open if the browser stalls. Such a file is never rejected:
  one that fails a check within a minute of its last write is left for later. A pass that leaves one
  looks again every 10 s, up to six times (the minute); after that, the next report or Shelf showing
  takes it. A file that passes is taken at once, since a partial zip never passes. A zero-byte file
  counts as just created for that minute, and as not an EPUB after.
- **Rejected.** A file that fails is deleted, and the Shelf says why (below). One that can't be
  deleted stays, with its line, and is checked again by later passes, never in a loop.
- **Hidden.** A name starting with "." is checked like any other: imported if it is a Book, and
  otherwise rejected with a line (macOS's `._` files among them).
- **Folders.** Left alone: the page can't make one.
- **No room.** A Book needs 16 MB free beside it, as a download leaves (moving it costs no space, but
  the reading data must still save). Short of that, the file stays in the folder rather than being
  deleted, the Shelf says so, and every pass tries it again, so it is added once there is room. Until
  then its line comes back each time the Shelf shows.
- **Not saved.** A Book whose move out of the folder fails with room to spare stays too, with its own
  line, and every pass tries it again. A move that fails short of room reads as no room.
- **Interrupted.** Moving a Book onto the Shelf is a rename, then a save of the reading data. A Book's
  file (a name made from its identifier) that no Book names, as a process killed between the two
  leaves, goes back into the folder at the start of each pass and is imported again; a download killed
  the same way is healed too. This happens only when the reading data was read from its file or its
  backup: when neither exists or parses, every Book's file would look unnamed, so all stay where they
  are. Downloads in progress use temp names, the file of a Book taken off the
  Shelf stays where it is (a removal that couldn't delete it must not bring the Book back), and other
  names, such as the dev file, are never touched. Recovering rather than saving before the move keeps
  the move a single rename that needs no undo when it fails.

**Import notice.** The browser says "uploaded" whatever Reader does with a file, so the next time the
Shelf shows, its list starts with a line for each file Reader didn't add, above the rows or above
"Nothing on your Shelf yet.": secondary text, like the Catalogue list's offline line, oldest first,
each wrapping to at most two lines so its reason shows. After three files, one more line counts the
rest: "and 2 more". A line is "Couldn’t add <file name>: <reason>.", such as "Couldn’t add notes.pdf:
it isn’t an EPUB.", and the reasons reuse the failed-download copy:

| Failure | Reason |
|---|---|
| Not an EPUB (not a zip, or a package Reader can't read) | "it isn’t an EPUB" |
| Copy-protected | "it’s copy-protected" |
| Too large (over 300 MB) | "it’s too large" |
| No room | "there isn’t enough space on your phone" |
| Not saved (the move failed with room to spare) | "it couldn’t be saved on your phone" |

The notice goes once read: a tap on it clears every line, and so does the next time the Shelf shows
after showing it (back from a Book or the Catalogues, the screen turned back on, or Reader reopened).
A line counts as shown once the Shelf has drawn it while showing, from the SDK's show to its pause or
hide. The SDK pauses a screen when the phone's screen turns off or Reader goes to the background, and
never hides it then, so a line that arrives meanwhile (the usual upload: Shelf open, the reader at the
computer) is still there when Reader comes back, and goes at the show after that. The lines are kept
in `import-notices.json` beside the reading data, which they leave unchanged (schemaVersion stays 1),
so a report handled with no screen open still shows at the next Shelf. A later line for the same file
name with another reason replaces the earlier one; the same line again leaves it as and where it was,
and adding the file clears its line. The log says only how many files were added, refused,
recovered or left undeletable, never a file's name or a Book's title.

## Catalogues

Where Books are found and added. Product rulings from the advisor (D14, D15 and the N3c rulings,
2026-09-27); copy is verbatim, and `CatalogueCopy.kt` holds it. Copy capitalises Book, Shelf and
Catalogue and keeps "place" lowercase; row second lines stay lowercase, because they are states, not
sentences.

**The list.** "Add" and "Add a Book" on the Shelf open it. `LightTopBar`: back, "Add a Book" in the
centre (the title, kept for continuity), and "Edit" on the right, which reads "Done" while editing
and is hidden when the list is empty. Rows are each Catalogue by name: the shipped ones first, in
shipped order, then the reader's own, oldest first. A Catalogue the reader added has its host as a
second line ("books.example.org"); a shipped one has none. The last row is "Add a Catalogue", shown
while browsing.

**Offline.** "You're offline. Your Shelf still works." is one line of secondary text at the top of
the list, shown while the phone reports no internet connection. The list follows the SDK's
`LightConnectivity` reports while it is open (which needs the normal `ACCESS_NETWORK_STATE`
permission), so the line comes and goes with the connection. When the phone can't report, the line
is not shown, so it never claims more than the phone did. The last fetch's result isn't used: one
unreachable server doesn't mean the phone is offline.

**Removing.** In Edit every row's trailing edge reads "Remove", in secondary text. Tapping it turns
the row, inline, into the Catalogue's name, then "Remove this Catalogue? Books you added from it stay
on your Shelf.", then "Remove" and "Cancel". Any Catalogue can be removed, the shipped ones too.
Removing the last one leaves Edit.

**Add a Catalogue.** Title "Add a Catalogue", then a field labelled "Catalogue address" with the
placeholder "https://…" in secondary text (the SDK's field draws it at full strength, as if typed),
then "Add". Tapping the field opens the SDK's text editor with the LP3
keyboard, whose button is also "Add". An address with no scheme is taken as https://; http:// is
tried once as https://, and a server with no HTTPS reads as NoHttps: a refused connection or a
failed handshake. A connect timeout stays Unreachable (see the note under the failure copy). The
Catalogue is stored as https://, so once added it is never "tried as https" again: a later refused
connection is Unreachable, with Retry. The feed is fetched before
anything is saved: a page that isn't a Catalogue feed shows Unreadable's copy and adds nothing. A host
that doesn't resolve (no DNS record) is NoSuchHost only while the phone reports an internet
connection: offline every lookup fails that way, so offline, or when the phone can't report, it is
Unreachable, with Retry. NoSuchHost keeps Retry too (see the note under the failure copy). A
Catalogue already on the list never reads NoSuchHost (its host resolved when it was added), only
Unreachable. The
name is the feed's title, else its host. An address already on the list reads "This Catalogue is
already in your list." A failure shows below the field in body text (the SDK's Paragraph size) at
line height 1.2, smaller than the rows. A failure that trying again can't fix hides "Add" until the address changes;
one that can reads "Retry". Only when a shipped Catalogue has been removed, one row per removed
Catalogue follows the field: "Add back Project Gutenberg", "Add back Standard Ebooks: new releases".
One tap adds it back, with no confirmation. Typing a removed shipped Catalogue's address adds it back
too.

**Stored.** `reading-data.json` gains `catalogues` (additive, ADR 0002): the reader's last change to
each Catalogue, `{name, url, removed, updatedAt}`, keyed by the Catalogue's URL in one form. The key
lowercases the host, drops the default port and the fragment, reads an empty path as "/" and ignores
one trailing slash, so "www.gutenberg.org/ebooks.opds" is the shipped Gutenberg Catalogue and
"https://books.example.org" and "https://books.example.org/" are one Catalogue. The key only
compares: `url` keeps the address a Catalogue was added with when it differs from the key, and that
is what is fetched. Reading the file re-keys every entry, so an `http://` or slashed key written by
hand or by an import is the Catalogue the list shows and can remove; a key that isn't a URL reads as
missing. A shipped Catalogue has a record only once it has been removed, and keeps one once added
back. Whether a Catalogue is shipped is decided by its key alone. A merge keeps both sides' records
and, for a Catalogue on both, the newer record's fields, with the unknown fields of both (the
winner's on a clash), as for a Book. At a tie a removal beats an addition, so the result doesn't
depend on which side saved last. A removal is a record rather than a missing entry, so it survives a
merge with a file that still lists the Catalogue, and adding it back later wins the same way.

**A page.** Back, and the Catalogue's name, the tapped entry's title, or the search terms in the
centre. Rows are text only: the title, then the byline (the author, else a short one-line content).
An entry with a download of its own opens its detail page; any other opens the feed it leads to, and
a feed opened that way is a Book's detail page when all its entries are one Book's Editions
(Gutenberg's Book pages). rel=next paging is a "More" row at the end, which appends the next page.
"More" ends when the next page is one the list already fetched, and at 500 entries: the list
composes every row, and past 20 of Gutenberg's pages search finds a Book faster.
"loading…" in secondary text stands in while a page loads.

**Search.** Shown only when the page offers one: a text-only field, its placeholder "Search all
Standard Ebooks" on Standard Ebooks' new releases (its search covers all of Standard Ebooks) and
"Search" elsewhere, over a rule. It opens the same editor, with a "Search" button, and the results
are a page of their own.

**Book detail.** The title, the author, then one action, then the summary only when it is prose (a
"Key: value" dump such as Gutenberg's is hidden, and so is a summary that only repeats the author).
One "Add to Shelf" per Book, across the page's Editions, with the download size in secondary text
beside it ("558 KB"). The page matches the Shelf by source URL, never by title: any of the page's
download links equal to a stored Book's source is that Book. A miss is harmless, because a landing
download merges into the Book by `dc:identifier`.

The author is the Editions' authors, except that the byline of the row that opened the page wins
when that row is this Book (its title is the page's first entry's, ignoring case and punctuation)
and its words are the Editions' author's, ignoring case, order and punctuation, or the Editions name
no author. So a list and its detail page name the author alike where their forms differ. A row's
second line isn't always an author (Gutenberg's "Our most popular books.", a Calibre category's "1
book", a description), hence both checks. A download records it only when the Book's file names no
author (`dc:creator`), which otherwise wins when the file lands.

An author's name drops what a library catalogue adds to it (`displayAuthor` in `Atom.kt`). Life dates
go, and so does a title of nobility written in lowercase: one title word as a comma-separated part of
the simple inverted form ("Tolstoy, Leo, graf, 1828-1910") or after a name in reading order ("Leo
Tolstoy, graf"), or a title leading a name in reading order, as a list row's content has it ("graf Leo
Tolstoy"), when every word after it is capitalised or a particle such as "de" or "von". All three read
"Leo Tolstoy"; Gutenberg's "Kropotkin, Petr Alekseevich, kniaz, 1842-1921" and "kniaz Petr Alekseevich
Kropotkin" read "Petr Alekseevich Kropotkin". The titles are a fixed list, with Gutenberg's spellings
(graf, count, baron, freiherr, prince, principe, kniaz, knyaz, książę, duke, duc, marquis, earl,
viscount, vicomte, comte, conte, conde, hrabia, gróf, fürst and their feminine forms). A row's summary
that only repeats its author, title and all, isn't shown as a summary. A capitalised title ("Baron
Corvo", "Corvo, Baron", "Baroness Emmuska Orczy Orczy") may be a
pen name and stays; so do "Sir", a title with its particle ("vicomte de"), and a lowercase word
before text that isn't a name ("baron of the Exchequer"). Then only the simple inverted form
un-inverts: "Austen, Jane" reads "Jane Austen"; further commas, organisations and several authors stay
as the feed gave them, a title of nobility included.

| Match | Action | Beside it |
|---|---|---|
| None | "Add to Shelf" | the size |
| On the Shelf with its file | "Read", which opens the Book at its Place | "On your Shelf" |
| On the Shelf, file missing | "Download again", keeping the Book's row and Place | the size |
| Removed | "Add to Shelf", keeping the Place | the size |
| A download running | "downloading…" in secondary text | |

"Read" closes the Catalogue's pages and "Add a Book", and opens the Reader over the Shelf with no frame
of them between, so leaving the Reader (system back, the controls' back, "Back to Shelf") always lands
on the Shelf. Where the reader was in the Catalogue (a search, say) isn't kept: "Add" opens the list
afresh. The page stays while a download runs and follows it: "downloading…", then "Read" when it lands.
The download belongs to the Shelf, so leaving the page doesn't stop it, and the Shelf shows it as a row
meanwhile. A failure shows its copy above the action: a retryable one turns the action into "Retry", and
a permanent one (CopyProtected, NotAnEpub, NoHttps) removes it and adds nothing to the Shelf.
CopyProtected's line then points to About's list of places to find Books without copy protection, as
text: "About, from “Reader” on the Shelf, lists places to find Books without copy protection." It isn't
a button (the Shelf row's "can't download again · copy-protected" has no pointer).

**Failure copy.** One plain line, with "Retry" wherever retrying can help and never a dead button.

| Failure | Copy | Retry |
|---|---|---|
| Unreachable | "Can't reach this Catalogue. Check your connection and try again." | yes |
| NoSuchHost (a typed address, the phone connected) | "Couldn't find that address. Check the spelling." | yes |
| NoHttps | "This Catalogue needs an https:// address." | no |
| HttpError | "This Catalogue isn't responding properly. Try again later." | yes |
| HttpError 401 | "This Catalogue needs a username and password. Sign-in isn't supported yet." | no |
| Unreadable | "This address isn't a Catalogue the Reader can open." | no |
| UntrustedCertificate, shipped Catalogue | "This connection isn't trusted. If you're on public Wi-Fi, sign in to it, then try again." | yes |
| UntrustedCertificate, the reader's Catalogue | the same, then "A self-hosted Catalogue needs a public certificate." | yes |
| NotAnEpub | "This file isn't an EPUB the Reader can open." | no |
| CopyProtected | "This Book is copy-protected and can't be opened here." | no |
| DiskError | "There isn't enough space on your phone to add this Book." | yes |

A timeout on a typed http:// address is NoHttps only on a validated network: a server that drops
connections to port 443 times out, but so does a network with no internet. The SDK's
`LightConnectivity` reports only whether the network claims internet, not whether Android validated
it, and asking Android directly needs a Context, which a Light Tool can't hold. So the Tool can't
tell, and every connect timeout reads as Unreachable, with Retry.

NoSuchHost keeps Retry for the same reason: the phone reporting a connection means only that the
network claims internet. A Wi-Fi with a dead upstream, DNS failing for a moment, or a redirect to a
host that is down fails a correctly typed address the same way, and trying again can fix those.

Every request (a Catalogue page, "More", a search, a download, and each redirect hop) sends the
User-Agent "Reader/<versionName> (+https://github.com/yarosz/light-reader)", so a server's logs can tell
the Tool apart; it names the Tool and its version, nothing about the reader or the phone.

Every failed Catalogue fetch (a page, "More", a search description) logs one line under the `Reader`
tag: the URL, the HTTP status (with where redirects ended) or the exception, and the failure it
became. Each URL is logged without its user info, query (shown as "?…") or fragment, and a search's
results page, or its "More", keeps only its host (the rest shown as "/…", or "?…" when only a query
follows the host), since a search template may
put the terms in the path (Calibre's `/opds/search/{searchTerms}`). So neither a search's terms nor
credentials reach the log.

## About

What the Tool is and promises, as static text: nothing on it is fetched, stored or changed, it needs no
network, and nothing on it is tappable but back. "Reader" in the Shelf's top bar opens it: a screen
titled "About", with the bar's back on the left, which returns to the Shelf, as system back does. Its
text scrolls, in the SDK's styles: each block's heading in the Heading style (a heading to a screen
reader), then its paragraphs in the Paragraph style. Copy is verbatim, and `About.kt` holds it; it
follows "Copy" (above), so it says "copy-protected" and "without copy protection", never "DRM".

1. The Tool's name and version, "Reader 0.1.0" (`VERSION_NAME`, which a test keeps equal to
   `versionName` in `tool/lighttool.toml`; a Light Tool can't ask Android for it). Then ADR 0003's
   promise, "Reader has no accounts, no service of its own and no tracking. It uses the network only to
   fetch a Catalogue and download a Book, and reading works fully offline.", and "While you read, the
   screen stays on until 10 minutes pass without a tap or key press, then follows your phone’s own
   timeout." (see "Keep awake"; the 10 minutes are fixed, with no setting).
2. "Books without copy protection": "Reader opens only Books without copy protection. Copy-protected
   Books, such as those from Kindle, Apple Books or Libby, can’t be opened.", then "Places to find
   them:" and a line each, only what Reader can reach today: "Project Gutenberg (built in)", "Standard
   Ebooks (built in)", "Any Catalogue you add over https" (a Catalogue must be https, so a Calibre
   server on the home network, usually http, can't be promised). This is the list a copy-protected
   Book's detail page points to (see "Catalogues").
3. "Source code": github.com/yarosz/light-reader, as text to read, since a Tool can't open a browser.
4. "Licenses", grouped by license, one line each: "Reader: MIT License, © 2026 Nicolas Yarosz.",
   "Literata, the typeface of Book text: SIL Open Font License 1.1, © 2017 The Literata Project
   Authors.", "Light’s SDK and keyboard: MIT License, © 2026 The Light Phone.", "AndroidX, Jetpack
   Compose, Material Components, Kotlin, kotlinx.coroutines, kotlinx.serialization, Ktor, OkHttp, the
   UnifiedPush connector, Tink and Guava: Apache License 2.0.", the three JetBrains NOTICE lines
   ("Kotlin: Copyright 2010-2024 JetBrains s.r.o and respective authors and developers.",
   "kotlinx.coroutines: Copyright 2016-2025 JetBrains s.r.o and contributors.",
   "kotlinx.serialization: Copyright 2017-2019 JetBrains s.r.o and respective authors and
   developers."), "Protocol Buffers: BSD 3-Clause License, © 2008 Google Inc.", "Public Suffix List:
   Mozilla Public License 2.0, source publicsuffix.org.", then "Full license texts:
   github.com/yarosz/light-reader, THIRD_PARTY_NOTICES.md", as text. `THIRD_PARTY_NOTICES.md` holds
   every shipped component, its license, its copyright and the full texts; a test checks it names
   every component a line here names. When a dependency comes or goes, both change with it.

## Dependencies

Reader's tool module excludes, from all its configurations, what Light's `sdk:ui` declares only for
`LightQrCodeScanner`, which Reader never shows: ML Kit (`com.google.mlkit`, `com.google.android.odml`),
CameraX (`androidx.camera`), and what ML Kit pulls in, Google Play services (`com.google.android.gms`),
Firebase components (`com.google.firebase`) and Google's Data Transport, its metrics channel
(`com.google.android.datatransport`). Nothing else in the graph depends on them, and after R8 the
release APK references none of their classes. Light's builder takes `tool/build.gradle.kts` as it is
(an exclude is not on its banned-pattern list), so the exclude holds in Light's release build too; it
takes the release APK from 27.1 MB to 5.2 MB and removes ML Kit's native libraries, its three tflite
models and its manifest entries (`MlKitInitProvider`, `MlKitComponentDiscoveryService`,
`TransportBackendDiscovery`, `JobInfoSchedulerService`). The CAMERA permission stays: `sdk:ui`'s own
manifest declares it, and a Tool can't edit its manifest. Calling `LightQrCodeScanner` would now crash,
so Reader must never call it; a Tool that needs a scanner drops the exclude.
