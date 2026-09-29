package com.yarosz.reader

/** What a Catalogue page shows. */
sealed interface PageSource {
    /** The Catalogue's own feed. */
    data object Root : PageSource

    /** A feed an entry opens, titled after [opener], the entry that was tapped. */
    data class Feed(val url: HttpsUrl, val opener: CatalogueEntry) : PageSource

    /** The results of searching for [terms]. */
    data class Search(val search: CatalogueSearch, val terms: String) : PageSource

    /** One entry with a download of its own (a Standard Ebooks release): its detail page, with nothing to fetch. */
    data class Entry(val entry: CatalogueEntry) : PageSource
}

/** The top bar's title for [source] in [catalogue]. */
fun pageTitle(catalogue: Catalogue, source: PageSource): String = when (source) {
    PageSource.Root -> catalogue.name
    is PageSource.Feed -> source.opener.title
    is PageSource.Search -> source.terms
    is PageSource.Entry -> source.entry.title
}

sealed interface PageState {
    data object Loading : PageState

    data class Failed(val reason: FeedFailure) : PageState

    /**
     * A list of entries, as far as it has been paged. [next] is the page "More" fetches, null at the
     * end; [more] is how fetching it is going. [fetched] are the pages this list was made from, so a
     * feed whose rel=next leads back to one of them ends instead of looping.
     */
    data class Listing(
        val entries: List<CatalogueEntry>,
        val search: CatalogueSearch?,
        val next: HttpsUrl?,
        val fetched: Set<HttpsUrl>,
        val more: More = More.Idle,
    ) : PageState

    /** One Book's detail page; [entries] are its Editions (see [bestDownload]). */
    data class Book(val entries: List<CatalogueEntry>) : PageState
}

sealed interface More {
    data object Idle : More

    data object Loading : More

    data class Failed(val reason: FeedFailure) : More
}

/**
 * The most entries one list holds. A list composes every row at once (LightScrollView is a scrolling
 * column, not a lazy list), so this bounds the work and memory one list can cost the phone, whatever
 * a feed sends. It is 20 of Gutenberg's 25-entry pages, far more than anyone pages through: past it,
 * search finds a Book faster than "More".
 */
const val MAX_LIST_ENTRIES = 500

/**
 * How [page], fetched from [url], shows. A feed opened from an entry is that Book's own page when all
 * its entries are one Book with a download (Gutenberg's Book pages, whose entries are the Book's
 * Editions); anything else is a list.
 */
fun pageState(page: CataloguePage, source: PageSource, url: HttpsUrl): PageState =
    if (source is PageSource.Feed && bestDownload(page.entries) != null) PageState.Book(page.entries)
    else listing(emptyList(), page, emptySet(), url, page.search)

/** [listing] with [page], which "More" fetched from [url], appended. */
fun appendPage(listing: PageState.Listing, page: CataloguePage, url: HttpsUrl): PageState.Listing =
    listing(listing.entries, page, listing.fetched, url, listing.search)

/**
 * A list of [entries] then [page]'s, up to [MAX_LIST_ENTRIES]. "More" ends at the cap, and when the
 * page's rel=next is a page already fetched for this list, [url] included.
 */
private fun listing(entries: List<CatalogueEntry>, page: CataloguePage, fetched: Set<HttpsUrl>, url: HttpsUrl, search: CatalogueSearch?): PageState.Listing {
    val all = (entries + page.entries).take(MAX_LIST_ENTRIES)
    val seen = fetched + url
    val next = page.next.takeUnless { it in seen || all.size >= MAX_LIST_ENTRIES }
    return PageState.Listing(all, search, next, seen)
}

/** Where tapping an entry of a list goes: its detail page when it has a download, else the feed it opens, else nowhere. */
fun entryTarget(entry: CatalogueEntry): PageSource? = when {
    entry.download != null -> PageSource.Entry(entry)
    entry.opens != null -> PageSource.Feed(entry.opens, entry)
    else -> null
}

/** The search field's placeholder: Standard Ebooks' search covers all of it, not only the new releases (D14.3). */
fun searchPlaceholder(catalogue: Catalogue): String =
    if (catalogue.url.catalogueKey == STANDARD_EBOOKS_NEW_RELEASES.url.catalogueKey) SEARCH_STANDARD_EBOOKS else SEARCH

/** The byline of the row the reader tapped to open this page, which its detail page repeats; null for none. */
val PageSource.byline: String?
    get() = when (this) {
        is PageSource.Feed -> opener.byline
        is PageSource.Entry -> entry.byline
        PageSource.Root, is PageSource.Search -> null
    }

/**
 * A Book's author on its detail page: [listed], the byline of the row that opened it, so a list and
 * the detail page name the author alike (Gutenberg's lists say "graf Leo Tolstoy" where its Book
 * pages say "Tolstoy, Leo, graf"); else the first Edition that names one; else the first entry's
 * second line.
 */
fun detailAuthor(entries: List<CatalogueEntry>, listed: String? = null): String? =
    listed ?: entries.firstNotNullOfOrNull { entry -> entry.authors.takeIf { it.isNotEmpty() }?.joinToString(", ") } ?: entries.firstOrNull()?.byline

/**
 * A Book's summary on its detail page: the first prose summary (the parser drops metadata dumps,
 * D14.4), unless it only repeats the author, as a Gutenberg list entry's short content does.
 */
fun detailSummary(entries: List<CatalogueEntry>, listed: String? = null): String? {
    val author = detailAuthor(entries, listed)
    return entries.firstNotNullOfOrNull { it.summary?.takeIf { summary -> summary != author } }
}

/**
 * "Read" on a Book's detail page: the Catalogue's pages and list hand [file] back down to the Shelf, which
 * opens the Reader over itself, so leaving the Reader, by system back, "Shelf" or "Back to Shelf", lands on
 * the Shelf. Each screen passes it on as its result arrives, so no frame of the screens between shows. That
 * relies on every Catalogue screen being opened with `::goBack` as its result callback (a missing one stops
 * the Book on that screen), and on their view models not overriding onBackPressed.
 */
data class OpenFromShelf(val file: String)
