package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Opens the checked-in Alice from a temp filesDir, through the directory's [ShelfOwner], loaded as
 * the Shelf leaves it. The main thread and IO are test dispatchers on separate schedulers, so an open
 * can be held between its IO work and its main-thread finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dir: File = createTempDirectory("reader-vm").toFile()
    private val main = StandardTestDispatcher(TestCoroutineScheduler())
    private val io = StandardTestDispatcher(TestCoroutineScheduler())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
        File("src/test/fixtures/alice.epub").copyTo(File(dir, "alice.epub"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    /** Runs both threads until idle: four rounds cover an open, its finish, a debounced save, and its return. */
    private fun settle() = repeat(4) {
        main.scheduler.advanceUntilIdle()
        io.scheduler.advanceUntilIdle()
    }

    private fun reader(start: DevStart? = null): ReaderViewModel {
        val owner = ShelfOwner.of(dir) { ShelfOwner(dir, io, FakeTransport(emptyMap())) }
        owner.refresh()
        settle()
        return ReaderViewModel(File(dir, "alice.epub"), owner, start, io)
    }

    @Test
    fun `opening a Book puts it on the Shelf in the file, with its author, as in progress at its first Page`() {
        val vm = reader()
        vm.openBook()
        settle()
        val book = assertNotNull(vm.book.value)
        val entry = ReadingStore(dir).load().books.getValue(book.identifier)
        val place = assertNotNull(entry.place)
        assertEquals(book.chapters[0].placeOf(0, place.updatedAt), place)
        assertEquals(BookEntry(book.title, "alice.epub", place, finished = false, onShelf = true, author = "Lewis Carroll"), entry.copy(addedAt = null))
        assertEquals("Lewis Carroll", shelfRows(ReadingStore(dir).load(), setOf("alice.epub"), emptyMap()).single().detail)
    }

    @Test
    fun `a stored title wins over the package's, so a Catalogue's title stays`() {
        val book = parseEpub(File(dir, "alice.epub"))
        ReadingStore(dir).save { ReadingData().shelve(book.identifier, "alice's adventures (as the Catalogue lists it)", "alice.epub") }
        reader().openBook()
        settle()
        assertEquals("alice's adventures (as the Catalogue lists it)", ReadingStore(dir).load().books.getValue(book.identifier).title)
    }

    @Test
    fun `a dev-start session changes the font and pauses, and the reading data file stays byte for byte`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 3)) }
        val before = File(dir, "reading-data.json").readBytes()
        val vm = reader(DevStart(2))
        vm.openBook()
        settle()
        vm.changeFont(+1)
        settle()
        vm.onAppPause()
        settle()
        assertEquals(DEFAULT_FONT_STEP + 1, vm.fontStep.value)
        assertContentEquals(before, File(dir, "reading-data.json").readBytes())
    }

    @Test
    fun `a Book with no title opens under the title stored for its file, and keeps it`() {
        File(dir, "alice.epub").writeEpub(epubFiles(identifier = "urn:uuid:untitled", title = null))
        ReadingStore(dir).save { ReadingData().shelve("urn:uuid:untitled", "From the Catalogue", "alice.epub") }
        val vm = reader()
        vm.openBook()
        settle()
        assertEquals("From the Catalogue", vm.book.value?.title)
        assertEquals("From the Catalogue", ReadingStore(dir).load().books.getValue("urn:uuid:untitled").title)
    }

    @Test
    fun `a dev start opens at its Place and the default font, whatever the reading data holds`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 3)) }
        val vm = reader(DevStart(2))
        vm.openBook()
        settle()
        assertEquals(Position(2, 0), vm.position.value)
        assertEquals(DEFAULT_FONT_STEP, vm.fontStep.value)
    }

    @Test
    fun `opening a Book lands on its stored Place`() {
        val book = parseEpub(File(dir, "alice.epub"))
        val chapter = book.chapters[3]
        val offset = chapter.text.length / 2
        ReadingStore(dir).save {
            ReadingData().shelve(book.identifier, book.title, "alice.epub").withPlace(book.identifier, chapter.placeOf(offset, 1))
        }
        val stored = ReadingStore(dir).load().books.getValue(book.identifier).place
        val vm = reader()
        vm.openBook()
        settle()
        assertEquals(Position(3, offset), vm.position.value)
        assertEquals(stored, ReadingStore(dir).load().books.getValue(book.identifier).place)
    }

    @Test
    fun `a show during the first open starts no second open, so a change made meanwhile survives`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 2)) }
        val vm = reader()
        vm.openBook()
        main.scheduler.runCurrent()
        io.scheduler.runCurrent()
        vm.openBook()
        main.scheduler.runCurrent()
        assertEquals(2, vm.fontStep.value)
        vm.changeFont(+1)
        settle()
        assertEquals(3, vm.fontStep.value)
        assertEquals(3, ReadingStore(dir).load().settings.fontStep)
    }
}
