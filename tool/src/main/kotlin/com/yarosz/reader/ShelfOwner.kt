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

/**
 * How many times an import pass waits [IMPORT_QUIET_MS] for files still arriving before leaving them
 * to the next pass: enough for a file whose upload stalled as the pass began to reach
 * [IMPORT_GIVE_UP_MS] and be rejected by the same pass.
 */
const val IMPORT_WAITS = 6

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
 * same way. [transport], [usableSpace], [rename], [delete] and [now] exist for tests; [rename] and
 * [delete] are an import's moves and deletions of uploaded files.
 */
class ShelfOwner(
    private val filesDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transport: Transport = HttpsTransport(),
    private val logFailure: (String) -> Unit = ::logFeedFailure,
    private val usableSpace: () -> Long = filesDir::getUsableSpace,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val delete: (File) -> Boolean = File::delete,
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

    /**
     * Whether the reading data came from a saved file that parsed. Only then does an import pass
     * [recover]: with none read (missing, unreadable or corrupt), every Book's file looks unnamed.
     */
    private var readSaved = false

    private val loaded = scope.async(start = CoroutineStart.LAZY) {
        val (fromDisk, start, stored) = withContext(io) { Triple(store.loadSaved(), devStartFile(), noticeStore.load()) }
        saver.loaded(fromDisk ?: ReadingData())
        readSaved = fromDisk != null
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

    /** Marks the notices a visible Shelf drew, [seen], as shown, so the next time the Shelf shows they are gone. */
    fun noticesShown(seen: List<ImportNotice>) {
        scope.launch {
            loaded.await()
            changeNotices { list -> list.map { notice -> if (seen.any { it.name == notice.name && it.reason == notice.reason }) notice.copy(shown = true) else notice } }
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
     * leaves a notice for the Shelf once it has gone [IMPORT_GIVE_UP_MS] unwritten; until then it is
     * left for later. A Book the phone lacks room for, or that can't be moved, stays in the inbox with
     * a notice, and every pass tries it again. Files are checked as found and acted on only if nothing
     * wrote to them since ([isAsChecked]). A pass first moves back to the inbox any Book's file that no
     * Book names ([recover]), once the reading data was read from a saved file. It scans again after it has taken files, since LightOS drops its reports
     * while a pass runs, waits [IMPORT_QUIET_MS] for files still arriving, up to [IMPORT_WAITS] times,
     * and before it ends lists the inbox once more, scanning again if a file came or changed. Passes
     * run one at a time; files are checked on [io] and moved on the main thread, as a download lands.
     */
    fun importBooks(): Job = scope.launch {
        loaded.await()
        importing.withLock {
            recover()
            var waits = 0
            var relisted = false
            while (true) {
                val round = importRound()
                when {
                    round.taken > 0 -> continue
                    round.waiting && waits < IMPORT_WAITS -> {
                        waits++
                        delay(IMPORT_QUIET_MS)
                    }
                    !relisted && withContext(io) { listInbox() } != round.left -> relisted = true
                    else -> break
                }
            }
        }
    }

    /** One scan of the inbox: how many files left it, whether any was left to settle, and the listing it leaves behind. */
    private class Round(val taken: Int, val waiting: Boolean, val left: Map<String, Long>)

    /** The inbox's files by name, with when each was last written. Blocks. */
    private fun listInbox(): Map<String, Long> = inbox.listFiles().orEmpty().associate { it.name to it.lastModified() }

    private suspend fun importRound(): Round {
        val (listing, examined) = withContext(io) {
            inbox.mkdirs()
            val files = inbox.listFiles().orEmpty().map { it to it.lastModified() }.sortedBy { it.second }
            files.associate { (file, modified) -> file.name to modified } to files.map { (file) -> file to examine(file, now(), usableSpace) }
        }
        val gone = mutableSetOf<String>()
        var refused = 0
        var stuck = 0
        var waiting = false
        for ((file, arrival) in examined) {
            when (arrival) {
                is Arrival.Accept -> when {
                    // Written to again since the check: the next scan checks it afresh.
                    !file.isAsChecked(arrival.length, arrival.modified) -> waiting = true
                    land(file, arrival.checked) -> gone += file.name
                    else -> notice(file.name, if (usableSpace() < MIN_FREE_BYTES) ImportFailure.NoRoom else ImportFailure.NotSaved)
                }
                is Arrival.Reject -> when {
                    !file.isAsChecked(arrival.length, arrival.modified) -> waiting = true
                    else -> {
                        notice(file.name, arrival.reason)
                        if (delete(file) || !file.exists()) {
                            gone += file.name
                            refused++
                        } else {
                            stuck++
                        }
                    }
                }
                Arrival.NoRoom -> notice(file.name, ImportFailure.NoRoom)
                Arrival.Waiting -> waiting = true
                Arrival.Ignore -> Unit
            }
        }
        if (gone.isNotEmpty() || stuck > 0) Log.i(TAG, "import added=${gone.size - refused} refused=$refused undeletable=$stuck")
        return Round(gone.size, waiting, listing - gone)
    }

    /** On the main thread: moves [file], a [checked] Book, out of the inbox and onto the Shelf. False when it can't be moved. */
    private fun land(file: File, checked: Checked): Boolean {
        val name = bookFileName(checked.identifier)
        val replaced = saver.data.books[checked.identifier]?.file
        if (!rename(file, File(filesDir, name))) return false
        saver.change { it.shelve(checked.identifier, checked.title, name, checked.author, source = null, now = now()) }
        // A Book stored under another file name (one opened before files were named by identifier) leaves no orphan behind.
        if (replaced != null && replaced != name && saver.data.books.values.none { it.file == replaced }) {
            val old = File(filesDir, replaced)
            if (!delete(old) && old.exists()) Log.w(TAG, "import couldn't delete a replaced file")
            fileChanged(replaced, exists = false)
        }
        fileChanged(name, exists = true)
        saver.flush()
        changeNotices { list -> list.filterNot { it.name == file.name } }
        publish()
        return true
    }

    /**
     * On the main thread: moves each Book's file (a [bookFileName] in filesDir) that no Book names back
     * into the inbox, so this pass imports it again. A process killed between an import's or a
     * download's rename and its save leaves one; downloads in progress use temp names, and every
     * rename into a Book's name is saved in the same main-thread step, so a file found here is never
     * one still on its way. The file of a Book off the Shelf is left alone: a removal that couldn't
     * delete it must not bring the Book back. Does nothing unless the reading data was read from a
     * saved file ([readSaved]), so a lost or corrupt file never takes every Book off the Shelf.
     */
    private suspend fun recover() {
        if (!readSaved) return
        val found = withContext(io) { filesDir.list().orEmpty().filter(::isBookFileName) }
        val named = saver.data.books.values.mapNotNull { it.file }.toSet()
        val removed = saver.data.books.filterValues { !it.onShelf }.keys.map(::bookFileName).toSet()
        val orphans = found.filter { it !in named && it !in removed }
        if (orphans.isEmpty()) return
        withContext(io) { inbox.mkdirs() }
        val moved = orphans.count { name ->
            val target = File(inbox, name)
            (!target.exists() && rename(File(filesDir, name), target)).also { if (it) fileChanged(name, exists = false) }
        }
        Log.i(TAG, "import recovered=$moved of ${orphans.size}")
    }

    /** Adds the notice for the file [name], replacing an earlier one for that name with another reason; the same one stays as it is. */
    private fun notice(name: String, reason: ImportFailure) = changeNotices { list ->
        if (list.any { it.name == name && it.reason == reason }) list else list.filterNot { it.name == name } + ImportNotice(name, reason)
    }

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
