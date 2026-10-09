package com.yarosz.reader

import android.util.Log
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val TAG = "Reader"

/** The Tool Manager's "Add Books" page's path: its uploads land in filesDir/shared/add-books, the inbox. */
const val ADD_BOOKS_PATH = "add-books"

/**
 * How recently a file must have been written for a failed check to leave it in the inbox for a later
 * pass instead of rejecting it: it may still be arriving. LightOS reports an upload about 3 s after
 * its last write, so a finished upload is usually checked once and settled.
 */
const val IMPORT_QUIET_MS = 10_000L

/** The inbox: where LightOS puts the files the reader uploads to "Add Books". */
fun importInbox(filesDir: File) = File(filesDir, "${LightFileProvider.SHARED_DIR}/$ADD_BOOKS_PATH")

/**
 * A file in the inbox that Reader didn't add, as the Shelf's notice says it: the file's [name] and
 * why ([reason]: NotAnEpub, CopyProtected, or DiskError for a file left for want of room). [shown] is
 * whether a Shelf has shown it; the next time the Shelf shows, it is gone.
 */
data class ImportNotice(val name: String, val reason: DownloadFailure, val shown: Boolean = false)

/** What an import pass does with one file in the inbox, decided by [examine]. */
sealed interface Arrival {
    /** Passed every check: the Book goes on the Shelf, if the file is still as [checked] found it. */
    data class Accept(val checked: Checked, val length: Long, val modified: Long) : Arrival

    /** Failed a check: the file is deleted and the Shelf says why. */
    data class Reject(val reason: DownloadFailure) : Arrival

    /** A Book, but the phone is short of room: the file stays, and the Shelf says so. */
    data object NoRoom : Arrival

    /** Recently written and not yet a Book, or a hidden name not yet quiet: left for a later pass. */
    data object Waiting : Arrival

    /** A hidden name (a leading "."), quiet for [IMPORT_QUIET_MS]: deleted, with no notice. */
    data object Discard : Arrival

    /** A directory, which an upload never makes: left alone. */
    data object Ignore : Arrival
}

/**
 * Checks one inbox [file] as a download is checked ([checkEpub], and no bigger than
 * [MAX_BOOK_BYTES]), at [now] in epoch millis. A file that fails a check while it was written in the
 * last [IMPORT_QUIET_MS] (a zero-byte file just created among them) is [Arrival.Waiting], never
 * rejected. A Book needs [MIN_FREE_BYTES] of [usableSpace] left, as a download does; moving it costs
 * no space, but the reading data must still be saveable. Blocks; run it off the main thread.
 */
fun examine(file: File, now: Long, usableSpace: () -> Long): Arrival {
    if (file.isDirectory) return Arrival.Ignore
    val length = file.length()
    val modified = file.lastModified()
    val recent = modified > now - IMPORT_QUIET_MS
    if (file.name.startsWith(".")) return if (recent) Arrival.Waiting else Arrival.Discard
    val checked = if (length > MAX_BOOK_BYTES) DownloadState.Failed(NotAnEpub) else checkEpub(file, file.nameWithoutExtension)
    return when (checked) {
        is Checked -> if (usableSpace() < MIN_FREE_BYTES) Arrival.NoRoom else Arrival.Accept(checked, length, modified)
        is DownloadState.Failed -> if (recent) Arrival.Waiting else Arrival.Reject(checked.reason)
    }
}

/**
 * The import notices in [dir]'s `import-notices.json`, kept apart from the reading data so its
 * schema doesn't change. Written whole through a temp file and a rename, so a kill leaves the old
 * list or the new one. [ShelfOwner] is the only writer.
 */
class ImportNoticeStore(dir: File) {
    private val file = File(dir, "import-notices.json")
    private val temp = File(dir, "import-notices.json.tmp")

    /** The stored notices, oldest first; none when the file is missing or unreadable. */
    fun load(): List<ImportNotice> = runCatching {
        if (!file.exists()) return emptyList()
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
        (root["notices"] as JsonArray).mapNotNull { element ->
            val notice = element as? JsonObject ?: return@mapNotNull null
            val name = (notice["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val reason = (notice["reason"] as? JsonPrimitive)?.content?.let(::reasonOf) ?: return@mapNotNull null
            ImportNotice(name, reason, (notice["shown"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }.onFailure { Log.w(TAG, "import notices don't parse", it) }.getOrDefault(emptyList())

    /** Replaces the stored notices with [notices]. Throws IOException when the write or rename fails. */
    fun save(notices: List<ImportNotice>) {
        val json = JsonObject(
            mapOf(
                "notices" to JsonArray(
                    notices.map {
                        JsonObject(mapOf("name" to JsonPrimitive(it.name), "reason" to JsonPrimitive(keyOf(it.reason)), "shown" to JsonPrimitive(it.shown)))
                    },
                ),
            ),
        )
        FileOutputStream(temp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("couldn't rename ${temp.name} to ${file.name}")
    }

    private fun keyOf(reason: DownloadFailure) = when (reason) {
        CopyProtected -> "copy-protected"
        DiskError -> "no-room"
        else -> "not-an-epub"
    }

    private fun reasonOf(key: String): DownloadFailure? = when (key) {
        "copy-protected" -> CopyProtected
        "no-room" -> DiskError
        "not-an-epub" -> NotAnEpub
        else -> null
    }
}
