package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ReadingDataTest {

    @Test
    fun `reading data survives encode and decode exactly, unknown fields included`() = forAll { rnd ->
        val data = randomData(rnd)
        assertEquals(data, decodeReadingData(data.encode()).getOrThrow())
    }

    @Test
    fun `malformed or mistyped input fails without throwing`() {
        val bad = listOf(
            "",
            "{\"schemaVersion\": 1, \"books\": {",
            "[]",
            "\"text\"",
            "{\"books\": []}",
            "{\"settings\": {\"fontStep\": \"x\"}}",
            "{\"schemaVersion\": true}",
            "{\"schemaVersion\": 1.5}",
            "{\"books\": {\"id\": 3}}",
            "{\"books\": {\"id\": {\"place\": {\"block\": \"3\"}}}}",
            "{\"books\": {\"id\": {\"onShelf\": \"yes\"}}}",
        )
        bad.forEach { text -> assertTrue(decodeReadingData(text).isFailure, text) }
    }

    @Test
    fun `absent or null known fields take their defaults`() {
        val data = decodeReadingData("{\"books\": {\"id\": {\"file\": null}}, \"settings\": null}").getOrThrow()
        assertEquals(ReadingData(books = mapOf("id" to Book("", null, null, finished = false, onShelf = false))), data)
    }

    @Test
    fun `the newer Place wins whichever side it is on`() {
        val older = book(place = place(updatedAt = 1))
        val newer = book(place = place(updatedAt = 2, offset = 9))
        assertEquals(newer.place, merge(data("b" to older), data("b" to newer)).books.getValue("b").place)
        assertEquals(newer.place, merge(data("b" to newer), data("b" to older)).books.getValue("b").place)
        assertEquals(older.place, merge(data("b" to book(place = null)), data("b" to older)).books.getValue("b").place)
        assertEquals(older.place, merge(data("b" to older), data("b" to book(place = null))).books.getValue("b").place)
    }

    @Test
    fun `a tie goes to mine`() {
        val disk = book(place = place(updatedAt = 5, offset = 1))
        val mine = book(place = place(updatedAt = 5, offset = 2))
        assertEquals(mine.place, merge(data("b" to disk), data("b" to mine)).books.getValue("b").place)
    }

    @Test
    fun `finished travels with the newer Place`() {
        val disk = book(place = place(updatedAt = 9), finished = true)
        val mine = book(place = place(updatedAt = 1), finished = false)
        assertTrue(merge(data("b" to disk), data("b" to mine)).books.getValue("b").finished)
    }

    @Test
    fun `file and Shelf state come from mine`() {
        val disk = book(place = place(updatedAt = 9), file = "old.epub", onShelf = true)
        val mine = book(place = null, file = "new.epub", onShelf = false)
        val merged = merge(data("b" to disk), data("b" to mine)).books.getValue("b")
        assertEquals("new.epub", merged.file)
        assertEquals(false, merged.onShelf)
    }

    @Test
    fun `title is mine unless blank`() {
        assertEquals("Mine", merge(data("b" to book(title = "Disk")), data("b" to book(title = "Mine"))).books.getValue("b").title)
        assertEquals("Disk", merge(data("b" to book(title = "Disk")), data("b" to book(title = " "))).books.getValue("b").title)
    }

    @Test
    fun `both sides' Books and unknown fields are kept, and schemaVersion never goes down`() {
        val disk = ReadingData(
            schemaVersion = 3,
            books = mapOf("a" to book(extras = mapOf("d" to JsonPrimitive(1), "both" to JsonPrimitive("disk")))),
            settings = Settings(fontStep = 4, extras = mapOf("theme" to JsonPrimitive("dark"))),
            extras = mapOf("sync" to JsonPrimitive(true)),
        )
        val mine = ReadingData(
            books = mapOf("a" to book(extras = mapOf("both" to JsonPrimitive("mine"))), "b" to book()),
            settings = Settings(fontStep = 0, extras = mapOf("margin" to JsonPrimitive(2))),
            extras = mapOf("export" to JsonNull),
        )
        val merged = merge(disk, mine)
        assertEquals(3, merged.schemaVersion)
        assertEquals(setOf("a", "b"), merged.books.keys)
        assertEquals(mapOf("d" to JsonPrimitive(1), "both" to JsonPrimitive("mine")), merged.books.getValue("a").extras)
        assertEquals(Settings(0, mapOf("theme" to JsonPrimitive("dark"), "margin" to JsonPrimitive(2))), merged.settings)
        assertEquals(mapOf("sync" to JsonPrimitive(true), "export" to JsonNull), merged.extras)
    }

    @Test
    fun `merge is idempotent`() = forAll { rnd ->
        val disk = randomData(rnd)
        val mine = overlapping(disk, rnd)
        assertEquals(mine, merge(mine, mine))
        val once = merge(disk, mine)
        assertEquals(once, merge(once, mine))
    }

    @Test
    fun `a Place resolves back to the offset it was taken at`() = forAll(runs = 300) { rnd ->
        val book = randomBook(rnd)
        val index = rnd.nextInt(book.spineItems.size)
        val spineItem = book.spineItems[index]
        for (offset in 0..spineItem.text.length) {
            assertEquals(SpinePoint(index, offset), book.resolve(spineItem.placeOf(offset, 0)))
        }
    }

    @Test
    fun `a looked-up Place yields the same Place again, so a font round trip lands on the same Page`() = forAll { rnd ->
        val book = randomBook(rnd)
        val index = rnd.nextInt(book.spineItems.size)
        val spineItem = book.spineItems[index]
        val place = spineItem.placeOf(rnd.nextInt(-5, spineItem.text.length + 5), rnd.nextLong())
        val found = book.resolve(place)!!
        assertEquals(place, book.spineItems[found.item].placeOf(found.char, place.updatedAt))
    }

    @Test
    fun `a Place is re-found by its snippet when a new edition shifts the text before it`() = forAll { rnd ->
        val spineItem = randomSpineItem(rnd, "ch")
        if (spineItem.text.length <= SNIPPET_CHARS) return@forAll
        val at = rnd.nextInt(0, spineItem.text.length - SNIPPET_CHARS + 1)
        val place = spineItem.placeOf(at, 0)
        val (edition, moved) = shiftedBefore(spineItem, place, rnd)
        assertEquals(SpinePoint(0, moved), OpenBook("id", "", listOf(edition)).resolve(place))
    }

    @Test
    fun `a Place whose snippet is gone falls back to its block's start, else the Spine item's`() = forAll { rnd ->
        val spineItem = randomSpineItem(rnd, "ch")
        val place = spineItem.placeOf(rnd.nextInt(0, spineItem.text.length), 0)
        val replaced = SpineItem("ch", List(rnd.nextInt(1, 20)) { Block(BlockKind.Paragraph, digits(rnd, rnd.nextInt(1, 300))) })
        val expected = replaced.blockStarts.getOrNull(place.block) ?: 0
        assertEquals(SpinePoint(0, expected), OpenBook("id", "", listOf(replaced)).resolve(place))
    }

    @Test
    fun `a repeated line resolves to the occurrence nearest the Place, not the first`() {
        val refrain = "Beware the Jabberwock, my son! The jaws"
        val verse = Block(BlockKind.Verse, List(5) { refrain }.joinToString("\n"))
        val heading = Block(BlockKind.Heading, "Jabberwocky")
        val spineItem = SpineItem("c", listOf(heading, verse))
        val third = spineItem.blockStarts[1] + 2 * (refrain.length + 1)
        val place = spineItem.placeOf(third, 0)
        assertEquals(4, Regex(Regex.escape(place.snippet)).findAll(spineItem.text).count())

        val note = Block(BlockKind.Paragraph, "(A poem.)")
        val longer = OpenBook("id", "", listOf(SpineItem("c", listOf(heading, note, verse))))
        assertEquals(SpinePoint(0, third + note.text.length + 1), longer.resolve(place))

        val cut = 8
        val trimmed = Block(BlockKind.Verse, refrain.dropLast(cut) + "\n" + List(4) { refrain }.joinToString("\n"))
        val shorter = OpenBook("id", "", listOf(SpineItem("c", listOf(heading, trimmed))))
        assertEquals(SpinePoint(0, third - cut), shorter.resolve(place))
    }

    @Test
    fun `a Place saved at a heading's leading line breaks or caption finds the heading's text after the parser dropped them`() {
        val before = Block(BlockKind.Paragraph, "He was the tallest.")
        val old = SpineItem("c", listOf(
            before,
            Block(BlockKind.Heading, "\nShe rode a grey mare.\n\nCHAPTER II."),
            Block(BlockKind.Paragraph, "Long ago."),
            Block(BlockKind.Heading, "\nMrs Bennet and her two youngest girls.\n\nCHAPTER III."),
            Block(BlockKind.Paragraph, "Not all that Mrs Bennet could say."),
            Block(BlockKind.Heading, "\n\nCHAPTER IV."),
            Block(BlockKind.Paragraph, "When Jane and Elizabeth were alone."),
            Block(BlockKind.Heading, "\nCHAPTER V."),
            Block(BlockKind.Paragraph, "Within a short walk of Longbourn."),
        ))
        val new = SpineItem("c", listOf(
            before,
            Block(BlockKind.Caption, "She rode a grey mare."),
            Block(BlockKind.Heading, "CHAPTER II."),
            Block(BlockKind.Paragraph, "Long ago."),
            Block(BlockKind.Caption, "Mrs Bennet and her two youngest girls."),
            Block(BlockKind.Heading, "CHAPTER III."),
            Block(BlockKind.Paragraph, "Not all that Mrs Bennet could say."),
            Block(BlockKind.Heading, "CHAPTER IV."),
            Block(BlockKind.Paragraph, "When Jane and Elizabeth were alone."),
            Block(BlockKind.Heading, "CHAPTER V."),
            Block(BlockKind.Paragraph, "Within a short walk of Longbourn."),
        ))
        val book = OpenBook("id", "", listOf(new))
        for ((oldBlock, newBlock) in listOf(1 to 1, 3 to 4, 5 to 7, 7 to 9)) {
            val start = new.blockStarts[newBlock]
            val lineBreaks = old.blocks[oldBlock].text.takeWhile { it == '\n' }.length
            for (offset in 0..2) {
                val expected = start + maxOf(offset - lineBreaks, 0)
                assertEquals(SpinePoint(0, expected), book.resolve(old.placeOf(old.blockStarts[oldBlock] + offset, 0)), "block $oldBlock offset $offset")
            }
            assertEquals(SpinePoint(0, start - 12), book.resolve(old.placeOf(old.blockStarts[oldBlock] - 12, 0)))
        }
    }

    @Test
    fun `a Place in a Spine item the Book no longer has resolves to nothing`() {
        val book = randomBook(Random(1))
        assertNull(book.resolve(Place("gone.xhtml", 0, 0, "", 0)))
    }

    @Test
    fun `a snippet never ends inside a surrogate pair`() {
        val spineItem = SpineItem("c", listOf(Block(BlockKind.Paragraph, "x".repeat(39) + "😀" + "y".repeat(5))))
        assertEquals("x".repeat(39), spineItem.placeOf(0, 0).snippet)
        assertEquals("x".repeat(38) + "😀", spineItem.placeOf(1, 0).snippet)
        assertEquals(SpinePoint(0, 3), OpenBook("id", "", listOf(spineItem)).resolve(spineItem.placeOf(3, 0)))
    }

    @Test
    fun `a Place's updatedAt stays after the Book's previous Place, so a clock step backwards can't lose a turn`() {
        val first = ReadingData().shelve("id", "T", "t.epub").withPlace("id", place(updatedAt = 100))
        val second = first.withPlace("id", place(updatedAt = 50, offset = 9))
        assertEquals(101, second.books.getValue("id").place!!.updatedAt)
        assertEquals(second.books.getValue("id").place, merge(first, second).books.getValue("id").place)
        assertEquals(200, second.withPlace("id", place(updatedAt = 200)).books.getValue("id").place!!.updatedAt)
    }

    @Test
    fun `a Place's block and snippet follow the text`() {
        val spineItem = SpineItem("c", listOf(Block(BlockKind.Heading, "One"), Block(BlockKind.Paragraph, "Two words")))
        assertEquals(Place("c", 0, 3, "\nTwo words", 7), spineItem.placeOf(3, 7))
        assertEquals(Place("c", 1, 4, "words", 7), spineItem.placeOf(8, 7))
        assertEquals(Place("c", 1, 9, "", 7), spineItem.placeOf(99, 7))
        assertEquals(Place("c", 0, 0, "", 7), SpineItem("c", emptyList()).placeOf(4, 7))
    }

    @Test
    fun `shelving adds a Book once and keeps its Place`() {
        val shelved = ReadingData().shelve("id", "Alice", "alice.epub")
        assertEquals(Book("Alice", "alice.epub", null, finished = false, onShelf = true), shelved.books.getValue("id"))
        val placed = shelved.withPlace("id", place(updatedAt = 3))
        assertEquals(place(updatedAt = 3), placed.shelve("id", "Alice", "alice.epub").books.getValue("id").place)
        assertEquals(placed, placed.withPlace("unknown", place(updatedAt = 4)))
    }

    @Test
    fun `a Book's source, author and date added round-trip, and a file from before them has none`() {
        val book = Book("Alice", "a.epub", null, finished = false, onShelf = true, author = "Lewis Carroll", source = "https://books.example.org/a.epub", addedAt = 42)
        val text = ReadingData(books = mapOf("id" to book)).encode()
        assertTrue("\"source\": \"https://books.example.org/a.epub\"" in text, text)
        assertEquals(book, decodeReadingData(text).getOrThrow().books.getValue("id"))
        val older = decodeReadingData("""{"schemaVersion": 1, "books": {"id": {"title": "Alice", "file": "alice.epub", "onShelf": true}}}""").getOrThrow()
        assertEquals(Book("Alice", "alice.epub", null, finished = false, onShelf = true), older.books.getValue("id"))
        assertTrue(decodeReadingData("""{"books": {"id": {"source": 3}}}""").isFailure)
        assertTrue(decodeReadingData("""{"books": {"id": {"addedAt": "3"}}}""").isFailure)
    }

    @Test
    fun `shelving keeps a stored source and author, and dates only a Book that wasn't on the Shelf`() {
        val added = ReadingData().shelve("id", "Alice", "a.epub", author = "Lewis Carroll", source = "https://books.example.org/a.epub", now = 10)
        assertEquals(10, added.books.getValue("id").addedAt)
        val reopened = added.shelve("id", "Alice", "a.epub", now = 20).books.getValue("id")
        assertEquals(Triple("Lewis Carroll", "https://books.example.org/a.epub", 10L), Triple(reopened.author, reopened.source, reopened.addedAt))
        val removed = added.withPlace("id", place(updatedAt = 3)).unshelve("id")
        val expected = Book(
            "Alice", null, place(updatedAt = 3), finished = false, onShelf = false,
            author = "Lewis Carroll", source = "https://books.example.org/a.epub", addedAt = 10,
        )
        assertEquals(expected, removed.books.getValue("id"))
        val again = removed.shelve("id", "Alice", "a.epub", source = "https://books.example.org/b.epub", now = 30).books.getValue("id")
        assertEquals(Triple(place(updatedAt = 3), "https://books.example.org/b.epub", 30L), Triple(again.place, again.source, again.addedAt))
        assertEquals(removed, removed.unshelve("unknown"))
    }

    @Test
    fun `a stored file that isn't a plain name inside filesDir reads as missing, and the other Books stay`() {
        val other = book(title = "Other", file = "other.epub", place = place(updatedAt = 3))
        listOf("../reading-data.json", "a/b.epub", "/data/x.epub", "a\\b.epub", ".", "..", "", "a\u0000.epub").forEach { name ->
            val bad = book(file = name, place = place(updatedAt = 5), onShelf = true)
            val text = ReadingData(books = mapOf("id" to bad, "other" to other)).encode()
            assertEquals(mapOf("id" to bad.copy(file = null), "other" to other), decodeReadingData(text).getOrThrow().books, name)
        }
        assertTrue(decodeReadingData("""{"books": {"id": {"file": 3}}}""").isFailure)
        assertEquals("..a.epub", decodeReadingData(ReadingData(books = mapOf("id" to book(file = "..a.epub"))).encode()).getOrThrow().books.getValue("id").file)
    }

    @Test
    fun `moving a Book takes its Place, date, title and fields to the new identifier and leaves the old one off the Shelf`() {
        val old = book(title = "Stored", place = place(updatedAt = 5), finished = true).copy(addedAt = 3, source = "https://books.example.org/a.epub", extras = mapOf("x" to JsonPrimitive(1)))
        val data = data("urn:old" to old, "urn:other" to book(title = "Other"))
        val moved = data.moveBook("urn:old", "urn:new")
        assertEquals(old, moved.books.getValue("urn:new"))
        assertEquals(old.copy(file = null, onShelf = false), moved.books.getValue("urn:old"))
        assertEquals(data.books.getValue("urn:other"), moved.books.getValue("urn:other"))
        assertEquals(data, data.moveBook("urn:old", "urn:old"))
        assertEquals(data, data.moveBook("urn:unknown", "urn:new"))
        val merged = merge(data, moved)
        assertEquals(false, merged.books.getValue("urn:old").onShelf)
        assertEquals(true, merged.books.getValue("urn:new").onShelf)
        val offShelf = data("urn:old" to old, "urn:new" to book(title = "Gone", file = null, onShelf = false, place = place(updatedAt = 9)))
        assertEquals(old, offShelf.moveBook("urn:old", "urn:new").books.getValue("urn:new"))
    }

    @Test
    fun `moving a Book onto one already on the Shelf keeps that one's title and date, with the newer Place`() {
        val old = book(title = "Stored", place = place(updatedAt = 5), finished = true).copy(addedAt = 3)
        val there = book(title = "There", file = "there.epub", place = place(updatedAt = 4, offset = 7))
            .copy(addedAt = 8, source = "https://s.example.org/t.epub")
        val movedNewer = data("urn:old" to old, "urn:new" to there).moveBook("urn:old", "urn:new")
        assertEquals(there.copy(place = old.place, finished = true), movedNewer.books.getValue("urn:new"))
        assertEquals(old.copy(file = null, onShelf = false), movedNewer.books.getValue("urn:old"))
        val newerThere = there.copy(place = place(updatedAt = 6, offset = 7))
        val movedOlder = data("urn:old" to old, "urn:new" to newerThere).moveBook("urn:old", "urn:new")
        assertEquals(newerThere, movedOlder.books.getValue("urn:new"))
        assertEquals(old.copy(file = null, onShelf = false), movedOlder.books.getValue("urn:old"))
    }

    @Test
    fun `a merge takes the author and source from mine unless mine has none`() {
        val disk = book().copy(author = "Disk", source = "https://d.example.org/d.epub", addedAt = 1)
        val mine = book().copy(author = null, source = "https://m.example.org/m.epub", addedAt = 2)
        val merged = merge(data("b" to disk), data("b" to mine)).books.getValue("b")
        assertEquals(Triple("Disk", "https://m.example.org/m.epub", 2L), Triple(merged.author, merged.source, merged.addedAt))
    }

    @Test
    fun `a Place's progress round-trips with its unknown fields, reads as null when a Place from before N4 has none, and fails mistyped`() {
        val stored = place(updatedAt = 3).copy(progress = 0.4213, extras = mapOf("xNewer" to JsonPrimitive("kept")))
        assertEquals(stored, decodeReadingData(data("b" to book(place = stored)).encode()).getOrThrow().books.getValue("b").place)
        val old = decodeReadingData("{\"books\": {\"b\": {\"place\": {\"spineId\": \"c\", \"updatedAt\": 1}}}}").getOrThrow()
        assertNull(old.books.getValue("b").place?.progress)
        assertTrue(decodeReadingData("{\"books\": {\"b\": {\"place\": {\"progress\": \"0.5\"}}}}").isFailure)
    }

    @Test
    fun `a stored progress that isn't a number in 0 to 1 reads as null, and the rest of the Place stays`() {
        for (bad in listOf("NaN", "1.5", "-0.1")) {
            val decoded = decodeReadingData("{\"books\": {\"b\": {\"place\": {\"spineId\": \"c\", \"updatedAt\": 1, \"progress\": $bad}}}}").getOrThrow()
            assertEquals(Place("c", 0, 0, "", 1), decoded.books.getValue("b").place, bad)
        }
        val edge = decodeReadingData("{\"books\": {\"b\": {\"place\": {\"progress\": 1.0}}}}").getOrThrow()
        assertEquals(1.0, edge.books.getValue("b").place?.progress)
    }

    @Test
    fun `progress travels with the newer Place through a merge`() {
        val disk = book(place = place(updatedAt = 1).copy(progress = 0.1))
        val mine = book(place = place(updatedAt = 2, offset = 5).copy(progress = 0.2))
        assertEquals(0.2, merge(data("b" to disk), data("b" to mine)).books.getValue("b").place?.progress)
        assertEquals(0.2, merge(data("b" to mine), data("b" to disk)).books.getValue("b").place?.progress)
    }

    @Test
    fun `setting or clearing Finished re-stamps the same Place, so it survives a merge with an older disk copy`() {
        val reading = book(place = place(updatedAt = 10).copy(progress = 0.99))
        val disk = data("b" to reading)
        val finished = disk.withFinished("b", true, place(updatedAt = 10).copy(progress = 0.99))
        val stamped = finished.books.getValue("b")
        assertTrue(stamped.finished)
        assertEquals(reading.place?.copy(updatedAt = 11), stamped.place)
        assertTrue(merge(disk, finished).books.getValue("b").finished)
        assertTrue(merge(finished, disk).books.getValue("b").finished)
        val cleared = finished.withFinished("b", false, place(updatedAt = 10).copy(progress = 0.99))
        assertEquals(false, merge(finished, cleared).books.getValue("b").finished)
        assertEquals(false, merge(cleared, finished).books.getValue("b").finished)
        assertEquals(disk, disk.withFinished("unknown", true, place(updatedAt = 1)))
    }
}

