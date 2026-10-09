package com.yarosz.reader

/** What the Shelf knows: the reading data, which Books' files exist, the downloads by source, and the import notices. */
data class ShelfSnapshot(
    val data: ReadingData,
    val present: Set<String>,
    val downloads: Map<HttpsUrl, Download>,
    val notices: List<ImportNotice> = emptyList(),
) {
    /** The Shelf's rows (see [shelfRows]), made once per snapshot, when first read. */
    val rows: List<ShelfRow> by lazy { shelfRows(data, present, downloads) }
}

/** A stored Book whose source is one of a detail page's links. */
sealed interface ShelfMatch {
    val identifier: String

    /** On the Shelf with its file ([file], a name inside filesDir). */
    data class Here(override val identifier: String, val file: String) : ShelfMatch

    /** On the Shelf, but its file is gone. */
    data class Missing(override val identifier: String) : ShelfMatch

    /** Removed from the Shelf; its Place is kept. */
    data class Removed(override val identifier: String) : ShelfMatch
}

/**
 * The stored Book whose source is one of [links], or null. Matched by source URL only, never by
 * title: an entry's id isn't the Book's `dc:identifier`, and a miss is harmless because a download
 * that lands merges into the Book by identifier. When several match, a Book here beats one whose
 * file is missing, which beats a removed one; then the most recently added wins.
 */
fun ShelfSnapshot.match(links: Collection<HttpsUrl>): ShelfMatch? {
    val sources = links.map { it.value }.toSet()
    return data.books.entries.filter { it.value.source in sources }.map { (identifier, book) ->
        val file = book.file?.takeIf { it in present }
        when {
            book.onShelf && file != null -> ShelfMatch.Here(identifier, file)
            book.onShelf -> ShelfMatch.Missing(identifier)
            else -> ShelfMatch.Removed(identifier)
        } to book.addedAt
    }.maxWithOrNull(compareBy<Pair<ShelfMatch, Long?>> { it.first.preference }.thenBy { it.second ?: Long.MIN_VALUE })?.first
}

/** Higher is better: a Book here, then one whose file is missing, then a removed one. */
private val ShelfMatch.preference
    get() = when (this) {
        is ShelfMatch.Here -> 2
        is ShelfMatch.Missing -> 1
        is ShelfMatch.Removed -> 0
    }

/** A Book's detail page below its title and author: the one action, and the failure line above it (null for none). */
data class BookDetail(val action: DetailAction, val problem: FailureCopy?)

sealed interface DetailAction {
    /** "Read", with "On your Shelf" as secondary text: opens the Book at its Place ([file], inside filesDir). */
    data class Read(val file: String) : DetailAction

    /**
     * "Add to Shelf", "Download again" or "Retry" ([label]): downloads [link]. [replacing] is the
     * stored Book that matched, so the download keeps its row and its Place.
     */
    data class Download(val link: Acquisition, val label: String, val replacing: String?) : DetailAction

    /** "downloading…" */
    data object Downloading : DetailAction

    /** Nothing to do: the page has no EPUB, or it failed in a way a retry can't fix. */
    data object None : DetailAction
}

/**
 * The detail page of the Book whose Editions are [entries] (one entry, or a Gutenberg Book page's
 * several), given the Shelf. [failure] is how this page's last download failed, when the Shelf
 * didn't keep it (a permanent failure of a download from a Catalogue adds nothing to the Shelf).
 * [shipped] picks the certificate copy (see [feedFailureCopy]).
 *
 * The page matches the Shelf by any of its links (see [match]): a Book here reads "Read", one whose
 * file is missing "Download again", and a removed one "Add to Shelf"; the last two download with
 * [DetailAction.Download.replacing] so the Place is kept. A running download of any of its links or
 * of the matched Book reads "downloading…", and a failed one shows its copy, with "Retry" when a
 * retry can help and no action when it can't.
 */
fun bookDetail(snapshot: ShelfSnapshot, entries: List<CatalogueEntry>, failure: DownloadFailure?, shipped: Boolean): BookDetail {
    val link = bestDownload(entries) ?: return BookDetail(DetailAction.None, null)
    val links = entries.flatMap { entry -> entry.acquisitions.map { it.url } }.toSet() + link.url
    val match = snapshot.match(links)
    val downloads = snapshot.downloads.filter { (source, download) -> source in links || (match != null && download.replacing == match.identifier) }
    if (downloads.values.any { it.status == Download.Status.Running }) return BookDetail(DetailAction.Downloading, null)
    if (match is ShelfMatch.Here) return BookDetail(DetailAction.Read(match.file), null)
    val failed = downloads.values.firstNotNullOfOrNull { (it.status as? Download.Status.Failed)?.reason } ?: failure
    val problem = failed?.let { downloadFailureCopy(it, shipped) }
    val label = when {
        problem != null -> RETRY
        match is ShelfMatch.Missing -> DETAIL_DOWNLOAD_AGAIN
        else -> DETAIL_ADD
    }
    val action = if (problem?.retry == false) DetailAction.None else DetailAction.Download(link, label, match?.identifier)
    return BookDetail(action, problem)
}
