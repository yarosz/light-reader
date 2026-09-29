package com.yarosz.reader

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * A foreground download the Shelf shows, keyed by its source. [title] is the one the Book gets: the
 * Catalogue entry's, or for a download from the Shelf, the stored one. [startedAt] is epoch millis.
 * [replacing] is the identifier of the Shelf row that started it, to download a missing file again;
 * null for a download from a Catalogue, which shows as a row of its own until it arrives.
 */
data class Download(val title: String, val author: String?, val startedAt: Long, val status: Status, val replacing: String? = null) {
    sealed interface Status {
        data object Running : Status

        /**
         * A download from a Catalogue stays on the Shelf only after a retryable failure ([isRetryable]);
         * a download from the Shelf keeps its row whatever the failure. [offline] is whether it was
         * Unreachable while the phone reported no internet connection, which the row then says, so a
         * retry tapped offline visibly answers instead of reading "tap to retry" unchanged. The row
         * says it in the past tense, since it stays after the phone reconnects.
         */
        data class Failed(val reason: DownloadFailure, val offline: Boolean = false) : Status
    }
}

/**
 * The largest Book downloaded. Text EPUBs are well under 10 MB, and Gutenberg's image Editions of
 * long illustrated works run to tens of MB (Pride and Prejudice's is 25 MB); 300 MB leaves room for
 * any real Book while bounding what a broken or hostile server can write to the phone. Opening a Book
 * never loads the whole file (the zip is read by entry), so this bounds storage and time, not memory.
 */
const val MAX_BOOK_BYTES = 300L * 1024 * 1024

/** Storage a download always leaves free, so the reading data can still be saved when a Book fills the phone. */
const val MIN_FREE_BYTES = 16L * 1024 * 1024

/** How often, in bytes written, a download checks that storage hasn't run out. */
private const val SPACE_CHECK_BYTES = 1024L * 1024

/** A temp file older than this was left by a killed process: a running download writes to its own every few seconds. */
private const val STALE_PART_MS = 10L * 60 * 1000

private const val TAG = "Reader"

private const val PART_PREFIX = "download-"
private const val PART_SUFFIX = ".part"

/** One download as the UI shows it. */
sealed interface DownloadState {
    /** [received] bytes so far, of [total] when the server said how many. */
    data class Downloading(val received: Long, val total: Long?) : DownloadState {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    }

    sealed interface Outcome : DownloadState

    /** The Book is on the phone as [file], a name inside the Downloader's directory. [author] is its package's, if any. */
    data class Done(val identifier: String, val title: String, val file: String, val author: String? = null) : Outcome

    data class Failed(val reason: DownloadFailure) : Outcome, Fetch
}

/** What [Downloader.fetch] brings back: a [Checked] Book, or why it failed. */
sealed interface Fetch

/**
 * A downloaded Book that passed every check, still in its temp file inside the Downloader's
 * directory. [Downloader.keep] renames it into place; [discard] deletes it.
 */
class Checked(val temp: File, val identifier: String, val title: String, val author: String?) : Fetch {
    fun discard() = temp.deleteOrLog()
}

/** Deletes this file, logging when it exists and can't be deleted: a Book's file or a temp file left behind. */
fun File.deleteOrLog() {
    if (!delete() && exists()) Log.w(TAG, "couldn't delete $name")
}

/**
 * Downloads Books into [dir] in the foreground. A download is written to a temp file, synced,
 * checked to be an EPUB that isn't copy-protected, and only then renamed into place, so no partial
 * or rejected file ever sits under a Book's name. [fetch] does the slow part and [keep] the rename,
 * so a caller can decide on its own thread whether the Book is still wanted. The file is named after
 * the Book's identifier, so downloading a Book again replaces its file. A process killed
 * mid-download leaves its temp file behind; constructing a Downloader deletes such leftovers.
 * [rename], [sync], [usableSpace], and [maxBookBytes] exist so a test can make one fail.
 */
