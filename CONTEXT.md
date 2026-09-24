# Reader

The language of the Reader tool: a Light Phone III eReader for DRM-free EPUBs, built for any LP3
owner who chose the phone to read more deliberately.

## Platform

**Tool**:
An installable capability on LightOS, built with Light's SDK.
_Avoid_: app

## Books and where they come from

**Book**:
One work on the reader's Shelf. It is the same Book across re-downloads and new editions of the
same work, and it has its own Place.
_Avoid_: title, file, ebook

**Shelf**:
The Books downloaded to this phone. Always readable offline.
_Avoid_: library, collection

**Catalogue**:
A source of Books the reader can browse and download from, such as Project Gutenberg or the
reader's own home server. Any Catalogue can be removed, including the ones the Tool ships with.
_Avoid_: library, store, feed

## Structure of a Book

**Chapter**:
A division named in the Book's own table of contents. When a Book has no usable table of contents,
each Spine item counts as a Chapter.
_Avoid_: section

**Spine item**:
One of the documents a Book is made of, in reading order. A Spine item may hold several Chapters,
and one Chapter may span several Spine items.
_Avoid_: file, chapter

## Reading

**Place**:
Where the reader is in a Book: the spot in the text at the top of the Page being read. Font or
layout changes never move it. The reader never sees it as a number; the Book simply opens there.
_Avoid_: position, anchor, offset, location

**Page**:
What fits on the screen at the current font size. Recomputed whenever the layout changes; never
saved and never shown outside the reading session.
_Avoid_: screen

**Progress**:
How far the reader is through the current Chapter or Book, shown as time left (such as "12 min
left"). Derived from the Place; never stored on its own.
_Avoid_: percent read, position

**Bookmark**:
A spot the reader deliberately saves to return to. Reserved: not part of the Tool yet.
_Avoid_: place, saved position