private fun place(updatedAt: Long, offset: Int = 0) = Place("chapter-1.xhtml", 2, offset, "snippet", updatedAt)

private fun book(
    title: String = "T",
    file: String? = "t.epub",
    place: Place? = null,
    finished: Boolean = false,
    onShelf: Boolean = true,
    extras: Map<String, JsonElement> = emptyMap(),
) = Book(title, file, place, finished, onShelf, extras = extras)

private fun data(vararg books: Pair<String, Book>) = ReadingData(books = mapOf(*books))

private val TEXT_PIECES = listOf("a", "Z", "é", "中", "😀", " ", "\"", "\\", "\n", "\t", "\u0001", "/", "{", "}", "'", "0")

private fun randomString(rnd: Random, max: Int = 12) = buildString { repeat(rnd.nextInt(0, max)) { append(TEXT_PIECES.random(rnd)) } }

private fun randomJson(rnd: Random, depth: Int = 0): JsonElement = when (rnd.nextInt(if (depth > 2) 6 else 8)) {
    0 -> JsonNull
    1 -> JsonPrimitive(rnd.nextBoolean())
    2 -> JsonPrimitive(rnd.nextLong())
    3 -> JsonPrimitive(rnd.nextInt(-1_000, 1_000))
    4 -> JsonPrimitive(rnd.nextDouble(-1e9, 1e9))
    5 -> JsonPrimitive(randomString(rnd))
    6 -> JsonArray(List(rnd.nextInt(0, 4)) { randomJson(rnd, depth + 1) })
    else -> JsonObject(List(rnd.nextInt(0, 4)) { randomString(rnd) to randomJson(rnd, depth + 1) }.toMap())
}

