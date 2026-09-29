# Reader

The language of the Reader tool: a Light Phone III eReader for DRM-free EPUBs, built for any LP3
owner who chose the phone to read more deliberately.

## Platform

**Tool**:
An installable capability on LightOS, built with Light's SDK.
_Avoid_: app

## Books and where they come from

**Book**:
One thing the reader has added to the Shelf to read, with its own Place. It stays the same Book when it
is downloaded again from its source, even as a new Edition, and whenever another download declares the
same identifier. The same work added from elsewhere under another identifier is a separate Book.
Removing a Book from the Shelf deletes its file but keeps its Place, so adding it again opens where the
reader left off.
_Avoid_: title, file, ebook, work

**Shelf**:
The Books the reader has added to read on this phone, from a Catalogue or imported. Reading never needs
a connection once a Book's file is on the phone. A removed Book leaves the Shelf but keeps its Place.
_Avoid_: library, collection

**Catalogue**:
A list of Books the reader can browse and add from, such as Project Gutenberg or the reader's own home
server. Any Catalogue can be removed, including the ones the Tool ships with.
_Avoid_: library, store, feed, source

**Shipped Catalogue**:
A Catalogue the Tool comes with: Project Gutenberg and Standard Ebooks' new releases. It keeps its own
name, and is recognised by its address however the reader types it. Removing one is remembered, and the
reader can add it back in one tap.
_Avoid_: default catalogue, built-in, preset

**Catalogue entry**:
One thing a Catalogue lists: something the reader could add, a way further into the Catalogue, or both.
On a Book's own page, the entries can be that Book's Editions, which share one Add to Shelf. An entry is
not a Book until it is added.
_Avoid_: book (before it's added), listing, item, result

**Edition**:
One packaging of a Book's text. Gutenberg's with-images and no-images files are two Editions of one Book,
and a Standard Ebooks re-release is a new Edition. The Tool picks which Edition to download; the reader
never chooses. Editions can divide the text differently, so a Place moved to another Edition is found
again by its text.
_Avoid_: version, variant

**Source** (of a Book):
Where in a Catalogue a Book's file was last downloaded from. A Book whose file goes missing downloads
again from its source; a Book with no source (one the reader imported) can only be removed. A Catalogue
is where Books are found; a source is one Book's file.
_Avoid_: origin, URL

**Download**:
The Tool fetching a Book's file, from a Catalogue entry or from the Book's source. It belongs to the
Shelf, not to the Catalogue page that started it, and a Book it brings back keeps its Place. A Book
added from a file already on the phone is imported, not downloaded.
_Avoid_: transfer

## Structure of a Book

**Chapter**:
A division named in the Book's own table of contents, running until the next one or the end of the
Book. When the table of contents nests, only divisions with none nested under them are Chapters. When
a Book has no usable table of contents, each Spine item counts as a Chapter. Back matter always starts a
Chapter.
_Avoid_: section

**Part**:
A division of a Book that groups Chapters, such as "BOOK ONE" or "PART II". When the table of contents
nests Chapters under it, it is not a Chapter, and a Page opening on its heading goes by its first
Chapter. When the table of contents lists it beside its Chapters, it is a Chapter too. A Part is never
a Book, whatever its heading says.
_Avoid_: section, volume, book (for a Part)

**Front matter**:
The part of a Book before its first Chapter, such as a title page, dedication or epigraph the table of
contents doesn't list. It belongs to no Chapter, and a Book with no usable table of contents has none.
The Book's own marking doesn't decide it: a preface or foreword the table of contents lists is a
Chapter, even when the Book marks it front matter.
_Avoid_: prelims

**Back matter**:
The part of a Book after its text ends: from Project Gutenberg's license, or from the run of Spine
items at the Book's end that it marks as back matter, whichever comes first. The end page comes before
it. Turning forward from the text never reaches it; Contents does, and a Place already in it opens
there. A Book that marks none has no Back matter.
_Avoid_: appendix, trailer

**Spine item**:
One of the documents a Book is made of, in reading order. A Spine item may hold several Chapters,
and one Chapter may span several Spine items. Documents that aren't reading matter (a cover wrapper
marked auxiliary, a Standard Ebooks title page or imprint, a document with no text) are left out:
they are no Spine item, and no Page shows them.
_Avoid_: file, chapter

## Reading

**Place**:
Where the reader is in a Book: the spot in the text at the top of the Page being read. A Book gets its
Place when it is first opened; until then it hasn't been started. Font or layout changes never move
it, though after one it may sit a few lines down its Page, so the Page doesn't start mid-word or part a
heading from its text, and never goes by a Chapter before the Place's. Going to a Chapter from Contents
puts the Place at the top of the Page the Chapter starts on. The reader never sees it as a number; the
Book simply opens there.
_Avoid_: position, anchor, offset, location

**Page**:
What fits on the screen at the current font size. Recomputed whenever the layout changes; never
saved, and gone once the reader leaves the Book. A Catalogue's page (one fetch of its list, which
"More" extends) is not a Page. Each Page goes by one Chapter, or by none in Front matter: the one its
top is in, or a Chapter that starts later in its first line, or in or right after the headings it opens
on. The Chapter title shown while reading, the time left, and the Chapter Contents marks as current all
follow it.
_Avoid_: screen

**Progress**:
How far the reader is. While reading, it is the time left in the Chapter the Page goes by ("about 12
min left in this chapter"). On the Shelf, it is how far through the Book the Place is, as a percent
("42%").
_Avoid_: position, page number

**Contents**:
The list of a Book's Chapters, in order, opened from the reading view. Choosing one goes to the start of
that Chapter; it is the only way from the text into Back matter.
_Avoid_: table of contents (that is the Book's own list, which Contents is built from), TOC, index, chapters list

**Finished**:
A Book the reader has read to the end of its text, the last Page before any Back matter. It becomes
Finished when the end page shows, which also saves its Place on that last Page, and stays Finished when
the reader leaves. Finished Books sit at the bottom of the Shelf. Opening one again opens at its Place.
Turning back onto a Page of the text (from the end page too), or going to a Chapter of the text from
Contents, makes it in progress again; a forward turn, a font change, or reading its Back matter does not.
_Avoid_: read, done, completed

**Bookmark**:
A spot the reader deliberately saves to return to. Reserved: not part of the Tool yet.
_Avoid_: place, saved position
