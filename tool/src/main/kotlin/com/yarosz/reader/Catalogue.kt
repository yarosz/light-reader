package com.yarosz.reader

import java.util.Locale

/** A Catalogue: a name and the Atom feed it starts at (ADR 0001). */
data class Catalogue(val name: String, val url: HttpsUrl)

val GUTENBERG = Catalogue("Project Gutenberg", HttpsUrl.parse("https://www.gutenberg.org/ebooks.opds/")!!)

/** Its search covers all of Standard Ebooks, not only the new releases (D14.3). */
val STANDARD_EBOOKS_NEW_RELEASES = Catalogue("Standard Ebooks: new releases", HttpsUrl.parse("https://standardebooks.org/feeds/atom/new-releases")!!)

/** The Catalogues the Tool ships with. The reader can remove either, and add it back. */
val SHIPPED_CATALOGUES = listOf(GUTENBERG, STANDARD_EBOOKS_NEW_RELEASES)

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
 * lists never show covers. [byline] is the second line of its row, null for none (see
 * [entryByline]). [opens] is the feed the entry leads to, such as a Gutenberg Book's own page.
 * [details] is the entry's complete OPDS entry document (an alternate link typed "type=entry"),
 * which is never a feed to open. [related] are further feeds named by the entry, such as
 * Gutenberg's "By Austen, Jane…".
 */
data class CatalogueEntry(
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val byline: String?,
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
 * Whether an edition with images beats one without. False until the Reader draws images (v1.x): it
 * shows an image only as its alt text, so the images edition is a larger download for the same
 * reading (Pride and Prejudice: 25 MB against 558 KB). Flip it when images ship.
 */
const val PREFER_IMAGES_EDITION = false

/**
 * The one link to download among [links]: EPUB over kepub, then the preferred edition (without images
 * or unmarked over with images, per [preferImages]), then EPUB3 over EPUB2. Feeds mark these only in
 * the link's file name and title, as Gutenberg does ("1342.epub3.images", "EPUB (older e-readers, no
 * images)"); Gutenberg offers its no-images edition only as EPUB2 ("1342.epub.noimages"). A tie keeps
 * feed order, which puts a feed's own recommendation first (Standard Ebooks lists its compatible epub
 * before the advanced one). Null when no link is an EPUB.
 */
fun bestAcquisition(links: List<Acquisition>, preferImages: Boolean = PREFER_IMAGES_EDITION): Acquisition? =
    links.filter { it.type in EPUB_TYPES }.minWithOrNull(
        compareBy<Acquisition> { EPUB_TYPES.indexOf(it.type) }
            .thenBy { it.editionRank(preferImages) }
            .thenByDescending { EPUB3.containsMatchIn(it.markers()) },
    )

/**
 * The one link "Add to Shelf" downloads for a Book's own page, whose entries are that Book's editions
 * (Gutenberg lists its no-images and images editions separately): the best of all their links. Null
 * unless every entry has the same title, because then the page is a list of different Books (Standard
 * Ebooks' new releases), and one download for all of them would add an arbitrary one. The title is
 * the test because it is what the page shows as one Book: entry ids differ per edition
 * (Gutenberg's `urn:gutenberg:1342:2` and `…:3`), so they can't tell editions from other Books.
 */
fun bestDownload(entries: List<CatalogueEntry>): Acquisition? =
    if (entries.map { it.title }.distinct().size == 1) bestAcquisition(entries.flatMap { it.acquisitions }) else null

/** 0 for the preferred edition; without [preferImages], "no images" and unmarked tie. */
private fun Acquisition.editionRank(preferImages: Boolean): Int {
    val markers = markers()
    val images = when {
        NO_IMAGES.containsMatchIn(markers) -> -1
        "images" in markers -> 1
        else -> 0
    }
    return if (preferImages) -images else maxOf(images, 0)
}

private fun Acquisition.markers() = (url.value.substringBefore('?').substringAfterLast('/') + " " + title.orEmpty()).lowercase()

/** A download size for the detail page, in decimal units: "558 KB", "24.8 MB", "300 MB". */
fun formatSize(bytes: Long): String {
    val kb = (bytes + 500) / 1000
    if (kb < 1000) return "${maxOf(kb, 1)} KB"
    val mb = bytes / 1_000_000.0
    return if (mb < 99.95) String.format(Locale.ROOT, "%.1f MB", mb) else "${(bytes + 500_000) / 1_000_000} MB"
}
