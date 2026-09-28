# A Place is a locator plus a text snippet; a Book is its dc:identifier

A Place is stored as (Spine item id, block index, character offset within the block) plus the ~40
characters of text at the top of the Page. A Book is identified by its OPF `dc:identifier`, never by file
name, so a re-download or re-import keeps its Place. The persisted format carries a `schemaVersion`.

Two refinements to identity (added in N3):

- A package with no `dc:identifier` is identified by a hash of its Spine (each Spine item's CRC-32 and
  length, from the zip's directory), so it stays one Book across re-downloads of the same text.
- A download from a Book's own source that declares a new identifier (Calibre mints one on every
  conversion) carries the Book, Place included, to that identifier. When a Book with that identifier is
  already on the Shelf, the two merge and the newer Place wins. The same work added from elsewhere under
  another identifier stays a separate Book.

A later schema only adds fields. It never changes the type or meaning of a field an earlier schema
knows. A build that meets a higher `schemaVersion` reads the fields it knows, carries every other field
back verbatim, and never lowers the version, so rolling back a release (`RELEASING.md`) or importing a
file from a newer build (N7) loses nothing. The one exception: turning a Page records a fresh Place, so
fields a newer build added inside the old Place are dropped with it, because they described that Place.
A change that needs a new type or meaning takes a new field name.

Two invariants make this worth an ADR:

1. **A Place is never rewritten by relayout.** Font and layout changes look the Place up; they don't
   replace it. That is why a font round trip lands on the identical Page.
2. **The snippet exists to re-find a Place** after a parser change or a new Edition shifts offsets
   (Standard Ebooks re-releases Editions often).

## Considered Options

- A bare character offset into the Spine item's text: breaks silently when parsing changes.
- Full EPUB CFI: more than this Tool needs.
- A page number: meaningless across fonts, screens, and devices.
