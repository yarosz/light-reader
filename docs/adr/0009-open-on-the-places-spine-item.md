# Open on the Place's Spine item; the whole Book behind it

Opening a Book parses only what its first Page needs: the package, the tables of contents and the Spine item
the Place is in (`EpubOpening.placed`; a never-opened Book takes the first Spine item with text that no type
marks as not reading matter). The first Page lays out from a Book of that one Spine item. The rest of the Book
is parsed behind the Page in one background pass that reuses what was parsed, giving exactly the Book a whole
parse gives (a test pins the equality), then its word index. One swap on the main thread then puts the whole
Book in: the Place and the Page on screen move to the Spine item's index in the whole Book without laying
anything out again (`Reading.rebase`). The running head and the Progress line are published afresh at the swap.

This is how a printed book with a bookmark works: the page is there at once, and the contents page and the rest
of the chapter are there when you turn to them. The Reader never shows a number it would have to correct.
Anything it can't know yet is blank, or waits:

- Turns within the Spine item and font changes work as ever. A turn past its first or last Page waits, and every
  turn after it queues behind it, so taps keep their order.
- The end page never shows early: a forward turn onto it waits too.
- The Progress line is blank, as in Front matter. Contents waits silently and opens once the Book is in. A second
  tap adds nothing, and the wait is dropped if the reader has left the Reader.
- Place writes wait for the swap, because `progress` needs every Spine item's length. The Shelf's title and author
  are written at once. A kill in the first moment loses a never-opened Book's first Place.
- A later Spine item that won't parse takes the Page away for "Couldn't open this Book.". That is the outcome a
  whole parse gave, only later.

Spine item indices are the structural reason for one swap. A Spine item is kept only if it has text, and a Book
that marks its body matter drops its title page, imprint and table of contents. So the index of the Place's
Spine item is final only once every Spine item is parsed. Back matter, the end page, Contents, Progress and
Shelf percent all hang off those indices or off every Spine item's text. With one swap, whole-Book data becomes
valid at one moment, and every rule that depends on indices stays as it was.

The bar this sets, which narrows ADR 0007's "it doesn't cover the EPUB parse": **the drawn open (from the open's
start to the first draw of its Page, `scripts/perf.sh`'s `drawn open`) in at most 300 ms P90 on the SM4450,
warm, release, for any Book whose Place's Spine item is at most 200 K characters.** ADR 0007's firstPageMs bar
still holds within it.

## Measurements

Where an eager open's time went, on the host JVM and the emulator alike: parsing Spine items was 80–90% of the
open, and the word index another 10–13%. The zip's directory, the package, resolving the Place, the table of
contents (save one of 1,111 entries, ~60 ms on the emulator) and every whole-Book rule after the parse took a few
ms together. Parsing only the Place's Spine item removes almost all of it from the path to the first Page.

LP3 (TLP301), release, speed-profile compiled, n = 10. Drawn open, P90, before (a whole parse first) and after:

| Book | Before | After |
|---|---|---|
| Alice's Adventures in Wonderland | 151 ms | 111 ms |
| War and Peace | 692 ms | 114 ms |
| King James Bible | 730 ms | 113 ms |
| Lady Chatterley's Lover (one 638 K-character Spine item) | 218 ms | 192 ms |
| A 1.43 M-character single-item EPUB (a 1,111-entry table of contents) | 626 ms | 360 ms |

On the long Books the whole Book is in ~0.65–0.7 s after the open starts, which is how long Contents, the Progress
line and a turn off the Spine item can wait (`loadedMs` in the `book` line). Turns, font changes and syncWindows
(the windows a turn measures on the main thread: none) are unchanged. A Book of one huge Spine item gains little, as its
first Page still waits for that item's whole parse; the last row is outside the bar for that reason.

## Rules that keep it exact

- The swap keeps a cached pass only when the whole Book lays it out the same way: the same page break (a text
  end at the Spine item's end breaks no Page) and, for a pass packed from the Place, the same `pageFloor`.
  Otherwise the Page lays out afresh at the Place. A Chapter the Spine item alone didn't list starting above the
  Place can cause that, as can a whole table of contents that isn't usable (one entry, or out of order). So the
  Page after the swap is always the one a whole parse would have shown.
- When the whole Book drops the Place's Spine item (a Place saved on a title page a newer parse drops), the Page
  lays out at the Book's start, as a whole parse opens such a Place.
- When the open found no Place, and no turn has moved off it, the swap goes to the whole Book's start. A Book that
  marks no body matter keeps a first document typed as a title page, which the open skipped, as only the whole
  Book can tell whether its body matter is marked.
- The one-item Book's Chapters are the entries its table of contents lists in that Spine item, even one, and none
  when it lists none. Until the swap the running head can therefore differ from the whole Book's in three cases
  only. A Page before the Spine item's first listed Chapter shows the Book's title, as in Front matter, where the
  whole Book names a Chapter running on from an earlier Spine item. A Chapter with no title and no heading counts
  its "Chapter N" from that Spine item. A Book whose whole table of contents isn't usable names a listed Chapter
  there. The controls are hidden at open, so the reader sees this only by showing them in that first moment, and
  the swap publishes the whole Book's running head.
- A dev-start session names its Spine item by its index in the whole Book, which only a whole parse knows. With
  the Spine item's id too, it opens lazily. `scripts/perf.sh` reads the id from an eager probe open, so its timed
  opens are lazy.

## Considered Options

What loads before the first Page:

- The package, tables of contents and the Place's Spine item (chosen). Only that Spine item's parse stays on the
  path to the first Page.
- The same plus both neighbouring Spine items, so an early turn across a Spine item's edge needn't wait. It puts
  two more parses on the path for a case that is rare: the whole Book is in within ~0.7 s, and a Page takes
  10–15 s to read.
- Only a prefix of a huge Spine item: stop parsing after the Place's block and a window. This would help the
  one-item Books. A pass would then need a Spine item whose length grows, and `Window`, `pack` and the text end
  all assume a fixed one. Deferred until the one-item Books matter.

How the rest fills in:

- One background pass and one swap (chosen).
- Progressively, neighbours first, publishing each Spine item as it lands. It helps only if the rest took
  seconds. It needs indices that hold while Spine items are still being kept or dropped, so Pass, Reading,
  `chapterAt` and `chaptersOf` would have to handle a partly known Spine.

A turn into a Spine item not yet parsed:

- Wait for the whole Book (chosen).
- Parse the neighbour synchronously on the main thread: 10–20 ms for a typical Spine item, but over 100 ms for a
  large one, and its index still isn't final.
- Prefetch the neighbours first (the second option above).