class Downloader(
    private val transport: Transport,
    private val dir: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val usableSpace: () -> Long = dir::getUsableSpace,
    private val maxBookBytes: Long = MAX_BOOK_BYTES,
) {
    init {
        val staleBefore = System.currentTimeMillis() - STALE_PART_MS
        dir.listFiles { file -> file.name.startsWith(PART_PREFIX) && file.name.endsWith(PART_SUFFIX) && file.lastModified() < staleBefore }
            ?.forEach { it.deleteOrLog() }
    }

    /** [fetch] then [keep]. */
    fun download(
        url: HttpsUrl,
        fallbackTitle: String,
        onProgress: (DownloadState.Downloading) -> Unit,
    ): DownloadState.Outcome =
        when (val fetched = fetch(url, fallbackTitle, onProgress)) {
            is Checked -> keep(fetched)
            is DownloadState.Failed -> fetched
        }

    /**
     * Downloads [url] into a temp file and checks it, reporting progress to [onProgress] on the
     * calling thread, which blocks. [fallbackTitle] (the Catalogue entry's title) titles a Book whose
     * package has none. [onProgress] may throw to cancel, such as a coroutine's CancellationException;
     * the temp file is then deleted and the exception propagates. Only a [Checked] result keeps its
     * temp file.
     */
    fun fetch(url: HttpsUrl, fallbackTitle: String, onProgress: (DownloadState.Downloading) -> Unit): Fetch {
        val response = try {
            transport.get(url)
        } catch (e: IOException) {
            return DownloadState.Failed(unreachable(url, e))
        }
        val temp = try {
            File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
        } catch (e: IOException) {
            response.close()
            return DownloadState.Failed(DiskError)
        }
        var checked: Checked? = null
        try {
            response.use {
                if (it.status !in 200..299) return DownloadState.Failed(HttpError(it.status))
                if (it.length != null && it.length > maxBookBytes) return DownloadState.Failed(NotAnEpub)
                if ((it.length ?: 0) + MIN_FREE_BYTES > usableSpace()) return DownloadState.Failed(DiskError)
                copy(it, temp, onProgress)?.let { failure -> return DownloadState.Failed(failure) }
            }
            return inspect(temp, fallbackTitle, lengthKnown = response.length != null).also { checked = it as? Checked }
        } finally {
            if (checked == null) temp.deleteOrLog()
        }
    }

    /** Renames [checked]'s temp file to its Book's name, replacing an earlier download of the Book. */
    fun keep(checked: Checked): DownloadState.Outcome {
        val target = File(dir, bookFileName(checked.identifier))
        if (!rename(checked.temp, target)) {
            checked.discard()
            return DownloadState.Failed(DiskError)
        }
        return DownloadState.Done(checked.identifier, checked.title, target.name, checked.author)
    }

    /**
     * Copies the body to [temp], reading no more than the declared length (more is a broken response)
     * or, with none declared, [maxBookBytes] (more isn't a Book the Tool takes), and stopping while
     * [MIN_FREE_BYTES] are still free.
     */
    private fun copy(response: Response, temp: File, onProgress: (DownloadState.Downloading) -> Unit): DownloadFailure? {
        val limit = response.length ?: maxBookBytes
        var received = 0L
        var spaceCheckedAt = -SPACE_CHECK_BYTES
        onProgress(DownloadState.Downloading(received, response.length))
        try {
            FileOutputStream(temp).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = try {
                        response.body.read(buffer)
                    } catch (e: IOException) {
                        return Unreachable
                    }
                    if (n < 0) break
                    if (received + n > limit) return if (response.length != null) Unreachable else NotAnEpub
                    if (received - spaceCheckedAt >= SPACE_CHECK_BYTES) {
                        if (usableSpace() < n + MIN_FREE_BYTES) return DiskError
                        spaceCheckedAt = received
                    }
                    out.write(buffer, 0, n)
                    received += n
                    onProgress(DownloadState.Downloading(received, response.length))
                }
                out.flush()
                sync(out)
            }
        } catch (e: IOException) {
            return DiskError
        }
        return if (response.length != null && received < response.length) Unreachable else null
    }

    /**
     * Checks that [temp] is a readable EPUB that isn't copy-protected. A file that starts as a zip but
     * has no central directory was cut short; with no declared length that is the only sign of a
     * dropped connection.
     */
    private fun inspect(temp: File, fallbackTitle: String, lengthKnown: Boolean): Fetch {
        val opened = try {
            ZipFile(temp)
        } catch (e: ZipException) {
            return DownloadState.Failed(if (!lengthKnown && startsLikeZip(temp)) Unreachable else NotAnEpub)
        } catch (e: IOException) {
            return DownloadState.Failed(NotAnEpub)
        }
        val pkg = try {
            opened.use { zip ->
                if (isCopyProtected(zip)) return DownloadState.Failed(CopyProtected)
                readPackage(zip, fallbackTitle)
            }
        } catch (e: Exception) {
            // Broad on purpose: the file is untrusted. Logged so a parser bug is diagnosable.
            Log.w(TAG, "download is not a readable EPUB", e)
            return DownloadState.Failed(NotAnEpub)
        }
        return Checked(temp, pkg.identifier, pkg.title, pkg.author)
    }
}

private fun startsLikeZip(file: File): Boolean {
    val header = ByteArray(4)
    val n = file.inputStream().use { it.read(header) }
    return n == 4 && header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
}

/** A file name safe on any file system for the Book with [identifier]: identifiers are URLs, URNs, or ISBNs. */
fun bookFileName(identifier: String): String =
    MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray()).take(8).joinToString("") { "%02x".format(it) } + ".epub"
