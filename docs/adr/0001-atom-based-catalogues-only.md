# Atom-based Catalogues only

Every Catalogue is an Atom feed: OPDS, or plain Atom whose entries carry EPUB enclosure links (Standard
Ebooks' public new-releases feed). One parser accepts both link forms (OPDS acquisition rels and
`rel="enclosure"` with `type="application/epub+zip"`). We don't scrape HTML or use proprietary store APIs.
One protocol serves all three audiences: self-hosters (Calibre's content server, calibre-web, Kavita),
parents curating a private shelf, and public-domain libraries. "Add a Catalogue" is the whole source UI.

Shipped defaults: Project Gutenberg (OPDS) and "Standard Ebooks: new releases" (public Atom, a rolling 15).
Standard Ebooks' full OPDS catalogue is patron-only (HTTP 401); patrons add it with their login.

## Considered Options

- Scraping Standard Ebooks' HTML: fragile, and discourteous to a volunteer project.
- A bespoke source per site: more code paths, no benefit over Atom.
