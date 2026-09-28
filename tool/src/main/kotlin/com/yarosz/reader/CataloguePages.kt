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
     * end; [more] is how fetching it is going.
     */
    data class Listing(val entries: List<CatalogueEntry>, val search: CatalogueSearch?, val next: HttpsUrl?, val more: More = More.Idle) : PageState

    /** One Book's detail page; [entries] are its Editions (see [bestDownload]). */
    data class Book(val entries: List<CatalogueEntry>) : PageState
}

sealed interface More {
    data object Idle : More

    data object Loading : More

    data class Failed(val reason: FeedFailure) : More
}

/**
 * How a fetched [page] shows. A feed opened from an entry is that Book's own page when all its
 * entries are one Book with a download (Gutenberg's Book pages, whose entries are the Book's
 * Editions); anything else is a list.
 */
fun pageState(page: CataloguePage, source: PageSource): PageState =
    if (source is PageSource.Feed && bestDownload(page.entries) != null) PageState.Book(page.entries)
    else PageState.Listing(page.entries, page.search, page.next)

/** [listing] with the page "More" fetched appended. */
fun appendPage(listing: PageState.Listing, page: CataloguePage): PageState.Listing =
    listing.copy(entries = listing.entries + page.entries, next = page.next, more = More.Idle)

/** Where tapping an entry of a list goes: its detail page when it has a download, else the feed it opens, else nowhere. */
fun entryTarget(entry: CatalogueEntry): PageSource? = when {
    entry.download != null -> PageSource.Entry(entry)
    entry.opens != null -> PageSource.Feed(entry.opens, entry)
    else -> null
}

/** The search field's placeholder: Standard Ebooks' search covers all of it, not only the new releases (D14.3). */
fun searchPlaceholder(catalogue: Catalogue): String =
    if (catalogue.url == STANDARD_EBOOKS_NEW_RELEASES.url) SEARCH_STANDARD_EBOOKS else SEARCH

/** A Book's author on its detail page: the first Edition that names one, else the first entry's second line. */
fun detailAuthor(entries: List<CatalogueEntry>): String? =
    entries.firstNotNullOfOrNull { entry -> entry.authors.takeIf { it.isNotEmpty() }?.joinToString(", ") } ?: entries.firstOrNull()?.byline

/**
 * A Book's summary on its detail page: the first prose summary (the parser drops metadata dumps,
 * D14.4), unless it only repeats the author, as a Gutenberg list entry's short content does.
 */
fun detailSummary(entries: List<CatalogueEntry>): String? {
    val author = detailAuthor(entries)
    return entries.firstNotNullOfOrNull { it.summary?.takeIf { summary -> summary != author } }
}
