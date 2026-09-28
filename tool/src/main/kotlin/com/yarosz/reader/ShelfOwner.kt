package com.yarosz.reader

import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Reader"

/**
 * The file a dev-start session opens, bypassing the Shelf: `scripts/ci.sh` and `scripts/perf.sh`
 * put their Book there. The name is the pre-Shelf Reader's, so devices that ran it keep working.
 */
const val DEV_BOOK_FILE = "alice.epub"

/** How a Shelf download ended. Never a cancellation, so awaiting one can't cancel the caller. */
sealed interface DownloadResult {
    /** The Book is on the Shelf under [identifier]. */
    data class Done(val identifier: String) : DownloadResult

    data class Failed(val reason: DownloadFailure) : DownloadResult

    /** The reader removed the row before the Book landed; nothing was added. */
    data object Removed : DownloadResult
}

/**
 * The one owner of a files directory's Shelf in this process: the reading data and its [saver],
 * which Books' files exist, and the foreground downloads (no background service in v1). LightOS can
 * recreate the activity in the same process without clearing the old screens' view models, so no
 * view model can own these: two savers over one file interleave their writes, and a stale one puts
 * a removed Book back. [ShelfViewModel] and [ReaderViewModel] are views onto the owner that [of]
 * returns, which lives as long as the process. Every Book lives in [filesDir].
 *
 * State changes happen on the main thread, and so does every change to a Book's file name (a
 * download landing, a removal deleting): they are then ordered, so a removal can't delete a Book
 * that a download landed after it, and a download that lost its row never leaves a file behind.
 * Those are single renames and deletes; the downloads themselves run on [io]. [transport] and [now]
 * exist for tests.
 */
