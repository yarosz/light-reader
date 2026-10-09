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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** How many times an import pass waits [IMPORT_QUIET_MS] for files still arriving before leaving them to the next pass. */
private const val IMPORT_WAITS = 3

/**
 * The one owner of a files directory's Shelf in this process: the reading data and its [saver],
 * which Books' files exist, the foreground downloads (no background service in v1), and the imports
 * from the Tool Manager's inbox with their notices ([importBooks]). LightOS can
 * recreate the activity in the same process without clearing the old screens' view models, so no
 * view model can own these: two savers over one file interleave their writes, and a stale one puts
 * a removed Book back. [ShelfViewModel] and [ReaderViewModel] are views onto the owner that [of]
 * returns, which lives as long as the process. Every Book lives in [filesDir].
 *
 * State changes happen on the main thread, and so does every change to a Book's file name (a
 * download landing, a removal deleting): they are then ordered, so a removal can't delete a Book
 * that a download landed after it, and a download that lost its row never leaves a file behind.
 * Those are single renames and deletes; the downloads themselves run on [io]. A landing and a
 * removal also save the reading data at once rather than after the debounce: they are rare, and a
 * screen on top of the Shelf may never see the pause that would flush them. An import lands the
 * same way. [transport], [usableSpace] and [now] exist for tests.
 */