private fun randomExtras(rnd: Random, known: Set<String>) =
    List(rnd.nextInt(0, 4)) { "x" + randomString(rnd) to randomJson(rnd) }.toMap().filterKeys { it !in known }

private fun randomPlace(rnd: Random) = Place(
    spineId = randomString(rnd),
    block = rnd.nextInt(0, 1_000),
    offset = rnd.nextInt(0, 10_000),
    snippet = randomString(rnd, SNIPPET_CHARS),
    updatedAt = rnd.nextLong(0, 4_000_000_000_000),
    progress = if (rnd.nextBoolean()) null else rnd.nextInt(0, 10_001) / 10_000.0,
    extras = randomExtras(rnd, setOf("spineId", "block", "offset", "snippet", "updatedAt", "progress")),
)

private fun randomEntry(rnd: Random) = Book(
    title = randomString(rnd),
    file = if (rnd.nextBoolean()) null else randomString(rnd).filter { it != '/' && it != '\\' } + ".epub",
    place = if (rnd.nextBoolean()) null else randomPlace(rnd),
    finished = rnd.nextBoolean(),
    onShelf = rnd.nextBoolean(),
    author = if (rnd.nextBoolean()) null else randomString(rnd),
    source = if (rnd.nextBoolean()) null else "https://example.org/" + randomString(rnd),
    addedAt = if (rnd.nextBoolean()) null else rnd.nextLong(0, 4_000_000_000_000),
    extras = randomExtras(rnd, setOf("title", "file", "place", "finished", "onShelf", "author", "source", "addedAt")),
)

