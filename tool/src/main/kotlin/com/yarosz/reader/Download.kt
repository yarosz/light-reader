package com.yarosz.reader

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

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
 * checked to be an EPUB that isn't copy-protected, and only then renamed into place, so [dir] never
 * holds a partial or rejected Book. The file is named after the Book's identifier, so downloading a
 * Book again replaces its file. [rename] and [sync] exist so a test can make one fail.
 */
class Downloader(
    private val transport: Transport,
    private val dir: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
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
            File.createTempFile("download-", ".part", dir)
        } catch (e: IOException) {
            response.close()
            return DownloadState.Failed(DiskError)
        }
        try {
            response.use {
                if (it.status !in 200..299) return DownloadState.Failed(HttpError(it.status))
                if (it.length != null && it.length > dir.usableSpace) return DownloadState.Failed(DiskError)
                copy(it, temp, onProgress)?.let { failure -> return DownloadState.Failed(failure) }
            }
            return shelve(temp, fallbackTitle)
        } finally {
            temp.delete()
        }
    }

    private fun copy(response: Response, temp: File, onProgress: (DownloadState.Downloading) -> Unit): DownloadFailure? {
        var received = 0L
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

    private fun shelve(temp: File, fallbackTitle: String): DownloadState.Finished {
        val pkg = try {
            ZipFile(temp).use { zip ->
                if (isCopyProtected(zip)) return DownloadState.Failed(CopyProtected)
                readPackage(zip, fallbackTitle)
            }
        } catch (e: Exception) {
            return DownloadState.Failed(NotAnEpub)
        }
        val target = File(dir, bookFileName(pkg.identifier))
        if (!rename(temp, target)) return DownloadState.Failed(DiskError)
        return DownloadState.Done(pkg.identifier, pkg.title, target.name)
    }
}

/** A file name safe on any file system for the Book with [identifier]: identifiers are URLs, URNs, or ISBNs. */
fun bookFileName(identifier: String): String =
    MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray()).take(8).joinToString("") { "%02x".format(it) } + ".epub"
