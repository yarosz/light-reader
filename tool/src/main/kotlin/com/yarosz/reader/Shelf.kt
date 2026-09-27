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

/** A row's identity: a Book on the Shelf, or a download of a Book the Shelf doesn't have yet. */
sealed interface RowKey {
    data class Shelved(val identifier: String) : RowKey

    /** Keyed by the download's source, because its Book's identifier is known only once it arrives. */
    data class Arriving(val source: HttpsUrl) : RowKey
}

/** A foreground download the Shelf shows, keyed by its source. [startedAt] is epoch millis. */
data class Transfer(val title: String, val author: String?, val startedAt: Long, val state: TransferState)

sealed interface TransferState {
    data object Running : TransferState

    /** Only a retryable failure stays on the Shelf ([isRetryable]); a permanent one leaves no row. */
    data class Failed(val reason: DownloadFailure) : TransferState
}

/**
 * Whether tapping "download failed · tap to retry" can help. The network and a full phone can
 * change between tries; a server with no HTTPS or an untrusted certificate, a file that isn't an
 * EPUB, and a copy-protected Book won't, so the Catalogue's detail page explains those instead.
 */
val DownloadFailure.isRetryable: Boolean
    get() = when (this) {
        Unreachable, is HttpError, DiskError -> true
        NoHttps, UntrustedCertificate, NotAnEpub, CopyProtected -> false
    }

/** What tapping a row does outside Edit. */
sealed interface RowTap {
    /** Opens the Book at its Place; [file] is a name inside filesDir. */
    data class Open(val file: String) : RowTap

    /** Downloads the Book from [source]: a retry, or a missing file downloaded again. */
    data class Download(val source: HttpsUrl, val title: String, val author: String?) : RowTap

    data object None : RowTap
}

/** One Shelf row: the title, the second line (null for none), and what a tap does. */
data class ShelfRow(val key: RowKey, val title: String, val detail: String?, val tap: RowTap)

/**
 * The Shelf's rows, in order: Books in progress by most recently read, then never-opened Books by
 * date added (newest first; a download that hasn't arrived counts as added when it started), then
 * finished Books by most recently read. [present] holds the file names that exist; [transfers] are
 * the running and retryable downloads, by source. A Book's download state wins over its file, and
 * its file over its reading state.
 */
fun shelfRows(data: ReadingData, present: Set<String>, transfers: Map<HttpsUrl, Transfer>): List<ShelfRow> {
    val shelved = data.books.filterValues { it.onShelf }
    val sources = shelved.values.mapNotNull { entry -> entry.source?.let { HttpsUrl.parse(it) } }.toSet()
    val books = shelved.map { (identifier, entry) ->
        val source = entry.source?.let { HttpsUrl.parse(it) }
        val row = bookRow(identifier, entry, source, entry.file?.takeIf { it in present }, transfers)
        val order = when {
            entry.place == null -> Order(1, entry.addedAt ?: Long.MIN_VALUE)
            entry.finished -> Order(2, entry.place.updatedAt)
            else -> Order(0, entry.place.updatedAt)
        }
        order to row
    }
    val arriving = transfers.filterKeys { it !in sources }.map { (source, transfer) ->
        Order(1, transfer.startedAt) to ShelfRow(RowKey.Arriving(source), transfer.title, transferDetail(transfer.state), transferTap(source, transfer))
    }
    return (books + arriving)
        .sortedWith(compareBy<Pair<Order, ShelfRow>> { it.first.group }.thenByDescending { it.first.time }.thenBy { it.second.title })
        .map { it.second }
}

/** [group] 0 in progress, 1 never opened, 2 finished; [time] sorts newest first within it. */
private data class Order(val group: Int, val time: Long)

private fun bookRow(identifier: String, entry: BookEntry, source: HttpsUrl?, file: String?, transfers: Map<HttpsUrl, Transfer>): ShelfRow {
    val key = RowKey.Shelved(identifier)
    val transfer = source?.let(transfers::get)
    if (source != null && transfer != null) return ShelfRow(key, entry.title, transferDetail(transfer.state), transferTap(source, transfer))
    return when {
        file == null && source != null -> ShelfRow(key, entry.title, ROW_FILE_MISSING_SOURCE, RowTap.Download(source, entry.title, entry.author))
        file == null -> ShelfRow(key, entry.title, ROW_FILE_MISSING, RowTap.None)
        entry.place == null -> ShelfRow(key, entry.title, ROW_NOT_STARTED, RowTap.Open(file))
        // In progress or finished: "author · 42%" and "finished" wait for N4's Progress, so the author stands alone.
        else -> ShelfRow(key, entry.title, entry.author, RowTap.Open(file))
    }
}

private fun transferDetail(state: TransferState) = when (state) {
    TransferState.Running -> ROW_DOWNLOADING
    is TransferState.Failed -> ROW_DOWNLOAD_FAILED
}

private fun transferTap(source: HttpsUrl, transfer: Transfer) = when (transfer.state) {
    TransferState.Running -> RowTap.None
    is TransferState.Failed -> RowTap.Download(source, transfer.title, transfer.author)
}

/** Whether the Shelf is browsing or editing, and in Edit, which row is asking to confirm its removal. */
sealed interface ShelfMode {
    data object Browsing : ShelfMode

    data class Editing(val confirming: RowKey? = null) : ShelfMode
}
