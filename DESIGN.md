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
of these live in `Typesetting.kt`. No footer while reading stays N5's target, when the chrome becomes an
overlay; until then the reading view has a top line and a 48 dp footer (see "Reading").

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
the start of the Place's Chapter, so the Page can't open in the Chapter before and go by it: when the
Place's line holds the Chapter's start, that line starts the Page. A heading, or run of headings, right
before a Chapter's start with no text between counts as the Chapter's (a table of contents may point at
the paragraph after it), so the walk still takes it and the heading stays with its text. The Place
itself doesn't move, so repeated font changes can't drift it. A Chapter jump is exempt: its Page starts
on the Chapter start's own line (Contents).

## Paragraph indent

A paragraph gets a first-line indent only when it follows another paragraph (Standard Ebooks' p + p
convention); the first paragraph after a heading, caption or verse, or a Spine item's first block, starts
flush, judged on block kinds, never on window boundaries.

## Copy

Copy capitalises Book, Shelf and Catalogue; "place" is lowercase; chapter is lowercase in running copy
and capitalised only in a "Chapter N" title; Edition, Spine item and Place-as-a-term never appear in
copy.

## Reading

The reading view until N5 moves its chrome into an overlay. Product rulings from the advisor (N4,
2026-09-29); copy is verbatim, and `Progress.kt` holds it. A Book that can't be opened reads "Couldn't
open this Book." (the reason goes to the log), and one with no text reads "This Book has no text." Under
either is "Back to Shelf", as on the end page. System back (the LP3's back gesture) leaves the Reader
from anywhere, to the Shelf, which the Reader always opens over (see "Catalogues"), but a Tool can't show
it, so every Reader state but the brief "Opening…" has a visible way to the Shelf: "Back to Shelf" where
there is no Page, and on a Page the top line, then "Shelf" in Contents.

**Layout.** A top line, the Page, then the footer. The top line is the title of the Chapter the Page
goes by, verbatim, in the SDK's Detail size and secondary text, on one line, ellipsised at the end and
centred. A Page goes by its start, or, when a Chapter starts later in its first line (a table of contents
may point mid-line), by the last Chapter starting there, so a jump names the Chapter chosen. A Page
that opens on headings goes by its first line under them instead, so a Page opening on a Chapter's
heading, with the Chapter's start on the next line, goes by that Chapter. It is one Detail line high:
the style's line height at the system font scale, so the large-text setting grows it and nothing clips,
but no title changes it (a script drawn in a fallback font with a taller line would otherwise re-pack
the Pages at a Chapter change). It has 4 dp below it.
In front matter, and on the end page, it shows the Book's title as the Shelf shows it. Tapping it opens
Contents; to a screen reader it is a button, "Contents: " and the title. Its tap target is the full
width of the screen's top 48 dp, or the top line's full height when that is taller, so it takes the top
of the Page; the rest of the Page turns Pages. The footer is 48 dp:
"A−" and "A+" at its ends, each with 8 dp of padding at the sides, each filling the footer's height as
its tap target, and the Progress line between them.
The Page takes the height that is left, so Pages re-pack at the Place, which a layout change never moves.

**Progress line.** The minutes left in the Chapter: the words from the point the Page goes by to the Chapter's
end (the next Chapter's start, or the end of the Book's text if that comes first), divided by the
reading speed. A word is a whitespace-separated run of text. The words are indexed once, off the main
thread, when the Book opens, so a turn counts them without reading any text. On the raw minutes m:

| m | Line |
|---|---|
| under 1 | "almost done with this chapter" |
| 1 to under 15 | "about ⌈m⌉ min left in this chapter" |
| 15 and over | "about ⌈m/5⌉×5 min left in this chapter" |

"chapter" stays lowercase (see "Copy"). There is no line in front matter, on the end page, or in a
Chapter whose whole text reads in under a minute at the current speed.

**Reading speed.** 230 words a minute until there are 5 samples, then the median of the newest 20 or
fewer. A sample is the words on a Page divided by the time on it. It counts only when the Page was
reached by a forward turn of one Page and left by one, holds at least 20 words, and was on screen for
2 s to 3 min. A back turn, a font change or relayout, opening Contents, a Chapter jump, reopening the
Book, or the Tool pausing drops the running timing. Samples belong to the reader: they are shared
across Books, kept in memory for as long as the Tool runs, and never saved.

**End page.** A forward turn (a tap outside the left third and below the top line's target, or volume
down) from the Page that reaches the end of the Book's text shows the end page: "The end." centred, and under it "Back to Shelf", which
leaves the Reader as system back does. The top line shows the Book's title, and the footer keeps "A−"
and "A+" with nothing between them. Forward does nothing there; back (a tap in the left third, or volume
up) returns to the last Page. The end page is not a Page, so the Place stays on the last Page. The
last Page of the text ends where Back matter starts, however short that leaves it, so Back matter never
shares a Page with the text.

**Back matter.** Reached only through Contents, never by turning past the end page. Inside it, turns work
as anywhere else: the top line shows its Chapter's title, the footer has no Progress line, forward on the
Book's last Page does nothing, and no second end page shows. A Place saved there opens there.

**Contents.** Tapping the top line opens it, from a Page, the end page, front matter or Back matter, and
drops the running timing even when back then returns without a jump. It is a screen titled "Contents"
with the bar's back on the left and "Shelf" on the right, and one row per Chapter in order, each the
Chapter's title verbatim at full strength; Back matter's rows follow the text's with no divider. The
current row alone has the detail line "you're here": the Chapter the Page goes by, as the top line
names it (of two starting at one point, the later), or on the end page the last Chapter of the text.
In front matter no row is current. The list opens with the row before the current one at the top, so the current row is second, or
with the current row at the top when it is first; with no current row it opens at the top. Tapping a row
goes to the Page that starts at that Chapter's start, laid out afresh from there so its heading tops the
Page, even for the current Chapter, and even when that line starts mid-word (a Chapter anchored inside a
paragraph): a jump never moves the start up to a whole word as a font change does. The Pages before it
may tile differently, as after a font change. That Page is the Place, it is untimed, and the jump leaves
the end page. Back, from the bar or the system, changes nothing else. "Shelf" leaves the Reader as system back does, straight to the Shelf with
no frame of the reading view, and keeps the Place and Finished as they were. For half a second after
Contents opens, "Shelf" does nothing, so a double tap on the top line stays in Contents. The volume keys
stay LightOS's on this screen.

**Finished.** Showing the end page sets Finished, and the back turn from it clears it; leaving it
either way keeps it. Setting or clearing it re-stamps the Place, the same Place with a newer time, so
a merge with an older copy of the file keeps the change. A Finished Book opens at its Place, the last
Page of the text. The first back turn clears Finished, and a forward turn shows the end page again. A
Contents jump to a Chapter of the text clears it too, even to the last Chapter, whose first Page it
lands on; a jump into Back matter keeps Finished and never sets it. A back turn inside Back matter keeps
Finished; the one onto the last Page of the text clears it. A font change keeps Finished, and so does
reopening the Book and leaving at once. At
another font size a Finished Book's Place may land before the last Page, and forward turns reach the
end page without clearing it.

**Stored.** Every Place write stores `progress` (additive, ADR 0002): the share (0–1) of the Book's
text characters before the Place, front and back matter included, to 4 decimals. The Shelf's percent
comes from it (see "Shelf"). The minutes left in a Chapter are never stored.

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
| Failed for good, the source needs a login (401) | "can't download again · needs a login" | nothing; the Book can only be removed |
| Finished | "author · finished" (see below) | opens the Book |
| File missing, source known | "file missing · tap to download again" | downloads again, keeps the Place |
| File missing, no source | "file missing" | nothing; the Book can only be removed |

The percent is the Place's `progress`, floored. Under 1%, or with no `progress` (a Place saved before
N4, until the next page turn), the author stands alone. When there is no author, the state stands
alone ("42%", "finished"), so an in-progress Book with neither has no second line. A running download
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
failures (Unreachable, HttpError other than 401, DiskError, and UntrustedCertificate, D15) leave a
row reading "download failed · tap to retry". An untrusted certificate is retryable because public
Wi-Fi intercepts TLS until the reader signs in to it. Permanent failures (CopyProtected, NotAnEpub,
and also NoHttps and a 401, which a retry can't fix, since sign-in doesn't exist) of a download from a
Catalogue show their copy on the Book's
detail page and add nothing to the Shelf. A copy-protected Book must never become a row that can't be read. When
downloading a missing file again from the Shelf fails for good, the Book's row says why ("can't
download again · …", table above) and can then only be removed. That state lives in memory: after a
relaunch the row reads "file missing · tap to download again" again, and a tap tries once more.

