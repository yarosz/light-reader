package com.yarosz.reader

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The largest Book downloaded. Text EPUBs are well under 10 MB, and Gutenberg's image editions of
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
    data object Idle : DownloadState

    /** [received] bytes so far, of [total] when the server said how many. */
    data class Downloading(val received: Long, val total: Long?) : DownloadState {
        val progress: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    }

    sealed interface Finished : DownloadState

    /** The Book is on the phone as [file], a name inside the books directory. */
    data class Done(val identifier: String, val title: String, val file: String) : Finished

    data class Failed(val reason: DownloadFailure) : Finished
}

/**
 * Downloads Books into [dir] in the foreground. A download is written to a temp file, synced,
 * checked to be an EPUB that isn't copy-protected, and only then renamed into place, so no partial
 * or rejected file ever sits under a Book's name. The file is named after the Book's identifier, so
 * downloading a Book again replaces its file. A process killed mid-download leaves its temp file
 * behind; constructing a Downloader deletes such leftovers. [rename], [sync], [usableSpace], and
 * [maxBookBytes] exist so a test can make one fail.
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
            ?.forEach { it.delete() }
    }

    /**
     * Downloads [url], reporting progress to [onProgress] on the calling thread, which blocks.
     * [fallbackTitle] (the Catalogue entry's title) titles a Book whose package has none.
     * [onProgress] may throw to cancel, such as a coroutine's CancellationException; the temp file is
     * then deleted and the exception propagates.
     */
    fun download(url: HttpsUrl, fallbackTitle: String, onProgress: (DownloadState.Downloading) -> Unit): DownloadState.Finished {
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
        try {
            response.use {
                if (it.status !in 200..299) return DownloadState.Failed(HttpError(it.status))
                if (it.length != null && it.length > maxBookBytes) return DownloadState.Failed(NotAnEpub)
                if ((it.length ?: 0) + MIN_FREE_BYTES > usableSpace()) return DownloadState.Failed(DiskError)
                copy(it, temp, onProgress)?.let { failure -> return DownloadState.Failed(failure) }
            }
            return shelve(temp, fallbackTitle, lengthKnown = response.length != null)
        } finally {
            temp.delete()
        }
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
     * Checks [temp] and renames it into place. A file that starts as a zip but has no central
     * directory was cut short; with no declared length that is the only sign of a dropped connection.
     */
    private fun shelve(temp: File, fallbackTitle: String, lengthKnown: Boolean): DownloadState.Finished {
        val opened = try {
            ZipFile(temp)
        } catch (e: ZipException) {
            return DownloadState.Failed(if (!lengthKnown && startsLikeZip(temp)) Unreachable else NotAnEpub)
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
        val target = File(dir, bookFileName(pkg.identifier))
        if (!rename(temp, target)) return DownloadState.Failed(DiskError)
        return DownloadState.Done(pkg.identifier, pkg.title, target.name)
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
