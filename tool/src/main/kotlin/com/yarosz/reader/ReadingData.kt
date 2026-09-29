package com.yarosz.reader

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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
 *
 * Fields added since schema 1: `catalogues` (N3) and a Place's `progress` (N4). `progress` is how far
 * through the Book the Place is, for the Shelf's percent; the minutes left in a Chapter are never
 * stored, because they depend on the reader's speed.
 */
const val CURRENT_SCHEMA = 1

/** Characters of Spine item text kept with a Place so it can be re-found after offsets shift (ADR 0002). */
const val SNIPPET_CHARS = 40

/**
 * Where the reader is, in memory: [item] indexes the Book's Spine items, and [char] is an offset into that
 * one's [SpineItem.text]. Ordered as the text is read.
 */
data class SpinePoint(val item: Int, val char: Int) : Comparable<SpinePoint> {
    override fun compareTo(other: SpinePoint) = compareValuesBy(this, other, { it.item }, { it.char })
}

/**
 * A Place as stored (ADR 0002): the Spine item, the block within it, the offset within that
 * block, and the first [SNIPPET_CHARS] characters of text there. [updatedAt] is epoch millis; the
 * newer Place wins a [merge]. Relayout never rewrites a Place; turning a Page records a new one, and
 * setting or clearing Finished re-stamps it ([withFinished]). [progress] is the share (0–1) of the
 * Book's text characters before the Place, through the whole Book, front and back matter included,
 * rounded to 4 decimals ([progressAt]); null in a Place saved before N4, and when the stored value isn't
 * a number in 0–1. It describes this Place only, so it is written with every Place. Progress within a
 * Chapter (its minutes left) is never stored.
 */
