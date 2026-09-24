# A Place is a locator plus a text snippet; a Book is its dc:identifier

A Place is stored as (Spine item id, block index, character offset within the block) plus the ~40
characters of text at the top of the Page. A Book is identified by its OPF `dc:identifier`, never by file
name, so a re-download or re-import keeps its Place. The persisted format carries a `schemaVersion`.

Two invariants make this worth an ADR:

1. **A Place is never rewritten by relayout.** Font and layout changes look the Place up; they don't
   replace it. That is why a font round trip lands on the identical Page.
2. **The snippet exists to re-find a Place** after a parser change or a new edition shifts offsets
   (Standard Ebooks re-releases editions often).

## Considered Options

- A bare character offset into the chapter text: breaks silently when parsing changes.
- Full EPUB CFI: more than this Tool needs.
- A page number: meaningless across fonts, screens, and devices.