private fun randomData(rnd: Random) = ReadingData(
    schemaVersion = rnd.nextInt(1, 4),
    books = List(rnd.nextInt(0, 5)) { "https://example.org/" + randomString(rnd) to randomEntry(rnd) }.toMap(),
    settings = Settings(rnd.nextInt(0, 5), randomExtras(rnd, setOf("fontStep"))),
    extras = randomExtras(rnd, setOf("schemaVersion", "settings", "books")),
)

/** Another process's view of [disk]: some of its Books with changed fields, plus Books of its own. */
private fun overlapping(disk: ReadingData, rnd: Random): ReadingData {
    val other = randomData(rnd)
    val shared = disk.books.keys.filter { rnd.nextBoolean() }.associateWith { randomEntry(rnd) }
    return other.copy(books = other.books + shared)
}

private fun word(rnd: Random) = buildString { repeat(rnd.nextInt(1, 9)) { append('a' + rnd.nextInt(26)) } }

private fun prose(rnd: Random, words: Int) =
    List(words) { word(rnd) }.joinToString(" ") { if (rnd.nextInt(12) == 0) "$it," else it }.replaceFirstChar { it.uppercase() } + "."

private fun digits(rnd: Random, length: Int) = buildString { repeat(length) { append("0123456789 "[rnd.nextInt(11)]) } }

