# Spine items for layout, Chapters for navigation and Progress

The Tool keeps two units. The Spine item is the layout and pagination unit: laid out once, and each Page
is drawn as a clipped band of that layout. The Chapter (a table-of-contents entry pointing at a Spine item
and fragment) is the unit for navigation, labels ("Chapter 3 of 12"), and Progress. "Minutes left in this
Chapter" spans Spine items when a Chapter is split across them. Tables of contents come from EPUB 3
`nav.xhtml` or EPUB 2 NCX. When the TOC is missing, has one entry, or is out of spine order, each Spine item
counts as a Chapter. Nested TOCs resolve to the nearest leaf.

Measured: Gutenberg EPUBs often pack ~7 Chapters into one 150–190 KB Spine item (Pride and Prejudice: 61
Chapters in 9 items), so the two units really do diverge.

If opening a large Spine item misses the performance bar (300 ms P90 on LP3 hardware for open, Chapter
jump, and font change, at 44 sp with hyphenation), windowed layout is a new ADR, not an edit to this one.