data class Place(
    val spineId: String,
    val block: Int,
    val offset: Int,
    val snippet: String,
    val updatedAt: Long,
    val progress: Double? = null,
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
data class Book(
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
 * The reader's last change to one Catalogue, keyed by its [CatalogueKey] in [ReadingData.catalogues]:
 * added (with the [name] it shows under) or removed. A Catalogue the Tool ships with needs a record
 * only once the reader removes it, and keeps its record, with [removed] false, once added back: the
 * record is what outlives a merge with a file that still has the removal. [updatedAt] is epoch
 * millis; the newer record wins a [merge]. [name] is null for a Catalogue the Tool ships with, which
 * takes its shipped name. [url] is where the Catalogue is fetched when that isn't its key's URL (an
 * address typed with a trailing slash or a capital in its host), else null. Decoding reads it from
 * the record's `url` when that is a URL of the key's Catalogue, else from the key as the file wrote
 * it when that isn't the key's URL, so an older file's slashed key keeps its address. It is never
 * [HttpsUrl.upgraded] (see [HttpsUrl.plain]).
 */
data class CatalogueRecord(
    val name: String?,
    val url: HttpsUrl?,
    val removed: Boolean,
    val updatedAt: Long,
    val extras: Map<String, JsonElement> = emptyMap(),
)

/**
 * Everything the Reader keeps between launches: each Book's Place and Shelf state, the settings, and
 * the reader's changes to the list of Catalogues ([catalogues], by URL; see [catalogueList]). Every
 * level carries the JSON fields this build doesn't know in `extras` (see [CURRENT_SCHEMA]).
 */
data class ReadingData(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val books: Map<String, Book> = emptyMap(),
    val settings: Settings = Settings(),
    val catalogues: Map<CatalogueKey, CatalogueRecord> = emptyMap(),
    val extras: Map<String, JsonElement> = emptyMap(),
)

/** The title stored for the Book in [file], a name inside filesDir, or null when none is stored. */
fun ReadingData.storedTitle(file: String): String? = books.values.firstOrNull { it.file == file }?.title?.takeIf { it.isNotBlank() }

/**
 * Puts the Book on the Shelf, adding it when it isn't stored, with [title] and [file] current. A
 * null [author] or [source] keeps the one stored. [now] becomes [Book.addedAt] when the Book
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
    val old = books[identifier] ?: Book(title = title, file = file, place = null, finished = false, onShelf = false)
    val book = old.copy(
        title = title,
        file = file,
        onShelf = true,
        author = author ?: old.author,
        source = source ?: old.source,
        addedAt = if (!old.onShelf && now != null) now else old.addedAt,
    )
    return copy(books = books + (identifier to book))
}

/**
 * Takes the Book off the Shelf: its file is forgotten (the caller deletes it) and everything else is
 * kept, so adding it again brings back its Place.
 */
fun ReadingData.unshelve(identifier: String): ReadingData {
    val book = books[identifier] ?: return this
    return copy(books = books + (identifier to book.copy(file = null, onShelf = false)))
}

/**
 * Moves the Book at [from] to [to], as when downloading it again brings a package that declares a
 * new identifier (Calibre mints one on every conversion): its Place, finished flag, date added,
 * title, source and unknown fields go to [to], replacing a Book there that is off the Shelf. A
 * Book already on the Shelf at [to] stays as it is except for its Place, which becomes the newer of
 * the two (with its finished flag; a tie keeps its own). [from] stays behind off the Shelf with no
 * file, the state a removal leaves, rather than being dropped: [merge] keeps every Book the file on
 * disk has, so a dropped key would come back on the next save.
 */
fun ReadingData.moveBook(from: String, to: String): ReadingData {
    val book = books[from]?.takeIf { from != to } ?: return this
    val moved = books[to]?.takeIf { it.onShelf }?.let { target ->
        val reading = if (book.placeTime > target.placeTime) book else target
        target.copy(place = reading.place, finished = reading.finished)
    } ?: book
    return copy(books = books + (to to moved) + (from to book.copy(file = null, onShelf = false)))
}

/**
 * Records [place] for a Book already in [ReadingData.books]; an unknown Book is left out. Its
 * [Place.updatedAt] is kept later than the Book's previous Place, so a clock stepped backwards can't
 * make the newest Place lose a [merge].
 */
fun ReadingData.withPlace(identifier: String, place: Place): ReadingData {
    val book = books[identifier] ?: return this
    val updatedAt = book.place?.let { maxOf(place.updatedAt, it.updatedAt + 1) } ?: place.updatedAt
    return copy(books = books + (identifier to book.copy(place = place.copy(updatedAt = updatedAt))))
}

/**
 * Records [place] as [withPlace] does, and the Book's [Book.finished] with it. Setting or clearing
 * Finished always re-stamps the Place, even when [place] is where the Book already is, so the change
 * is the newer side of a [merge], which carries the flag with the Place.
 */
fun ReadingData.withFinished(identifier: String, finished: Boolean, place: Place): ReadingData {
    val placed = withPlace(identifier, place)
    val book = placed.books[identifier] ?: return this
    return placed.copy(books = placed.books + (identifier to book.copy(finished = finished)))
}

/**
 * The Place at [textOffset], clamped to the Spine item. A separating '\n' belongs to the block before it,
 * as in [SpineItem.kindAt]. The snippet ends on a code-point boundary: a split surrogate pair would not
 * survive UTF-8.
 */
fun SpineItem.placeOf(textOffset: Int, now: Long): Place {
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
 * at the nearest occurrence of the snippet without a leading run of spaces that holds a line break and
 * with each run of line breaks as one (a Place saved before blocks dropped such runs at their ends), else
 * at the nearest occurrence of that snippet cut at its first line break, the rest of the Place's own
 * block (a parser change put a new block within the snippet's reach after it, so its block index is stale
 * too), else at the start of its block, or of its Spine item when the block is gone. An occurrence that
 * starts on the separator before a block, for a Place no further into its block than the line breaks its
 * snippet starts with, is taken past them: that Place was saved inside a heading's leading line breaks,
 * which no block has now, so it lands on the heading, not on the end of the block before. That holds for
 * every Place in a run of up to two line breaks and the first half of a longer one; a current Place on a
 * separator keeps its spot. Null only when no Spine item has the Place's Spine item.
 */
fun OpenBook.resolve(place: Place): SpinePoint? {
    val index = spineItems.indexOfFirst { it.spineId == place.spineId }.takeIf { it >= 0 } ?: return null
    val text = spineItems[index].text
    val blockStart = spineItems[index].blockStarts.getOrNull(place.block)
    val expected = blockStart?.plus(place.offset)?.takeIf { it in 0..text.length }
    if (expected != null && text.startsWith(place.snippet, expected)) return SpinePoint(index, expected)
    val anchor = expected ?: blockStart ?: 0
    fun nearest(snippet: String) = generateSequence(text.indexOf(snippet).takeIf { it >= 0 }) { from ->
        text.indexOf(snippet, from + 1).takeIf { it >= 0 }
    }.minByOrNull { abs(it - anchor) }
    val lead = place.snippet.takeWhile { it in TRIMMED }
    val normalised = (if ('\n' in lead) place.snippet.substring(lead.length) else place.snippet).replace(LINE_BREAK_RUN, "\n")
    for (snippet in listOf(place.snippet, normalised).distinct()) {
        if (snippet.isEmpty()) continue
        val found = nearest(snippet) ?: continue
        val lineBreaks = snippet.length - snippet.trimStart('\n').length
        val onSeparator = text[found] == '\n' && spineItems[index].blockStarts.binarySearch(found + 1) >= 0
        return SpinePoint(index, found + if (onSeparator && place.offset <= lineBreaks) lineBreaks else 0)
    }
    val ownBlock = normalised.substringBefore('\n')
    if (ownBlock.isNotEmpty() && ownBlock.length < normalised.length) nearest(ownBlock)?.let { return SpinePoint(index, it) }
    return SpinePoint(index, blockStart ?: 0)
}

private val LINE_BREAK_RUN = Regex("\n+")

/**
 * Combines the file on disk with this process's data before a save. What it protects: the Books and
 * the unknown fields another build wrote, which [mine] doesn't carry, and each Book's newest Place.
 * Only the Place is independent of the order of saves; fontStep, a Book's file, Shelf state, and
 * title come from [mine], so the save that runs last decides them (see [ReadingSaver], which makes
 * that the save with the newest data). Idempotent: merge(x, x) == x, and merging the same [mine]
 * twice changes nothing.
 * - schemaVersion: the higher, so an older build never downgrades the file.
 * - books: both sides' Books. For a Book on both sides, see [mergeBook].
 * - settings: fontStep from [mine]; unknown fields from both, [mine] winning a clash.
 * - catalogues: both sides' records; for a Catalogue on both sides, see [mergeRecord]. A removal is a
 *   record too, so it survives a merge with a file that still lists the Catalogue, and adding it
 *   back later wins over the removal the same way.
 * - unknown top-level fields: from both, [mine] winning a clash.
 */
fun merge(disk: ReadingData, mine: ReadingData): ReadingData = ReadingData(
    schemaVersion = maxOf(disk.schemaVersion, mine.schemaVersion),
    books = (disk.books.keys + mine.books.keys).associateWith { id ->
        val d = disk.books[id]
        val m = mine.books[id]
        if (d != null && m != null) mergeBook(d, m) else m ?: d!!
    },
    settings = Settings(mine.settings.fontStep, disk.settings.extras + mine.settings.extras),
    catalogues = (disk.catalogues.keys + mine.catalogues.keys).associateWith { key ->
        val d = disk.catalogues[key]
        val m = mine.catalogues[key]
        if (d != null && m != null) mergeRecord(d, m) else m ?: d!!
    },
    extras = disk.extras + mine.extras,
)

/**
 * The newer Place wins, and brings its [Book.finished] with it: a missing Place counts as oldest
 * and a tie goes to [mine]. [Book.file], [Book.onShelf] and [Book.addedAt] come from
 * [mine], because this process owns the files. The title is [mine]'s unless blank; the author and
 * source are [mine]'s unless null. Unknown fields come from both, [mine] winning a clash.
 */
private fun mergeBook(disk: Book, mine: Book): Book {
    val reading = if (disk.placeTime > mine.placeTime) disk else mine
    return Book(
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

/**
 * The later record wins with its known fields, and the unknown fields come from both, the winner's
 * taking a clash, as for a Book. Which record wins doesn't depend on the order of saves, because the
 * list is the reader's, not this process's, and a newer build or an imported file (N7) may have
 * changed it: the newer [CatalogueRecord.updatedAt] wins, and at a tie a removal beats an addition
 * (one tap on "Add back" undoes a removal, while a lost removal brings back a Catalogue the reader
 * dismissed), then the name and URL decide. Only records equal in every known field fall to [mine].
 */
internal fun mergeRecord(disk: CatalogueRecord, mine: CatalogueRecord): CatalogueRecord {
    val (winner, loser) = if (RECORD_ORDER.compare(disk, mine) > 0) disk to mine else mine to disk
    return winner.copy(extras = loser.extras + winner.extras)
}

private val RECORD_ORDER = compareBy<CatalogueRecord> { it.updatedAt }
    .thenBy { it.removed }
    .thenBy(nullsFirst()) { it.name }
    .thenBy(nullsFirst()) { it.url?.value }

/** When the Book's Place was recorded; a Book with no Place counts as oldest. */
private val Book.placeTime get() = place?.updatedAt ?: Long.MIN_VALUE

private val prettyJson = Json { prettyPrint = true }

private val TOP_FIELDS = setOf("schemaVersion", "settings", "books", "catalogues")
private val SETTINGS_FIELDS = setOf("fontStep")
private val ENTRY_FIELDS = setOf("title", "file", "place", "finished", "onShelf", "author", "source", "addedAt")
private val PLACE_FIELDS = setOf("spineId", "block", "offset", "snippet", "updatedAt", "progress")
private val CATALOGUE_FIELDS = setOf("name", "url", "removed", "updatedAt")

/**
 * The file's text. Absent Places, progress, files, authors, sources, dates, names and URLs are
 * omitted, and so is an empty `catalogues`; unknown fields are written back as they came.
 */
fun ReadingData.encode(): String = prettyJson.encodeToString(
    JsonElement.serializer(),
    jsonObject(
        TOP_FIELDS,
        extras,
        "schemaVersion" to JsonPrimitive(schemaVersion),
        "settings" to jsonObject(SETTINGS_FIELDS, settings.extras, "fontStep" to JsonPrimitive(settings.fontStep)),
        "books" to JsonObject(books.mapValues { (_, book) -> book.toJson() }),
        "catalogues" to catalogues.takeIf { it.isNotEmpty() }?.let { records ->
            JsonObject(
                records.entries.associate { (key, record) ->
                    key.value to jsonObject(
                        CATALOGUE_FIELDS,
                        record.extras,
                        "name" to record.name?.let(::JsonPrimitive),
                        "url" to record.url?.let { JsonPrimitive(it.value) },
                        "removed" to JsonPrimitive(record.removed),
                        "updatedAt" to JsonPrimitive(record.updatedAt),
                    )
                },
            )
        },
    ),
)

private fun Book.toJson() = jsonObject(
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
            "progress" to it.progress?.let(::JsonPrimitive),
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
 * A Catalogue is read under its [CatalogueKey], so a key written as http:// or with a trailing slash
 * is the Catalogue the list shows and can remove; two keys for one Catalogue merge as a save does
 * ([mergeRecord]). A key that isn't an http(s) URL reads as missing (logged), and the other
 * Catalogues stay; a record's `url` that isn't a URL of its key's Catalogue reads as missing too.
 * Never throws.
 */
fun decodeReadingData(text: String): Result<ReadingData> = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: corrupt("top level")
    ReadingData(
        schemaVersion = root.int("schemaVersion") ?: CURRENT_SCHEMA,
        books = root.obj("books")?.mapValues { (id, json) -> (json as? JsonObject ?: corrupt("books.$id")).toBook(id) }.orEmpty(),
        settings = root.obj("settings")?.let { Settings(it.int("fontStep") ?: DEFAULT_FONT_STEP, it.unknown(SETTINGS_FIELDS)) } ?: Settings(),
        catalogues = root.obj("catalogues")?.let(::catalogueRecords).orEmpty(),
        extras = root.unknown(TOP_FIELDS),
    )
}

private fun catalogueRecords(records: JsonObject): Map<CatalogueKey, CatalogueRecord> =
    records.entries.fold(emptyMap()) { read, (raw, value) ->
        val fields = value as? JsonObject ?: corrupt("catalogues.$raw")
        val written = HttpsUrl.parse(raw)
        if (written == null) {
            Log.w(TAG, "reading data: a catalogues key isn't an https URL; reading it as missing")
            return@fold read
        }
        val key = written.catalogueKey
        val record = CatalogueRecord(
            name = fields.string("name"),
            url = (fields.string("url")?.let { HttpsUrl.parse(it) }?.takeIf { it.catalogueKey == key } ?: written.takeIf { it != key.url })?.plain,
            removed = fields.boolean("removed") ?: false,
            updatedAt = fields.long("updatedAt") ?: 0,
            extras = fields.unknown(CATALOGUE_FIELDS),
        )
        read + (key to (read[key]?.let { mergeRecord(it, record) } ?: record))
    }

private fun JsonObject.toBook(id: String) = Book(
    title = string("title").orEmpty(),
    file = bookFile(id),
    place = obj("place")?.let {
        Place(
            spineId = it.string("spineId").orEmpty(),
            block = it.int("block") ?: 0,
            offset = it.int("offset") ?: 0,
            snippet = it.string("snippet").orEmpty(),
            updatedAt = it.long("updatedAt") ?: 0,
            progress = it.double("progress")?.takeIf { p -> p in 0.0..1.0 },
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
 * Whether [name] names a file directly inside filesDir, as [Book.file] must: a removal deletes
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

private fun JsonObject.double(name: String): Double? = literal(name)?.let { it.doubleOrNull ?: corrupt(name) }

private fun JsonObject.boolean(name: String): Boolean? = literal(name)?.let { it.booleanOrNull ?: corrupt(name) }

private fun JsonObject.string(name: String): String? =
    present(name)?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: corrupt(name) }

private fun JsonObject.obj(name: String): JsonObject? = present(name)?.let { it as? JsonObject ?: corrupt(name) }