**Offline.** Nothing changes on the Shelf, because everything there works offline: no rows are
removed and nothing is greyed out. "You're offline. Your Shelf still works." is one line of
secondary text at the top of the Catalogue list, shown while the phone reports no internet
connection (see "Catalogues").

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
placeholder "https://…", then "Add". Tapping the field opens the SDK's text editor with the LP3
keyboard, whose button is also "Add". An address with no scheme is taken as https://; http:// is
tried once as https://, and a server with no HTTPS reads as NoHttps: a refused connection or a
failed handshake. A connect timeout stays Unreachable (see the note under the failure copy). The
Catalogue is stored as https://, so once added it is never "tried as https" again: a later refused
connection is Unreachable, with Retry. The feed is fetched before
anything is saved: a page that isn't a Catalogue feed shows Unreadable's copy and adds nothing. The
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

| Match | Action | Beside it |
|---|---|---|
| None | "Add to Shelf" | the size |
| On the Shelf with its file | "Read", which opens the Book at its Place | "On your Shelf" |
| On the Shelf, file missing | "Download again", keeping the Book's row and Place | the size |
| Removed | "Add to Shelf", keeping the Place | the size |
| A download running | "downloading…" in secondary text | |

"Read" closes the Catalogue's pages and "Add a Book", and opens the Reader over the Shelf with no frame
of them between, so leaving the Reader (system back, "Shelf" in Contents, "Back to Shelf") always lands
on the Shelf. Where the reader was in the Catalogue (a search, say) isn't kept: "Add" opens the list
afresh. The page stays while a download runs and follows it: "downloading…", then "Read" when it lands.
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

A timeout on a typed http:// address is NoHttps only on a validated network: a server that drops
connections to port 443 times out, but so does a network with no internet. The SDK's
`LightConnectivity` reports only whether the network claims internet, not whether Android validated
it, and asking Android directly needs a Context, which a Light Tool can't hold. So the Tool can't
tell, and every connect timeout reads as Unreachable, with Retry.
