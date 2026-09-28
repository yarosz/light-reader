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

## Copy

Copy capitalises Book, Shelf and Catalogue; "place" is lowercase; chapter is lowercase in running copy
and capitalised only in a "Chapter N" title; Edition, Spine item and Place-as-a-term never appear in
copy.

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
| Failed for good, downloading again from the Shelf | "can't download again · copy-protected", "· not an EPUB" or "· needs https" | nothing; the Book can only be removed |
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
failures (Unreachable, HttpError, DiskError, and UntrustedCertificate, D15) leave a row reading
"download failed · tap to retry". An untrusted certificate is retryable because public Wi-Fi
intercepts TLS until the reader signs in to it. Permanent failures (CopyProtected, NotAnEpub, and
also NoHttps, which a retry can't fix) of a download from a Catalogue show their copy on the Book's
detail page and add nothing to the Shelf. A copy-protected Book must never become a row that can't be read. When
downloading a missing file again from the Shelf fails for good, the Book's row says why ("can't
download again · …", table above) and can then only be removed. That state lives in memory: after a
relaunch the row reads "file missing · tap to download again" again, and a tap tries once more.

**Offline.** Nothing changes on the Shelf, because everything there works offline: no rows are
removed and nothing is greyed out. "You're offline. Your Shelf still works." is one line of
secondary text at the top of the Catalogue list, shown when Add is tapped offline (see
"Catalogues").

**Missing file.** A Book whose file is gone reads "file missing · tap to download again", and the tap
downloads it again from the Book's source, keeping its Place. If the download declares a different
`dc:identifier` (Calibre mints a new one on every conversion), the Book keeps its row: its Place,
date added and title move to the new identifier, and the Place resolves as well as the new edition
allows. If a Book with the new identifier is already on the Shelf, the two rows become that one: it
keeps its own title and date added, takes whichever Place is newer, and the old row leaves the
Shelf. A Book with no known source (a future
N7 import) reads "file missing" and can only be removed.

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
the list. The Tool asks the phone each time the list shows (the SDK's `LightConnectivity`, which
needs the normal `ACCESS_NETWORK_STATE` permission): offline means no network that can reach the
internet. When the phone can't say, the line is not shown, so it never claims more than the phone
did. The last fetch's result isn't used: one unreachable server doesn't mean the phone is offline.

**Removing.** In Edit every row's trailing edge reads "Remove", in secondary text. Tapping it turns
the row, inline, into the Catalogue's name, then "Remove this Catalogue? Books you added from it stay
on your Shelf.", then "Remove" and "Cancel". Any Catalogue can be removed, the shipped ones too.
Removing the last one leaves Edit.

**Add a Catalogue.** Title "Add a Catalogue", then a field labelled "Catalogue address" with the
placeholder "https://…", then "Add". Tapping the field opens the SDK's text editor with the LP3
keyboard, whose button is also "Add". An address with no scheme is taken as https://; http:// is
tried once as https://, and a server with no HTTPS reads as NoHttps. The feed is fetched before
anything is saved: a page that isn't a Catalogue feed shows Unreadable's copy and adds nothing. The
name is the feed's title, else its host. An address already on the list reads "This Catalogue is
already in your list." A failure that trying again can't fix hides "Add" until the address changes;
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

| Match | Action | Beside it |
|---|---|---|
| None | "Add to Shelf" | the size |
| On the Shelf with its file | "Read", which opens the Book at its Place | "On your Shelf" |
| On the Shelf, file missing | "Download again", keeping the Book's row and Place | the size |
| Removed | "Add to Shelf", keeping the Place | the size |
| A download running | "downloading…" in secondary text | |

The page stays while a download runs and follows it: "downloading…", then "Read" when it lands.
The download belongs to the Shelf, so leaving the page doesn't stop it, and the Shelf shows it as a
row meanwhile. A failure shows its copy above the action: a retryable one turns the action into
"Retry", and a permanent one (CopyProtected, NotAnEpub, NoHttps) removes it and adds nothing to the
Shelf. CopyProtected will point to About's list of places to find DRM-free Books, which arrives with
N5; until then it shows its one line.

**Failure copy.** One plain line, with "Retry" wherever retrying can help and never a dead button.

| Failure | Copy | Retry |
|---|---|---|
| Unreachable | "Can't reach this Catalogue. Check your connection and try again." | yes |
| NoHttps | "This Catalogue needs an https:// address." | no |
| HttpError | "This Catalogue isn't responding properly. Try again later." | yes |
| HttpError 401 | "This Catalogue needs a username and password. Sign-in isn't supported yet." | no |
| Unreadable | "This address isn't a Catalogue the Reader can open." | no |
| UntrustedCertificate, shipped Catalogue | "This connection isn't trusted. If you're on public Wi-Fi, sign in to it, then try again." | yes |
| UntrustedCertificate, the reader's Catalogue | the same, then "A self-hosted Catalogue needs a public certificate." | yes |
| NotAnEpub | "This file isn't an EPUB the Reader can open." | no |
| CopyProtected | "This Book is copy-protected and can't be opened here." | no |
| DiskError | "There isn't enough space on your phone to add this Book." | yes |
