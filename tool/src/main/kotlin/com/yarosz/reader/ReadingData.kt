package com.yarosz.reader

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

private const val TAG = "Reader"

/**
 * The reading data file's format version. The compatibility rule: a later schema only adds fields. It
 * never changes the type or meaning of a field an earlier schema knows. So a file with a higher
 * schemaVersion is read for its known fields, and every other field is carried verbatim in `extras`
 * and written back. An older build therefore never loses what a newer one wrote, with one exception:
 * a page turn creates a fresh Place, so a newer build's Place-level extras describe the old Place and
 * are dropped with it. Extras at the top level, in settings, and on a Book survive.
 */
const val CURRENT_SCHEMA = 1

/** Characters of chapter text kept with a Place so it can be re-found after offsets shift (ADR 0002). */
const val SNIPPET_CHARS = 40

/** Where the reader is, in memory: a chapter index and a character offset into [Chapter.text]. */
data class Position(val chapter: Int, val offset: Int)

/**
 * A Place as stored (ADR 0002): the Spine item, the block within its Chapter, the offset within that
 * block, and the first [SNIPPET_CHARS] characters of text there. [updatedAt] is epoch millis; the
 * newer Place wins a [merge]. Relayout never rewrites a Place; only turning a Page does.
 */
data class Place(
    val spineId: String,
    val block: Int,
    val offset: Int,
    val snippet: String,
    val updatedAt: Long,
    val extras: Map<String, JsonElement> = emptyMap(),
)

/**
 * One Book's reading state, keyed by the Book's identifier in [ReadingData.books]. [file] is the EPUB's
 * name inside filesDir, where every Book lives, never a path. [finished] travels with the Place.
 * [author] is the package's `dc:creator`s, for the Shelf row. [source] is the Book's source: the
 * acquisition URL it was last downloaded from, which downloads it again when its file goes missing;
 * null for a Book that didn't come from a Catalogue. [addedAt] is when it was last put on the Shelf,
 * epoch millis; null in a file from before the Shelf, which sorts as oldest.
 */
data class BookEntry(
    val title: String,
    val file: String?,
    val place: Place?,
    val finished: Boolean,
    val onShelf: Boolean,
    val author: String? = null,
    val source: String? = null,
    val addedAt: Long? = null,
    val extras: Map<String, JsonElement> = emptyMap(),
)

data class Settings(val fontStep: Int = DEFAULT_FONT_STEP, val extras: Map<String, JsonElement> = emptyMap())

/**
 * Everything the Reader keeps between launches: each Book's Place and Shelf state, and the settings.
 * Every level carries the JSON fields this build doesn't know in `extras` (see [CURRENT_SCHEMA]).
 */