class ShelfOwner(
    private val filesDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transport: Transport = HttpsTransport(),
    private val logFailure: (String) -> Unit = ::logFeedFailure,
    private val usableSpace: () -> Long = filesDir::getUsableSpace,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ReadingStore(filesDir)
    val saver = ReadingSaver(scope, io, SAVE_DEBOUNCE_MS, store::save) { Log.w(TAG, "reading data save failed", it) }

    private val snapshots = MutableStateFlow<ShelfSnapshot?>(null)

    /** What the Shelf knows, which its rows ([ShelfSnapshot.rows]) and the Catalogue screens read; null until the reading data is loaded. */
    val snapshot: StateFlow<ShelfSnapshot?> = snapshots

    /** The reader's reading speed, shared by every Book this process opens and never saved. */
    val speed = ReadingSpeed()

    /** A dev-start session to open once (see [DEV_BOOK_FILE]); the Shelf clears it when it navigates. */
    val devStart = MutableStateFlow<DevStart?>(null)

    /** Deletes a killed process's partial downloads when first used, which is off the main thread. */
    private val downloader by lazy { Downloader(transport, filesDir) }
    private val downloads = mutableMapOf<HttpsUrl, Download>()
    private val running = mutableMapOf<HttpsUrl, Running>()

    /** Names of the Books' files that exist. */
    private var present = emptySet<String>()

    /** Counts changes made here to Books' files; [changedAt] is the count at each file's last change. */
    private var fileChanges = 0L
    private val changedAt = mutableMapOf<String, Long>()

    private class Running(val job: Job, val result: CompletableDeferred<DownloadResult>, val connected: () -> Boolean?)

    private val inbox = importInbox(filesDir)
    private val noticeStore = ImportNoticeStore(filesDir)

    /** The files Reader didn't add, oldest first, one per file name; changed on the main thread. */
    @Volatile
    private var notices = emptyList<ImportNotice>()
    private val noticeSaves = Any()

    /** One import pass at a time, so a pass from LightOS and one from the Shelf never take the same file twice. */
    private val importing = Mutex()

    private val loaded = scope.async(start = CoroutineStart.LAZY) {
        val (fromDisk, start, stored) = withContext(io) { Triple(store.load(), devStartFile(), noticeStore.load()) }
        saver.loaded(fromDisk)
        devStart.value = start
        notices = stored
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
     * is the stored Book the download is for (a Shelf row downloading its missing file again, or a
     * removed Book added again from a Catalogue): a Book that arrives under another identifier takes
     * over its Place and date (see [moveBook]). A retryable failure leaves "download failed · tap to
     * retry"; a permanent one leaves a [replacing] row that is on the Shelf saying why, and otherwise
     * nothing, for the caller to show. Removing the row ends the download
     * as [DownloadResult.Removed]. [connected] is whether the phone reports a connection (null when it
     * can't say), asked when the download fails: see [Download.Status.Failed.offline].
     */
    fun download(
        source: HttpsUrl,
        title: String,
        author: String?,
        replacing: String? = null,
        connected: () -> Boolean? = { null },
    ): Deferred<DownloadResult> {
        running[source]?.let { return it.result }
        downloads[source] = Download(title, author, downloads[source]?.startedAt ?: now(), Download.Status.Running, replacing)
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
        running[source] = Running(job, result, connected)
        job.start()
        publish()
        return result
    }

    /**
     * The Shelf is showing: notices it showed before go, which files exist is rechecked ([refresh]),
     * and the inbox is imported ([importBooks]), since LightOS's report of an upload can be dropped
     * or arrive before this process has an owner.
     */
    fun shelfShown() {
        scope.launch {
            loaded.await()
            changeNotices { list -> list.filterNot { it.shown } }
        }
        refresh()
        importBooks()
    }

    /** Marks every notice as shown, so the next time the Shelf shows they are gone. */
    fun noticesShown() {
        scope.launch {
            loaded.await()
            changeNotices { list -> list.map { it.copy(shown = true) } }
        }
    }

    /** The reader tapped the notice: every notice goes. */
    fun clearNotices() {
        scope.launch {
            loaded.await()
            changeNotices { emptyList() }
        }
    }

    /**
     * Imports every file in the inbox, LightOS's folder for the Tool Manager's "Add Books" page: each
     * Book that passes the checks a download passes ([examine]) moves out of the inbox under its
     * identifier's file name ([bookFileName]) and goes on the Shelf with its own title and author and
     * no new source, keeping a Place it had, even after a removal. A file that fails is deleted and
     * leaves a notice for the Shelf; one written in the last [IMPORT_QUIET_MS] is left for later
     * instead. A Book the phone lacks room for stays in the inbox, with a notice, and every pass tries
     * it again. A pass scans again after it has taken files, since LightOS drops its reports while a
     * pass runs, and waits for files still arriving, up to [IMPORT_WAITS] times. Passes run one at a
     * time; files are checked on [io] and moved on the main thread, as a download lands.
     */
    fun importBooks(): Job = scope.launch {
        loaded.await()
        importing.withLock {
            var waits = 0
            while (true) {
                val (taken, waiting) = importRound()
                when {
                    taken > 0 -> continue
                    waiting && waits++ < IMPORT_WAITS -> delay(IMPORT_QUIET_MS)
                    else -> break
                }
            }
        }
    }

    /** One scan of the inbox: how many files left it, and whether any was left to settle. */
    private suspend fun importRound(): Pair<Int, Boolean> {
        val files = withContext(io) { inbox.mkdirs(); inbox.listFiles()?.sortedBy { it.lastModified() }.orEmpty() }
        var added = 0
        var refused = 0
        var discarded = 0
        var waiting = false
        for (file in files) {
            when (val arrival = withContext(io) { examine(file, now(), usableSpace) }) {
                is Arrival.Accept -> when {
                    // Written to again since the check: the next scan checks it afresh.
                    file.length() != arrival.length || file.lastModified() != arrival.modified -> waiting = true
                    land(file, arrival.checked) -> added++
                    else -> notice(file.name, DiskError)
                }
                is Arrival.Reject -> {
                    file.deleteOrLog()
                    notice(file.name, arrival.reason)
                    refused++
                }
                Arrival.NoRoom -> notice(file.name, DiskError)
                Arrival.Waiting -> waiting = true
                Arrival.Discard -> {
                    file.deleteOrLog()
                    discarded++
                }
                Arrival.Ignore -> Unit
            }
        }
        if (added + refused > 0) Log.i(TAG, "import added=$added refused=$refused")
        return (added + refused + discarded) to waiting
    }

    /** On the main thread: moves [file], a [checked] Book, out of the inbox and onto the Shelf. False when it can't be moved. */
    private fun land(file: File, checked: Checked): Boolean {
        val name = bookFileName(checked.identifier)
        if (!file.renameTo(File(filesDir, name))) return false
        saver.change { it.shelve(checked.identifier, checked.title, name, checked.author, source = null, now = now()) }
        fileChanged(name, exists = true)
        saver.flush()
        changeNotices { list -> list.filterNot { it.name == file.name } }
        publish()
        return true
    }

    /** Adds the notice for the file [name], replacing an earlier one for that name. */
    private fun notice(name: String, reason: DownloadFailure) =
        changeNotices { list -> list.filterNot { it.name == name } + ImportNotice(name, reason) }

    /** On the main thread: changes the notices, publishes them, and saves them on [io] when they changed. */
    private fun changeNotices(transform: (List<ImportNotice>) -> List<ImportNotice>) {
        val changed = transform(notices)
        if (changed == notices) return
        notices = changed
        publish()
        scope.launch(io) {
            // Saves can run out of order, so each writes the latest list, read under the lock.
            synchronized(noticeSaves) {
                runCatching { noticeStore.save(notices) }.onFailure { Log.w(TAG, "import notices save failed", it) }
            }
        }
    }

    /** Fetches a Catalogue page on [io]; [search] marks a search's results or their "More" ([fetchPage]). */
    suspend fun fetchPage(url: HttpsUrl, search: Boolean = false): Fetched<CataloguePage> = withContext(io) { fetchPage(transport, url, logFailure, search) }

    /** Fetches the search template an OpenSearch description offers, on [io]. */
    suspend fun fetchSearch(description: HttpsUrl): Fetched<SearchTemplate> = withContext(io) { fetchSearch(transport, description, logFailure) }

    /** Puts [catalogue] on the list of Catalogues, or back on it, and saves at once. */
    fun addCatalogue(catalogue: Catalogue) = changeCatalogues { it.withCatalogue(catalogue, now()) }

    /** Takes the Catalogue at [url] off the list and saves at once; Books added from it stay on the Shelf. */
    fun removeCatalogue(url: HttpsUrl) = changeCatalogues { it.withoutCatalogue(url, now()) }

    private fun changeCatalogues(transform: (ReadingData) -> ReadingData) {
        scope.launch {
            loaded.await()
            saver.change(transform)
            saver.flush()
            publish()
        }
    }

    /**
     * Takes the row off the Shelf: ends its download, deletes its file, and keeps its Place, so adding
     * the Book again opens where the reader left off.
     */
    fun remove(key: RowKey) {
        when (key) {
            is RowKey.Arriving -> cancel(key.source)
            is RowKey.Shelved -> {
                val book = saver.data.books[key.identifier] ?: return
                downloads.filterValues { it.replacing == key.identifier }.keys.forEach(::cancel)
                saver.change { it.unshelve(key.identifier) }
                val file = book.file
                if (file != null && saver.data.books.values.none { it.onShelf && it.file == file }) {
                    File(filesDir, file).deleteOrLog()
                    fileChanged(file, exists = false)
                }
                saver.flush()
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
        val connected = running.remove(source)?.connected
        val download = downloads.getValue(source)
        val ended = when (fetched) {
            is Checked -> downloader.keep(fetched)
            is DownloadState.Failed -> fetched
        }
        val result = when (ended) {
            is DownloadState.Done -> {
                downloads.remove(source)
                saver.change { data ->
                    val moved = download.replacing?.let { data.moveBook(it, ended.identifier) } ?: data
                    val title = download.replacing?.let { moved.books[ended.identifier]?.title } ?: download.title
                    moved.shelve(
                        ended.identifier,
                        title.ifBlank { ended.title },
                        ended.file,
                        ended.author ?: download.author,
                        source.value,
                        now(),
                    )
                }
                fileChanged(ended.file, exists = true)
                saver.flush()
                DownloadResult.Done(ended.identifier)
            }
            is DownloadState.Failed -> {
                val replacesRow = download.replacing?.let { saver.data.books[it]?.onShelf } == true
                val offline = ended.reason == Unreachable && connected?.invoke() == false
                if (ended.reason.isRetryable || replacesRow) downloads[source] = download.copy(status = Download.Status.Failed(ended.reason, offline))
                else downloads.remove(source)
                DownloadResult.Failed(ended.reason)
            }
        }
        publish()
        return result
    }

    private fun cancel(source: HttpsUrl) {
        running.remove(source)?.job?.cancel()
        downloads.remove(source)
    }

    private fun fileChanged(name: String, exists: Boolean) {
        changedAt[name] = ++fileChanges
        present = if (exists) present + name else present - name
    }

    private fun publish() {
        if (!loaded.isCompleted) return
        snapshots.value = ShelfSnapshot(saver.data, present, downloads.toMap(), notices)
    }

    /**
     * Dev hook for `scripts/perf.sh` and `scripts/ci.sh`: filesDir/dev-start opens [DEV_BOOK_FILE] at a
     * Spine item and offset, at the default font, with an optional window size (see [parseDevStart]).
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

        /**
         * The process's owner, once a screen has made one. LightOS reports an upload to a worker with
         * no filesDir to hand (see [ReaderEntryPoint]); with no owner yet, the Shelf imports when it
         * next shows.
         */
        fun ofProcess(): ShelfOwner? = synchronized(owners) { owners.values.singleOrNull() }

        /** For tests, whose directories don't outlive them: ends [filesDir]'s owner and forgets it and its save lock. */
        internal fun forget(filesDir: File) {
            synchronized(owners) { owners.remove(filesDir.canonicalPath) }?.scope?.cancel()
            forgetSaveLock(filesDir)
        }
    }
}