class ShelfOwner(
    private val filesDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transport: Transport = HttpsTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ReadingStore(filesDir)
    val saver = ReadingSaver(scope, io, SAVE_DEBOUNCE_MS, store::save) { Log.w(TAG, "reading data save failed", it) }

    private val shown = MutableStateFlow<List<ShelfRow>?>(null)

    /** The rows to show; null until the reading data is loaded. */
    val rows: StateFlow<List<ShelfRow>?> = shown

    /** A dev-start session to open once (see [DEV_BOOK_FILE]); the Shelf clears it when it navigates. */
    val devStart = MutableStateFlow<DevStart?>(null)

    /** Deletes a killed process's partial downloads when first used, which is off the main thread. */
    private val downloader by lazy { Downloader(transport, filesDir) }
    private val transfers = mutableMapOf<HttpsUrl, Transfer>()
    private val running = mutableMapOf<HttpsUrl, Running>()

    /** Names of the Books' files that exist. */
    private var present = emptySet<String>()

    /** Counts changes made here to Books' files; [changedAt] is the count at each file's last change. */
    private var fileChanges = 0L
    private val changedAt = mutableMapOf<String, Long>()

    private class Running(val job: Job, val result: CompletableDeferred<DownloadResult>)

    private val loaded = scope.async(start = CoroutineStart.LAZY) {
        val (fromDisk, start) = withContext(io) { store.load() to devStartFile() }
        saver.loaded(fromDisk)
        devStart.value = start
    }

    /** Suspends until the reading data is loaded, loading it on first use. */
    suspend fun awaitLoaded() = loaded.await()

    /**
     * Loads the reading data once, then rechecks which files exist: something outside the Shelf may
     * have changed them. A file this owner changed while the check ran keeps what the change left.
     */
    fun refresh() {
        scope.launch {
            loaded.await()
            val asOf = fileChanges
            val names = saver.data.books.values.mapNotNull { it.file }.toSet()
            val found = withContext(io) { names.filter { File(filesDir, it).exists() }.toSet() }
            val checked = names.filter { (changedAt[it] ?: -1) <= asOf }.toSet()
            present = present - checked + (found intersect checked)
            publish()
        }
    }

    /**
     * Downloads [source] onto the Shelf, or returns the download of it already running. Its row reads
     * "downloading…" meanwhile. On success the Book goes on the Shelf titled [title] (the Catalogue
     * entry's, or the stored one), with [source] as its source, keeping a Place it had. [replacing]
     * is the Shelf row downloading its missing file again: a Book that arrives under another
     * identifier takes over that row's Place and date (see [moveBook]). A retryable failure leaves
     * "download failed · tap to retry"; a permanent one leaves the [replacing] row saying why, and
     * a download from a Catalogue nothing, for the caller to show. Removing the row ends the download
     * as [DownloadResult.Removed].
     */
    fun download(source: HttpsUrl, title: String, author: String?, replacing: String? = null): Deferred<DownloadResult> {
        running[source]?.let { return it.result }
        transfers[source] = Transfer(title, author, transfers[source]?.startedAt ?: now(), TransferState.Running, replacing)
        val result = CompletableDeferred<DownloadResult>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            loaded.await()
            val me = coroutineContext.job
            withContext(NonCancellable) {
                val fetched = try {
                    withContext(io) { downloader.fetch(source, title) { me.ensureActive() } }
                } catch (e: CancellationException) {
                    null
                }
                result.complete(land(source, me, fetched))
            }
        }
        job.invokeOnCompletion { result.complete(DownloadResult.Removed) }
        running[source] = Running(job, result)
        job.start()
        publish()
        return result
    }

    /**
     * Takes the row off the Shelf: ends its download, deletes its file, and keeps its Place, so adding
     * the Book again opens where the reader left off.
     */
    fun remove(key: RowKey) {
        when (key) {
            is RowKey.Arriving -> cancel(key.source)
            is RowKey.Shelved -> {
                val entry = saver.data.books[key.identifier] ?: return
                transfers.filterValues { it.replacing == key.identifier }.keys.forEach(::cancel)
                saver.change { it.unshelve(key.identifier) }
                val file = entry.file
                if (file != null && saver.data.books.values.none { it.onShelf && it.file == file }) {
                    File(filesDir, file).deleteOrLog()
                    fileChanged(file, exists = false)
                }
            }
        }
        publish()
    }

    /**
     * On the main thread, with [fetched] from [me]'s download of [source] (null when it was cancelled
     * first): keeps the Book when [me] is still its source's download, else deletes the temp file.
     */
    private fun land(source: HttpsUrl, me: Job, fetched: Fetch?): DownloadResult {
        if (fetched == null || running[source]?.job !== me) {
            (fetched as? Checked)?.discard()
            return DownloadResult.Removed
        }
        running.remove(source)
        val transfer = transfers.getValue(source)
        val finished = when (fetched) {
            is Checked -> downloader.keep(fetched)
            is DownloadState.Failed -> fetched
        }
        val result = when (finished) {
            is DownloadState.Done -> {
                transfers.remove(source)
                saver.change { data ->
                    val moved = transfer.replacing?.let { data.moveBook(it, finished.identifier) } ?: data
                    moved.shelve(finished.identifier, transfer.title.ifBlank { finished.title }, finished.file, finished.author ?: transfer.author, source.value, now())
                }
                fileChanged(finished.file, exists = true)
                DownloadResult.Done(finished.identifier)
            }
            is DownloadState.Failed -> {
                if (finished.reason.isRetryable || transfer.replacing != null) transfers[source] = transfer.copy(state = TransferState.Failed(finished.reason))
                else transfers.remove(source)
                DownloadResult.Failed(finished.reason)
            }
        }
        publish()
        return result
    }

    private fun cancel(source: HttpsUrl) {
        running.remove(source)?.job?.cancel()
        transfers.remove(source)
    }

    private fun fileChanged(name: String, exists: Boolean) {
        changedAt[name] = ++fileChanges
        present = if (exists) present + name else present - name
    }

    private fun publish() {
        if (loaded.isCompleted) shown.value = shelfRows(saver.data, present, transfers.toMap())
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

    companion object {
        private val owners = mutableMapOf<String, ShelfOwner>()

        /** The process's owner of [filesDir], made by [create] the first time it is asked for. */
        fun of(filesDir: File, create: () -> ShelfOwner = { ShelfOwner(filesDir) }): ShelfOwner =
            synchronized(owners) { owners.getOrPut(filesDir.canonicalPath, create) }
    }
}