data class ReadingData(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val books: Map<String, BookEntry> = emptyMap(),
    val settings: Settings = Settings(),
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** The title stored for the Book in [file], a name inside filesDir, or null when none is stored. */
fun ReadingData.storedTitle(file: String): String? = books.values.firstOrNull { it.file == file }?.title?.takeIf { it.isNotBlank() }

/**
 * Puts the Book on the Shelf, adding its entry when it has none, with [title] and [file] current. A
 * null [author] or [source] keeps the one stored. [now] becomes [BookEntry.addedAt] when the Book
 * wasn't on the Shelf; a Book already there keeps its date. Its Place is always kept.
 */
fun ReadingData.shelve(
    identifier: String,
    title: String,
    file: String,
    author: String? = null,
    source: String? = null,
    now: Long? = null,
): ReadingData {
    val old = books[identifier] ?: BookEntry(title = title, file = file, place = null, finished = false, onShelf = false)
    val entry = old.copy(
        title = title,
        file = file,
        onShelf = true,
        author = author ?: old.author,
        source = source ?: old.source,
        addedAt = if (!old.onShelf && now != null) now else old.addedAt,
    )
    return copy(books = books + (identifier to entry))
}

/**
 * Takes the Book off the Shelf: its file is forgotten (the caller deletes it) and everything else is
 * kept, so adding it again brings back its Place.
 */
fun ReadingData.unshelve(identifier: String): ReadingData {
    val entry = books[identifier] ?: return this
    return copy(books = books + (identifier to entry.copy(file = null, onShelf = false)))
}

/**
 * Moves the Book at [from] to [to], as when downloading it again brings a package that declares a
 * new identifier (Calibre mints one on every conversion): its Place, finished flag, date added,
 * title, source and unknown fields go to [to], replacing an entry there that is off the Shelf. A
 * Book already on the Shelf at [to] stays as it is except for its Place, which becomes the newer of
 * the two (with its finished flag; a tie keeps its own). [from] stays behind off the Shelf with no
 * file, the state a removal leaves, rather than being dropped: [merge] keeps every Book the file on
 * disk has, so a dropped key would come back on the next save.
 */
fun ReadingData.moveBook(from: String, to: String): ReadingData {
    val entry = books[from]?.takeIf { from != to } ?: return this
    val moved = books[to]?.takeIf { it.onShelf }?.let { target ->
        val reading = if (entry.placeTime > target.placeTime) entry else target
        target.copy(place = reading.place, finished = reading.finished)
    } ?: entry
    return copy(books = books + (to to moved) + (from to entry.copy(file = null, onShelf = false)))
}

/**
 * Records [place] for a Book already in [ReadingData.books]; an unknown Book is left out. Its
 * [Place.updatedAt] is kept later than the Book's previous Place, so a clock stepped backwards can't
 * make the newest Place lose a [merge].
 */
fun ReadingData.withPlace(identifier: String, place: Place): ReadingData {
    val entry = books[identifier] ?: return this
    val updatedAt = entry.place?.let { maxOf(place.updatedAt, it.updatedAt + 1) } ?: place.updatedAt
    return copy(books = books + (identifier to entry.copy(place = place.copy(updatedAt = updatedAt))))
}

/**
 * The Place at [textOffset], clamped to the chapter. A separating '\n' belongs to the block before it,
 * as in [Chapter.kindAt]. The snippet ends on a code-point boundary: a split surrogate pair would not
 * survive UTF-8.
 */
fun Chapter.placeOf(textOffset: Int, now: Long): Place {
    val at = textOffset.coerceIn(0, text.length)
    val found = blockStarts.binarySearch(at)
    val block = (if (found >= 0) found else -found - 2).coerceAtLeast(0)
    var end = minOf(at + SNIPPET_CHARS, text.length)
    if (end < text.length && text[end - 1].isHighSurrogate()) end--
    return Place(
        spineId = spineId,
        block = block,
        offset = at - (blockStarts.getOrNull(block) ?: 0),
        snippet = text.substring(at, end),
        updatedAt = now,
    )
}

/**
 * Finds [place] in this Book: at its block and offset when the snippet still matches there, else at
 * the snippet's occurrence nearest that spot (a new edition or parser change shifted the text), else
 * at the start of its block, or of its chapter when the block is gone. Null only when no chapter has
 * the Place's Spine item.
 */
fun Book.resolve(place: Place): Position? {
    val index = chapters.indexOfFirst { it.spineId == place.spineId }.takeIf { it >= 0 } ?: return null
    val text = chapters[index].text
    val blockStart = chapters[index].blockStarts.getOrNull(place.block)
    val expected = blockStart?.plus(place.offset)?.takeIf { it in 0..text.length }
    if (expected != null && text.startsWith(place.snippet, expected)) return Position(index, expected)
    if (place.snippet.isNotEmpty()) {
        val anchor = expected ?: blockStart ?: 0
        val nearest = generateSequence(text.indexOf(place.snippet).takeIf { it >= 0 }) { from ->
            text.indexOf(place.snippet, from + 1).takeIf { it >= 0 }
        }.minByOrNull { abs(it - anchor) }
        if (nearest != null) return Position(index, nearest)
    }
    return Position(index, blockStart ?: 0)
}

/**
 * Combines the file on disk with this process's data before a save. What it protects: the Books and
 * the unknown fields another build wrote, which [mine] doesn't carry, and each Book's newest Place.
 * Only the Place is independent of the order of saves; fontStep, a Book's file, Shelf state, and
 * title come from [mine], so the save that runs last decides them (see [ReadingSaver], which makes
 * that the save with the newest data). Idempotent: merge(x, x) == x, and merging the same [mine]
 * twice changes nothing.
 * - schemaVersion: the higher, so an older build never downgrades the file.
 * - books: both sides' Books. For a Book on both sides, see [mergeEntry].
 * - settings: fontStep from [mine]; unknown fields from both, [mine] winning a clash.
 * - unknown top-level fields: from both, [mine] winning a clash.
 */
fun merge(disk: ReadingData, mine: ReadingData): ReadingData = ReadingData(
    schemaVersion = maxOf(disk.schemaVersion, mine.schemaVersion),
    books = (disk.books.keys + mine.books.keys).associateWith { id ->
        val d = disk.books[id]
        val m = mine.books[id]
        if (d != null && m != null) mergeEntry(d, m) else m ?: d!!
    },
    settings = Settings(mine.settings.fontStep, disk.settings.extras + mine.settings.extras),
    extras = disk.extras + mine.extras,
)

/**
 * The newer Place wins, and brings its [BookEntry.finished] with it: a missing Place counts as oldest
 * and a tie goes to [mine]. [BookEntry.file], [BookEntry.onShelf] and [BookEntry.addedAt] come from
 * [mine], because this process owns the files. The title is [mine]'s unless blank; the author and
 * source are [mine]'s unless null. Unknown fields come from both, [mine] winning a clash.
 */
private fun mergeEntry(disk: BookEntry, mine: BookEntry): BookEntry {
    val reading = if (disk.placeTime > mine.placeTime) disk else mine
    return BookEntry(
        title = mine.title.ifBlank { disk.title },
        file = mine.file,
        place = reading.place,
        finished = reading.finished,
        onShelf = mine.onShelf,
        author = mine.author ?: disk.author,
        source = mine.source ?: disk.source,
        addedAt = mine.addedAt,
        extras = disk.extras + mine.extras,
    )
}

/** When the Book's Place was recorded; a Book with no Place counts as oldest. */
private val BookEntry.placeTime get() = place?.updatedAt ?: Long.MIN_VALUE

private val prettyJson = Json { prettyPrint = true }

private val TOP_FIELDS = setOf("schemaVersion", "settings", "books")
private val SETTINGS_FIELDS = setOf("fontStep")
private val ENTRY_FIELDS = setOf("title", "file", "place", "finished", "onShelf", "author", "source", "addedAt")
private val PLACE_FIELDS = setOf("spineId", "block", "offset", "snippet", "updatedAt")

/** The file's text. Absent Places, files, authors, sources and dates are omitted; unknown fields are written back as they came. */
fun ReadingData.encode(): String = prettyJson.encodeToString(
    JsonElement.serializer(),
    jsonObject(
        TOP_FIELDS,
        extras,
        "schemaVersion" to JsonPrimitive(schemaVersion),
        "settings" to jsonObject(SETTINGS_FIELDS, settings.extras, "fontStep" to JsonPrimitive(settings.fontStep)),
        "books" to JsonObject(books.mapValues { (_, entry) -> entry.toJson() }),
    ),
)

private fun BookEntry.toJson() = jsonObject(
    ENTRY_FIELDS,
    extras,
    "title" to JsonPrimitive(title),
    "file" to file?.let(::JsonPrimitive),
    "onShelf" to JsonPrimitive(onShelf),
    "finished" to JsonPrimitive(finished),
    "author" to author?.let(::JsonPrimitive),
    "source" to source?.let(::JsonPrimitive),
    "addedAt" to addedAt?.let(::JsonPrimitive),
    "place" to place?.let {
        jsonObject(
            PLACE_FIELDS,
            it.extras,
            "spineId" to JsonPrimitive(it.spineId),
            "block" to JsonPrimitive(it.block),
            "offset" to JsonPrimitive(it.offset),
            "snippet" to JsonPrimitive(it.snippet),
            "updatedAt" to JsonPrimitive(it.updatedAt),
        )
    },
)

private fun jsonObject(known: Set<String>, extras: Map<String, JsonElement>, vararg fields: Pair<String, JsonElement?>) =
    JsonObject(fields.mapNotNull { (name, value) -> value?.let { name to it } }.toMap() + extras.filterKeys { it !in known })

/**
 * Parses the file's text. A known field that is absent or null takes its default, which is what keeps
 * a file from a newer build readable. A known field of the wrong type is corruption and fails, as does
 * anything that isn't a JSON object at the top. A Book's file that isn't a plain name inside filesDir
 * reads as missing (null), so nothing outside filesDir is ever deleted and the other Books stay.
 * Never throws.
 */
fun decodeReadingData(text: String): Result<ReadingData> = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: corrupt("top level")
    ReadingData(
        schemaVersion = root.int("schemaVersion") ?: CURRENT_SCHEMA,
        books = root.obj("books")?.mapValues { (id, entry) -> (entry as? JsonObject ?: corrupt("books.$id")).toEntry(id) }.orEmpty(),
        settings = root.obj("settings")?.let { Settings(it.int("fontStep") ?: DEFAULT_FONT_STEP, it.unknown(SETTINGS_FIELDS)) } ?: Settings(),
        extras = root.unknown(TOP_FIELDS),
    )
}

