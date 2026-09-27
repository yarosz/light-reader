package com.yarosz.reader

/** A source of Books: a name and the Atom feed it starts at (ADR 0001). */
data class Catalogue(val name: String, val url: HttpsUrl)

/** The Catalogues the Tool ships with. The reader can remove either. */
val SHIPPED_CATALOGUES = listOf(
    Catalogue("Project Gutenberg", HttpsUrl.parse("https://www.gutenberg.org/ebooks.opds/")!!),
    Catalogue("Standard Ebooks: new releases", HttpsUrl.parse("https://standardebooks.org/feeds/atom/new-releases")!!),
)

/**
 * One fetched page of a Catalogue. [next] is the following page (rel="next"). [search] is how to
 * search the Catalogue; null means it has no search.
 */
data class CataloguePage(
    val title: String,
    val entries: List<CatalogueEntry>,
    val next: HttpsUrl?,
    val search: CatalogueSearch?,
)

/** How a feed offers search. */
sealed interface CatalogueSearch {
    /** An OpenSearch description, to resolve with [fetchSearch] (OPDS, Standard Ebooks, Gutenberg). */
    data class Description(val url: HttpsUrl) : CatalogueSearch

    /** A template the feed gives directly, ready to fill (Calibre's content server). */
    data class Ready(val template: SearchTemplate) : CatalogueSearch
}

/**
 * One entry of a page: a Book to add, a way further into the Catalogue, or both. Text only, because
 * lists never show covers. [opens] is the feed the entry leads to, such as a Gutenberg Book's own
 * page. [details] is the entry's complete OPDS entry document (an alternate link typed
 * "type=entry"), which is never a feed to open. [related] are further feeds named by the entry, such
 * as Gutenberg's "By Austen, Jane…".
 */
data class CatalogueEntry(
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val opens: HttpsUrl?,
    val details: HttpsUrl?,
    val related: List<NavigationLink>,
    val acquisitions: List<Acquisition>,
) {
    /** The link "Add to Shelf" downloads, or null when the entry has no EPUB. */
    val download: Acquisition? get() = bestAcquisition(acquisitions)
}

data class NavigationLink(val title: String, val url: HttpsUrl)

/** A free download of the entry. [type] is the media type without parameters; [length] is in bytes. */
data class Acquisition(val url: HttpsUrl, val type: String, val title: String?, val length: Long?)

/** EPUB media types the Tool opens, most preferred first. Kobo's kepub is an ordinary EPUB with extra spans (ADR 0005). */
private val EPUB_TYPES = listOf("application/epub+zip", "application/kepub+zip")

private val EPUB3 = Regex("epub ?3")
private val NO_IMAGES = Regex("no[ -]?images")

/**
 * The one link to download among [links]: EPUB over kepub, then EPUB3 over EPUB2, then with images
 * over unmarked over without. Feeds mark these only in the link's file name and title, as Gutenberg
 * does ("1342.epub3.images", "EPUB (older e-readers, no images)"). A tie keeps feed order, which
 * puts a feed's own recommendation first (Standard Ebooks lists its compatible epub before the
 * advanced one). Null when no link is an EPUB.
 */
fun bestAcquisition(links: List<Acquisition>): Acquisition? =
    links.filter { it.type in EPUB_TYPES }.minWithOrNull(
        compareBy<Acquisition> { EPUB_TYPES.indexOf(it.type) }
            .thenByDescending { EPUB3.containsMatchIn(it.markers()) }
            .thenByDescending { it.markers().let { m -> if (NO_IMAGES.containsMatchIn(m)) 0 else if ("images" in m) 2 else 1 } },
    )

private fun Acquisition.markers() = (url.value.substringBefore('?').substringAfterLast('/') + " " + title.orEmpty()).lowercase()
