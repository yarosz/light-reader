package com.yarosz.reader

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Reader"

/**
 * The file a dev-start session opens, bypassing the Shelf: `scripts/ci.sh` and `scripts/perf.sh`
 * put their Book there. The name is the pre-Shelf Reader's, so devices that ran it keep working.
 */
const val DEV_BOOK_FILE = "alice.epub"

/**
 * The Shelf, the Tool's first screen, and the one writer of the reading data: [saver] is handed to
 * each Reader it opens, so the two never save over each other. Every Book lives in [filesDir]. It
 * runs the foreground downloads (no background service in v1) on [io], one per source, each
 * cancellable. State changes happen on the main thread. [transport] and [now] exist for tests.
 */
class ShelfViewModel(
    private val filesDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transport: Transport = HttpsTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) : LightViewModel<Unit>() {
    /** The rows to show; null until the reading data is loaded. */
    val rows = MutableStateFlow<List<ShelfRow>?>(null)
    val mode = MutableStateFlow<ShelfMode>(ShelfMode.Browsing)

    /** A dev-start session to open once (see [DEV_BOOK_FILE]); the screen clears it when it navigates. */
    val devStart = MutableStateFlow<DevStart?>(null)

    private val store = ReadingStore(filesDir)
    val saver = ReadingSaver(viewModelScope, io, SAVE_DEBOUNCE_MS, store::save) { Log.w(TAG, "reading data save failed", it) }

    /** Deletes a killed process's partial downloads when first used, off the main thread. */
    private val downloader by lazy { Downloader(transport, filesDir) }
    private val transfers = mutableMapOf<HttpsUrl, Transfer>()
    private val jobs = mutableMapOf<HttpsUrl, Deferred<DownloadState.Finished>>()

    /** Names of the Books' files that exist, as of the last [refresh]. */
    private var present = emptySet<String>()

    private val loaded = viewModelScope.async(start = CoroutineStart.LAZY) {
        val (fromDisk, start) = withContext(io) { store.load() to devStartFile() }
        saver.loaded(fromDisk)
        devStart.value = start
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        refresh()
    }

    /** Loads the reading data once, then rechecks which files exist: the Reader may have changed the Shelf meanwhile. */
    internal fun refresh() {
        viewModelScope.launch {
            loaded.await()
            val data = saver.data
            present = withContext(io) { data.books.values.mapNotNull { it.file }.filter { File(filesDir, it).exists() }.toSet() }
            publish()
        }
    }

    /**
     * What tapping [row] does: the Book's file to open in the Reader, or null after starting its
     * download or doing nothing. Rows never open while editing.
     */
    fun tap(row: ShelfRow): File? {
        if (mode.value != ShelfMode.Browsing) return null
        return when (val tap = row.tap) {
            is RowTap.Open -> File(filesDir, tap.file)
            is RowTap.Download -> null.also { download(tap.source, tap.title, tap.author) }
            RowTap.None -> null
        }
    }

    /** Starts downloading [entry]'s [acquisition], as a Catalogue detail page's "Add to Shelf" does. */
    fun download(entry: CatalogueEntry, acquisition: Acquisition): Deferred<DownloadState.Finished> =
        download(acquisition.url, entry.title, entry.authors.joinToString(", ").ifEmpty { null })

    /**
     * Downloads [source] onto the Shelf, or returns the download of it already running. The row reads
     * "downloading…" meanwhile. [DownloadState.Done] puts the Book on the Shelf with [source] as its
     * source, keeping a Place it had. A retryable failure leaves "download failed · tap to retry"; a
     * permanent one leaves nothing, and the caller shows it. Removing the row cancels the download,
     * and awaiting it then throws CancellationException. [title] titles a Book whose package has none.
     */
    fun download(source: HttpsUrl, title: String, author: String?): Deferred<DownloadState.Finished> {
        jobs[source]?.takeIf { it.isActive }?.let { return it }
        transfers[source] = Transfer(title, author, transfers[source]?.startedAt ?: now(), TransferState.Running)
        publish()
        val job = viewModelScope.async {
            val finished = withContext(io) {
                val result = downloader.download(source, title) { ensureActive() }
                // A removal can land after the last progress report: never leave its file behind.
                if (result is DownloadState.Done && !isActive) File(filesDir, result.file).delete()
                ensureActive()
                result
            }
            jobs.remove(source)
            when (finished) {
                is DownloadState.Done -> {
                    transfers.remove(source)
                    saver.change { it.shelve(finished.identifier, finished.title, finished.file, finished.author ?: author, source.value, now()) }
                    present = present + finished.file
                }
                is DownloadState.Failed -> {
                    if (finished.reason.isRetryable) transfers[source] = transfers.getValue(source).copy(state = TransferState.Failed(finished.reason))
                    else transfers.remove(source)
                }
            }
            publish()
            finished
        }
        jobs[source] = job
        return job
    }

    fun toggleEdit() {
        mode.value = if (mode.value == ShelfMode.Browsing) ShelfMode.Editing() else ShelfMode.Browsing
    }

    /** The row's trailing "Remove" in Edit: the row turns into the confirmation. */
    fun askToRemove(key: RowKey) {
        if (mode.value is ShelfMode.Editing) mode.value = ShelfMode.Editing(confirming = key)
    }

    fun cancelRemove() {
        if (mode.value is ShelfMode.Editing) mode.value = ShelfMode.Editing()
    }

    /**
     * Takes the row off the Shelf: cancels its download, deletes its file, and keeps its Place, so
     * adding the Book again opens where the reader left off.
     */
    fun remove(key: RowKey) {
        when (key) {
            is RowKey.Arriving -> cancel(key.source)
            is RowKey.Shelved -> {
                val entry = saver.data.books[key.identifier] ?: return
                entry.source?.let { HttpsUrl.parse(it) }?.let(::cancel)
                saver.change { it.unshelve(key.identifier) }
                entry.file?.let { file ->
                    present = present - file
                    viewModelScope.launch(io) { File(filesDir, file).delete() }
                }
            }
        }
        cancelRemove()
        publish()
    }

    /** A saver for a dev-start session: it starts empty and writes nothing, so the device's reading data is left as it was. */
    fun devSaver() = ReadingSaver(viewModelScope, io, SAVE_DEBOUNCE_MS, { }) { }

    override fun onAppPause() = saver.flush()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = saver.flush()

    override fun onCleared() {
        saver.flush()
        super.onCleared()
    }

    private fun cancel(source: HttpsUrl) {
        jobs.remove(source)?.cancel()
        transfers.remove(source)
    }

    /** Shows the rows as they now are; an emptied Shelf leaves Edit, since Edit is hidden then. */
    private fun publish() {
        if (!loaded.isCompleted) return
        val shown = shelfRows(saver.data, present, transfers.toMap())
        rows.value = shown
        if (shown.isEmpty()) mode.value = ShelfMode.Browsing
    }

    /**
     * Dev hook for `scripts/perf.sh` and `scripts/ci.sh`: filesDir/dev-start opens [DEV_BOOK_FILE] at a
     * chapter and offset, at the default font, with an optional window size (see [parseDevStart]).
     * Such a session neither reads nor saves the reading data. Only `adb shell run-as` can write that
     * file, and run-as works on debuggable builds only. A read error or garbage opens the Shelf.
     */
    private fun devStartFile(): DevStart? = runCatching {
        File(filesDir, "dev-start").takeIf { it.exists() }?.readText()?.let(::parseDevStart)
    }.getOrNull()
}
