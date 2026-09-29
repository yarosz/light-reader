package com.yarosz.reader

/** Shelf copy, verbatim from DESIGN.md "Shelf". */
const val SHELF_TITLE = "Reader"
const val SHELF_EDIT = "Edit"
const val SHELF_DONE = "Done"
const val SHELF_ADD = "Add"
const val SHELF_EMPTY = "Nothing on your Shelf yet."
const val SHELF_ADD_A_BOOK = "Add a Book"
const val SHELF_REMOVE = "Remove"
const val SHELF_CANCEL = "Cancel"
const val SHELF_CONFIRM_REMOVE = "Remove from Shelf? Your place is kept if you add it again."
const val ROW_NOT_STARTED = "not started"
const val ROW_DOWNLOADING = "downloading…"
const val ROW_DOWNLOAD_FAILED = "download failed · tap to retry"
const val ROW_FILE_MISSING = "file missing"
const val ROW_FILE_MISSING_SOURCE = "file missing · tap to download again"
const val ROW_CANT_DOWNLOAD_COPY_PROTECTED = "can't download again · copy-protected"
const val ROW_CANT_DOWNLOAD_NOT_AN_EPUB = "can't download again · not an EPUB"
const val ROW_CANT_DOWNLOAD_NO_HTTPS = "can't download again · needs https"
const val ROW_CANT_DOWNLOAD_NEEDS_LOGIN = "can't download again · needs a login"

/** A row's identity: a Book on the Shelf, or a download of a Book the Shelf doesn't have yet. */
sealed interface RowKey {
    data class Shelved(val identifier: String) : RowKey

    /** Keyed by the download's source, because its Book's identifier is known only once it arrives. */
    data class Arriving(val source: HttpsUrl) : RowKey
}

/**
 * Whether trying again can help: the one rule behind a Shelf row's "tap to retry" and every "Retry"
 * ([FailureCopy.retry]). The network and a full phone can change between tries, and so can an
 * untrusted certificate: public Wi-Fi intercepts TLS until the reader signs in to it (D15). A server
 * with no HTTPS, a file that isn't an EPUB, and a copy-protected Book won't, and neither will a 401,
 * because no sign-in exists to change the answer. The Catalogue's detail page explains those; a Shelf
 * row whose download failed that way reads "can't download again · …" and can only be removed.
 */
val DownloadFailure.isRetryable: Boolean
    get() = when (this) {
        Unreachable, DiskError, UntrustedCertificate -> true
        is HttpError -> status != 401
        NoHttps, NotAnEpub, CopyProtected -> false
    }

/** What tapping a row does outside Edit. */
sealed interface RowTap {
    /** Opens the Book at its Place; [file] is a name inside filesDir. */
    data class Open(val file: String) : RowTap

    /**
     * Downloads the Book from [source]: a retry, or a missing file downloaded again. [replacing] is
     * the Book the row shows, so the download can take over its Place (see [com.yarosz.reader.Download.replacing]).
     */
    data class Download(val source: HttpsUrl, val title: String, val author: String?, val replacing: String? = null) : RowTap

    data object None : RowTap
}

/** One Shelf row: the title, the second line (null for none), and what a tap does. */
data class ShelfRow(val key: RowKey, val title: String, val detail: String?, val tap: RowTap)

/**
 * The Shelf's rows, in order: Books in progress by most recently read, then never-opened Books by
 * date added (newest first; a download that hasn't arrived counts as added when it started), then
 * finished Books by most recently read. [present] holds the file names that exist; [downloads] are
 * the downloads the Shelf shows, by source. A download shows on the one row it replaces, else as a
 * row of its own. A running download wins over a Book's file, the file over a failed download, and
 * both over its reading state.
 */
fun shelfRows(data: ReadingData, present: Set<String>, downloads: Map<HttpsUrl, Download>): List<ShelfRow> {
    val shelved = data.books.filterValues { it.onShelf }
    val books = shelved.map { (identifier, book) ->
        val download = downloads.entries.firstOrNull { it.value.replacing == identifier }
        val row = bookRow(identifier, book, book.file?.takeIf { it in present }, download)
        val order = when {
            book.place == null -> Order(1, book.addedAt ?: Long.MIN_VALUE)
            book.finished -> Order(2, book.place.updatedAt)
            else -> Order(0, book.place.updatedAt)
        }
        order to row
    }
    val arriving = downloads.filterValues { it.replacing !in shelved }.map { (source, download) ->
        Order(1, download.startedAt) to ShelfRow(RowKey.Arriving(source), download.title, downloadDetail(download.status), downloadTap(source, download))
    }
    return (books + arriving)
        .sortedWith(compareBy<Pair<Order, ShelfRow>> { it.first.group }.thenByDescending { it.first.time }.thenBy { it.second.title })
        .map { it.second }
}

/** [group] 0 in progress, 1 never opened, 2 finished; [time] sorts newest first within it. */
private data class Order(val group: Int, val time: Long)

private fun bookRow(identifier: String, book: Book, file: String?, download: Map.Entry<HttpsUrl, Download>?): ShelfRow {
    val key = RowKey.Shelved(identifier)
    val source = book.source?.let { HttpsUrl.parse(it) }
    if (download != null && (download.value.status == Download.Status.Running || file == null)) {
        return ShelfRow(key, book.title, downloadDetail(download.value.status), downloadTap(download.key, download.value))
    }
    return when {
        file == null && source != null -> ShelfRow(key, book.title, ROW_FILE_MISSING_SOURCE, RowTap.Download(source, book.title, book.author, identifier))
        file == null -> ShelfRow(key, book.title, ROW_FILE_MISSING, RowTap.None)
        book.place == null -> ShelfRow(key, book.title, ROW_NOT_STARTED, RowTap.Open(file))
        // In progress or finished: "author · 42%" and "finished" wait for N4's Progress, so the author stands alone.
        else -> ShelfRow(key, book.title, book.author, RowTap.Open(file))
    }
}

private fun downloadDetail(status: Download.Status) = when (status) {
    Download.Status.Running -> ROW_DOWNLOADING
    is Download.Status.Failed -> failedDetail(status.reason)
}

/** "download failed · tap to retry" when retrying can help ([isRetryable]), else why it can't. */
private fun failedDetail(reason: DownloadFailure) = if (reason.isRetryable) ROW_DOWNLOAD_FAILED else when (reason) {
    CopyProtected -> ROW_CANT_DOWNLOAD_COPY_PROTECTED
    NotAnEpub -> ROW_CANT_DOWNLOAD_NOT_AN_EPUB
    NoHttps -> ROW_CANT_DOWNLOAD_NO_HTTPS
    is HttpError -> ROW_CANT_DOWNLOAD_NEEDS_LOGIN
    Unreachable, DiskError, UntrustedCertificate -> ROW_DOWNLOAD_FAILED
}

/** A running download does nothing on a tap, and neither does a permanent failure: its row can only be removed. */
private fun downloadTap(source: HttpsUrl, download: Download): RowTap {
    val status = download.status
    val retryable = status is Download.Status.Failed && status.reason.isRetryable
    return if (retryable) RowTap.Download(source, download.title, download.author, download.replacing) else RowTap.None
}

/** Whether the Shelf is browsing or editing, and in Edit, which row is asking to confirm its removal. */
sealed interface ShelfMode {
    data object Browsing : ShelfMode

    data class Editing(val confirming: RowKey? = null) : ShelfMode
}
