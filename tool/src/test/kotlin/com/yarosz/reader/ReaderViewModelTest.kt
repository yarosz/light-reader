package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Opens the checked-in Alice from a temp filesDir. The main thread and IO are test dispatchers on
 * separate schedulers, so an open can be held between its IO work and its main-thread finish.
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

    @Test
    fun `opening a Book puts it on the Shelf in the file, with no page turned`() {
        val vm = ReaderViewModel(dir, io)
        vm.openBook()
        settle()
        val book = assertNotNull(vm.book.value)
        val entry = ReadingStore(dir).load().books.getValue(book.identifier)
        assertEquals(BookEntry(book.title, "alice.epub", null, finished = false, onShelf = true), entry)
    }

    @Test
    fun `the dev-start hook opens at the default font, whatever the last run left in the file`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 3)) }
        File(dir, "dev-start").writeText("2\n")
        val vm = ReaderViewModel(dir, io)
        vm.openBook()
        settle()
        assertEquals(Position(2, 0), vm.position.value)
        assertEquals(DEFAULT_FONT_STEP, vm.fontStep.value)
    }

    @Test
    fun `a dev-start session saves nothing, so the device's reading data is left as it was`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 3)) }
        val before = File(dir, "reading-data.json").readText()
        File(dir, "dev-start").writeText("2\n")
        val vm = ReaderViewModel(dir, io)
        vm.openBook()
        settle()
        vm.changeFont(+1)
        settle()
        vm.onAppPause()
        assertEquals(before, File(dir, "reading-data.json").readText())
    }

    @Test
    fun `opening a Book lands on its stored Place`() {
        val book = parseEpub(File(dir, "alice.epub"))
        val chapter = book.chapters[3]
        val offset = chapter.text.length / 2
        ReadingStore(dir).save {
            ReadingData().shelve(book.identifier, book.title, "alice.epub").withPlace(book.identifier, chapter.placeOf(offset, 1))
        }
        val vm = ReaderViewModel(dir, io)
        vm.openBook()
        settle()
        assertEquals(Position(3, offset), vm.position.value)
    }

    @Test
    fun `a show during the first open starts no second open, so a change made meanwhile survives`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 2)) }
        val vm = ReaderViewModel(dir, io)
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