/** A Spine item of varied prose, so any [SNIPPET_CHARS] of it occur once. */
private fun randomSpineItem(rnd: Random, spineId: String) = SpineItem(spineId, List(rnd.nextInt(1, 30)) { i ->
    val kind = if (i == 0) BlockKind.Heading else listOf(BlockKind.Paragraph, BlockKind.Paragraph, BlockKind.Verse, BlockKind.Caption).random(rnd)
    Block(kind, prose(rnd, if (kind == BlockKind.Heading) rnd.nextInt(1, 6) else rnd.nextInt(1, 120)))
})

private fun randomBook(rnd: Random) = OpenBook("id", "", List(rnd.nextInt(1, 6)) { randomSpineItem(rnd, "chapter-$it.xhtml") })

/**
 * A new edition of [spineItem] with text inserted or deleted before [place], and where the Place's text
 * now starts: a new block before it, text added in its block before it, or text cut from its block
 * before it.
 */
private fun shiftedBefore(spineItem: SpineItem, place: Place, rnd: Random): Pair<SpineItem, Int> {
    val at = spineItem.blockStarts[place.block] + place.offset
    val blocks = spineItem.blocks.toMutableList()
    val block = blocks[place.block]
    return when (rnd.nextInt(3)) {
        0 -> {
            val added = Block(BlockKind.Paragraph, prose(rnd, rnd.nextInt(1, 30)))
            blocks.add(rnd.nextInt(0, place.block + 1), added)
            SpineItem(spineItem.spineId, blocks) to at + added.text.length + 1
        }
        1 -> {
            val cut = rnd.nextInt(0, place.offset + 1)
            val added = " " + prose(rnd, rnd.nextInt(1, 10))
            blocks[place.block] = block.copy(text = block.text.substring(0, cut) + added + block.text.substring(cut))
            SpineItem(spineItem.spineId, blocks) to at + added.length
        }
        else -> {
            val from = rnd.nextInt(0, place.offset + 1)
            val to = rnd.nextInt(from, place.offset + 1)
            blocks[place.block] = block.copy(text = block.text.removeRange(from, to))
            SpineItem(spineItem.spineId, blocks) to at - (to - from)
        }
    }
}
