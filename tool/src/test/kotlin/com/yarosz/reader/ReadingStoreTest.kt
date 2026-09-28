package com.yarosz.reader

import java.io.File
import java.io.IOException
import java.io.SyncFailedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class ReadingStoreTest {

    private val dir: File = createTempDirectory("reading-store").toFile()
    private val main = File(dir, "reading-data.json")
    private val backup = File(dir, "reading-data.json.bak")
    private val corrupt = File(dir, "reading-data.json.corrupt")
    private val temp = File(dir, "reading-data.json.tmp")
    private val backupTemp = File(dir, "reading-data.json.bak.tmp")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val alice = ReadingData(
        books = mapOf("urn:alice" to BookEntry("Alice", "alice.epub", Place("chapter-1.xhtml", 3, 12, "Alice was", 100), finished = false, onShelf = true)),
        settings = Settings(fontStep = 3),
    )

    private val snark = ReadingData(
        books = mapOf("urn:snark" to BookEntry("The Hunting of the Snark", "snark.epub", null, finished = false, onShelf = true)),
    )

    @Test
    fun `a save loads back, and saving again changes nothing`() {
        val store = ReadingStore(dir)
        store.save { alice }
        assertEquals(alice, store.load())
        val first = decodeReadingData(main.readText()).getOrThrow()
        store.save { alice }
        assertEquals(first, decodeReadingData(main.readText()).getOrThrow())
    }

    @Test
    fun `nothing saved loads as empty data`() {
        assertEquals(ReadingData(), ReadingStore(dir).load())
    }

    @Test
    fun `an older build keeps a newer build's schema and fields`() {
        main.writeText(
            """
            {
              "schemaVersion": 2,
              "cloudless": {"peers": ["a", "b"]},
              "settings": {"fontStep": 2, "theme": "night"},
              "books": {
                "urn:alice": {
                  "title": "Alice",
                  "file": "alice.epub",
                  "onShelf": true,
                  "finished": false,
                  "rating": 5,
                  "place": {"spineId": "chapter-2.xhtml", "block": 4, "offset": 8, "snippet": "Curiouser", "updatedAt": 1727400000000}
                }
              }
            }
            """.trimIndent(),
        )
        val store = ReadingStore(dir)
        val loaded = store.load()
        assertEquals(Place("chapter-2.xhtml", 4, 8, "Curiouser", 1727400000000), loaded.books.getValue("urn:alice").place)

        store.save { ReadingData() }
        val saved = decodeReadingData(main.readText()).getOrThrow()
        assertEquals(2, saved.schemaVersion)
        assertEquals(loaded.extras, saved.extras)
        assertEquals(JsonPrimitive("night"), saved.settings.extras["theme"])
        assertEquals(JsonPrimitive(5), saved.books.getValue("urn:alice").extras["rating"])
        assertEquals(loaded.books.getValue("urn:alice").place, saved.books.getValue("urn:alice").place)
    }

    @Test
    fun `a corrupt main file loads the backup, is set aside, and never replaces the backup`() {
        backup.writeText(alice.encode())
        main.writeText("{\"books\": {")
        val store = ReadingStore(dir)
        assertEquals(alice, store.load())

        store.save { snark }
        assertEquals("{\"books\": {", corrupt.readText())
        assertEquals(alice, decodeReadingData(backup.readText()).getOrThrow())
        assertEquals(merge(alice, snark), store.load())
    }

    @Test
    fun `both files corrupt load as empty data, and a save keeps the main file's text aside`() {
        main.writeText("[]")
        backup.writeText("not json")
        val store = ReadingStore(dir)
        assertEquals(ReadingData(), store.load())
        store.save { alice }
        assertEquals(alice, store.load())
        assertEquals("[]", corrupt.readText())
        assertEquals("not json", backup.readText())
    }

    @Test
    fun `only the latest corrupt main file is kept aside`() {
        val store = ReadingStore(dir)
        store.save { alice }
        store.save { alice }
        main.writeText("{{")
        store.save { alice }
        assertEquals("{{", corrupt.readText())
        main.writeText("]")
        store.save { snark }
        assertEquals("]", corrupt.readText())
        assertEquals(merge(alice, snark), store.load())
    }

    @Test
    fun `a crash at any point of a save leaves the previous data readable, and the next save finishes cleanly`() {
        val store = ReadingStore(dir)
        store.save { alice }
        val previous = main.readText()
        val crashes = listOf(
            "after the backup's temp file" to { backupTemp.writeText(previous) },
            "after the backup's rename" to { backup.writeText(previous) },
            "after a partial temp file" to { temp.writeText(previous.take(20)) },
            "after the synced temp file, before its rename" to { temp.writeText(merge(alice, snark).encode()) },
        )
        for ((point, crash) in crashes) {
            listOf(backup, backupTemp, temp).forEach { it.delete() }
            main.writeText(previous)
            crash()
            assertEquals(alice, store.load(), point)
            store.save { snark }
            assertEquals(merge(alice, snark), store.load(), point)
            assertEquals(previous, backup.readText(), point)
            assertFalse(temp.exists(), point)
            assertFalse(backupTemp.exists(), point)
        }
    }

    @Test
    fun `a kill after a corrupt main file is set aside leaves the backup readable, and the next save restores the main file`() {
        backup.writeText(alice.encode())
        corrupt.writeText("{{")
        temp.writeText(snark.encode())
        val store = ReadingStore(dir)
        assertEquals(alice, store.load())
        store.save { snark }
        assertEquals(merge(alice, snark), store.load())
        assertEquals("{{", corrupt.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun `a failed rename over the main file leaves it as it was, and the retry succeeds`() {
        var failing = false
        val store = ReadingStore(dir, rename = { from, to -> !(failing && to == main) && from.renameTo(to) })
        store.save { alice }
        val previous = main.readText()
        failing = true
        assertFailsWith<IOException> { store.save { snark } }
        assertEquals(previous, main.readText())
        assertEquals(previous, backup.readText())
        assertEquals(alice, store.load())
        failing = false
        store.save { snark }
        assertEquals(merge(alice, snark), store.load())
        assertFalse(temp.exists())
    }

    @Test
    fun `a failed sync fails the save and leaves the main file as it was`() {
        var failing = false
        val store = ReadingStore(dir, sync = { if (failing) throw SyncFailedException("sync") })
        store.save { alice }
        val previous = main.readText()
        failing = true
        assertFailsWith<IOException> { store.save { snark } }
        assertEquals(previous, main.readText())
        assertEquals(alice, store.load())
    }

    @Test
    fun `two stores over one directory never save at the same time`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = ReadingStore(dir, sync = { inside.countDown(); release.await() })
        val secondSynced = AtomicBoolean(false)
        val second = ReadingStore(File(dir, "."), sync = { secondSynced.set(true) })
        val saving = thread { first.save { alice } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val waiting = thread { second.save { snark } }
        waiting.join(300)
        assertFalse(secondSynced.get(), "the second store saved while the first was mid-save")
        release.countDown()
        saving.join()
        waiting.join()
        assertEquals(merge(alice, snark), ReadingStore(dir).load())
        assertFalse(temp.exists())
    }

    @Test
    fun `after a save the backup holds the previous file`() {
        val store = ReadingStore(dir)
        store.save { alice }
        val previous = main.readText()
        store.save { snark }
        assertEquals(previous, backup.readText())
    }

    @Test
    fun `a Place whose snippet would split a surrogate pair survives the file byte for byte`() {
        val chapter = Chapter("c", "", listOf(Block(BlockKind.Paragraph, "x".repeat(44) + "😀" + "y".repeat(20))))
        val place = chapter.placeOf(5, 1)
        assertEquals(39, place.snippet.length)
        val store = ReadingStore(dir)
        store.save { ReadingData().shelve("id", "", "b.epub").withPlace("id", place) }
        val loaded = store.load().books.getValue("id").place
        assertEquals(place, loaded)
        assertEquals(Position(0, 5), Book("id", "", listOf(chapter)).resolve(loaded!!))
    }
}
