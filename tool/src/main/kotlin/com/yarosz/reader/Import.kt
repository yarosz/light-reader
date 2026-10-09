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
 * How long an import pass waits before looking again at files still arriving. LightOS reports an
 * upload about 3 s after its last write, so a finished upload is usually checked once and settled.
 */
const val IMPORT_QUIET_MS = 10_000L

/**
 * How long a file that fails a check must have gone unwritten before it is rejected and deleted.
 * LightOS writes an upload in place under its final name, so a browser that stalls mid-upload leaves
 * a partial file that fails while its writer still holds it open; a minute without a write means the
 * upload has ended. A file that passes is taken at once: a partial zip never passes.
 */
const val IMPORT_GIVE_UP_MS = 60_000L

/** The inbox: where LightOS puts the files the reader uploads to "Add Books". */
fun importInbox(filesDir: File) = File(filesDir, "${LightFileProvider.SHARED_DIR}/$ADD_BOOKS_PATH")

/** Whether [name] is one [bookFileName] makes: the name of every file an import or a download keeps. */
fun isBookFileName(name: String) = BOOK_FILE_NAME.matches(name)

private val BOOK_FILE_NAME = Regex("[0-9a-f]{16}\\.epub")

/** Why Reader didn't add a file from the inbox, as the Shelf's notice says it ([noticeLine]). */
enum class ImportFailure {
    NotAnEpub,
    CopyProtected,

    /** Bigger than [MAX_BOOK_BYTES]. */
    TooLarge,

    /** The phone is short of room: the file stays in the inbox, and every pass tries it again. */
    NoRoom,

    /** Moving it out of the inbox failed with room to spare: the file stays, and every pass tries it again. */
    NotSaved,
}

/**
 * A file in the inbox that Reader didn't add, as the Shelf's notice says it: the file's [name] and
 * why ([reason]). [shown] is whether a visible Shelf has drawn it; the next time the Shelf shows, it
 * is gone.
 */
data class ImportNotice(val name: String, val reason: ImportFailure, val shown: Boolean = false)

/** What an import pass does with one file in the inbox, decided by [examine]. */
sealed interface Arrival {
    /** Passed every check: the Book goes on the Shelf, if the file is still as [checked] found it. */
    data class Accept(val checked: Checked, val length: Long, val modified: Long) : Arrival

    /** Failed a check and quiet for [IMPORT_GIVE_UP_MS]: the file is deleted, if still as checked, and the Shelf says why. */
    data class Reject(val reason: ImportFailure, val length: Long, val modified: Long) : Arrival

    /** A Book, but the phone is short of room: the file stays, and the Shelf says so. */
    data object NoRoom : Arrival

    /** Failed a check but written in the last [IMPORT_GIVE_UP_MS]: left for a later look. */
    data object Waiting : Arrival

    /** A directory, which an upload never makes: left alone. */
    data object Ignore : Arrival
}

/**
 * Checks one inbox [file] as a download is checked ([checkEpub], and no bigger than
 * [MAX_BOOK_BYTES]), at [now] in epoch millis. Every file is checked, a hidden name (a leading ".")
 * too. A file that fails while it was written in the last [IMPORT_GIVE_UP_MS] (a zero-byte file just
 * created among them) is [Arrival.Waiting], never rejected. A Book needs [MIN_FREE_BYTES] of
 * [usableSpace] left, as a download does; moving it costs no space, but the reading data must still
 * be saveable. Blocks; run it off the main thread.
 */
fun examine(file: File, now: Long, usableSpace: () -> Long): Arrival {
    if (file.isDirectory) return Arrival.Ignore
    val length = file.length()
    val modified = file.lastModified()
    val checked = if (length > MAX_BOOK_BYTES) null else checkEpub(file, file.nameWithoutExtension)
    if (checked is Checked) return if (usableSpace() < MIN_FREE_BYTES) Arrival.NoRoom else Arrival.Accept(checked, length, modified)
    if (modified > now - IMPORT_GIVE_UP_MS) return Arrival.Waiting
    val reason = when ((checked as DownloadState.Failed?)?.reason) {
        null -> ImportFailure.TooLarge
        CopyProtected -> ImportFailure.CopyProtected
        else -> ImportFailure.NotAnEpub
    }
    return Arrival.Reject(reason, length, modified)
}

/** Whether this file still has the [length] and [modified] time a check found: nothing wrote to it since. */
fun File.isAsChecked(length: Long, modified: Long) = exists() && length() == length && lastModified() == modified

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

    private fun keyOf(reason: ImportFailure) = when (reason) {
        ImportFailure.NotAnEpub -> "not-an-epub"
        ImportFailure.CopyProtected -> "copy-protected"
        ImportFailure.TooLarge -> "too-large"
        ImportFailure.NoRoom -> "no-room"
        ImportFailure.NotSaved -> "not-saved"
    }

    private fun reasonOf(key: String): ImportFailure? = ImportFailure.entries.firstOrNull { keyOf(it) == key }
}