private fun JsonObject.toEntry(id: String) = BookEntry(
    title = string("title").orEmpty(),
    file = bookFile(id),
    place = obj("place")?.let {
        Place(
            spineId = it.string("spineId").orEmpty(),
            block = it.int("block") ?: 0,
            offset = it.int("offset") ?: 0,
            snippet = it.string("snippet").orEmpty(),
            updatedAt = it.long("updatedAt") ?: 0,
            extras = it.unknown(PLACE_FIELDS),
        )
    },
    finished = boolean("finished") ?: false,
    onShelf = boolean("onShelf") ?: false,
    author = string("author"),
    source = string("source"),
    addedAt = long("addedAt"),
    extras = unknown(ENTRY_FIELDS),
)

/** The Book's file, or null when it has none or it isn't a plain name inside filesDir (logged). */
private fun JsonObject.bookFile(id: String): String? {
    val name = string("file") ?: return null
    if (isPlainFileName(name)) return name
    Log.w(TAG, "reading data: books.$id.file isn't a plain name inside filesDir; reading it as missing")
    return null
}

/**
 * Whether [name] names a file directly inside filesDir, as [BookEntry.file] must: a removal deletes
 * that file, so a path separator, "." or ".." could reach outside it.
 */
private fun isPlainFileName(name: String) =
    name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

private class CorruptReadingData(field: String) : Exception("reading data: $field has the wrong type")

private fun corrupt(field: String): Nothing = throw CorruptReadingData(field)

private fun JsonObject.unknown(known: Set<String>) = filterKeys { it !in known }

private fun JsonObject.present(name: String): JsonElement? = get(name)?.takeUnless { it is JsonNull }

private fun JsonObject.literal(name: String): JsonPrimitive? =
    present(name)?.let { it as? JsonPrimitive ?: corrupt(name) }?.also { if (it.isString) corrupt(name) }

private fun JsonObject.int(name: String): Int? = literal(name)?.let { it.intOrNull ?: corrupt(name) }

private fun JsonObject.long(name: String): Long? = literal(name)?.let { it.longOrNull ?: corrupt(name) }

private fun JsonObject.boolean(name: String): Boolean? = literal(name)?.let { it.booleanOrNull ?: corrupt(name) }

private fun JsonObject.string(name: String): String? =
    present(name)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: corrupt(name) }

private fun JsonObject.obj(name: String): JsonObject? = present(name)?.let { it as? JsonObject ?: corrupt(name) }
