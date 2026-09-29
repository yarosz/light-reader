package com.yarosz.reader

import android.view.KeyEvent
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
        ShelfOwner.forget(dir)
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

    /** Lays a window out as 30 px lines of 1,000 / (font step + 1) characters, each a legal Page end: 10 lines to a Page. */
    private class LineMeasurer : WindowMeasurer {
        override fun key(fontStep: Int) = LayoutKey(fontStep, 1_000, 300)

        override fun measure(spineItem: SpineItem, window: Window, fontStep: Int): WindowLayout {
            val lines = (window.start until window.end step 1_000 / (fontStep + 1)).mapIndexed { i, start ->
                LineMetrics(start, i * 30f, (i + 1) * 30f, endsAtBreak = true, heading = false)
            }
            return WindowLayout(lines) { }
        }
    }

    /** A reader open on Alice and bound to a [LineMeasurer]. */
    private fun reading(): ReaderViewModel {
        val vm = reader()
        vm.openBook()
        settle()
        vm.bind(LineMeasurer())
        settle()
        return vm
    }

    private val ReaderViewModel.onLastPage: Boolean
        get() = frame.value!!.let { SpinePoint(it.pass.item, it.page.end) >= book.value!!.textEnd }

    private fun ReaderViewModel.toLastPage() {
        repeat(10_000) { if (onLastPage) return else nextPage() }
        error("never reached the last Page")
    }

    private fun stored(vm: ReaderViewModel): Book {
        settle()
        return ReadingStore(dir).load().books.getValue(vm.book.value!!.identifier)
    }

    @Test
    fun `opening a Book puts it on the Shelf in the file, with its author, as in progress at its first Page`() {
        val vm = reader()
        vm.openBook()
        settle()
        val book = assertNotNull(vm.book.value)
        val saved = ReadingStore(dir).load().books.getValue(book.identifier)
        val place = assertNotNull(saved.place)
        assertEquals(book.placeAt(SpinePoint(0, 0), place.updatedAt), place)
        assertEquals(0.0, place.progress)
        assertEquals(Book(book.title, "alice.epub", place, finished = false, onShelf = true, author = "Lewis Carroll"), saved.copy(addedAt = null))
        assertEquals("Lewis Carroll", shelfRows(ReadingStore(dir).load(), setOf("alice.epub"), emptyMap()).single().detail)
    }

    @Test
    fun `the volume keys are consumed on down, up and repeat, and other keys are left to LightOS`() {
        val vm = reader()
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertTrue(vm.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
            assertTrue(vm.onKeyUp(key, KeyEvent(KeyEvent.ACTION_UP, key)))
            assertTrue(vm.onKeyMultiple(key, 2, KeyEvent(KeyEvent.ACTION_MULTIPLE, key)))
        }
        val wheelClick = 319 // the SDK's LightDeviceKeys.RotaryButtonPress: LightOS lights the flashlight on it
        assertFalse(vm.onKeyDown(wheelClick, KeyEvent(KeyEvent.ACTION_DOWN, wheelClick)))
        assertFalse(vm.onKeyUp(wheelClick, KeyEvent(KeyEvent.ACTION_UP, wheelClick)))
        assertFalse(vm.onKeyMultiple(wheelClick, 2, KeyEvent(KeyEvent.ACTION_MULTIPLE, wheelClick)))
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
        assertEquals(SpinePoint(2, 0), vm.spinePoint.value)
        assertEquals(DEFAULT_FONT_STEP, vm.fontStep.value)
    }

    @Test
    fun `opening a Book lands on its stored Place`() {
        val book = parseEpub(File(dir, "alice.epub"))
        val spineItem = book.spineItems[3]
        val offset = spineItem.text.length / 2
        ReadingStore(dir).save {
            ReadingData().shelve(book.identifier, book.title, "alice.epub").withPlace(book.identifier, spineItem.placeOf(offset, 1))
        }
        val stored = ReadingStore(dir).load().books.getValue(book.identifier).place
        val vm = reader()
        vm.openBook()
        settle()
        assertEquals(SpinePoint(3, offset), vm.spinePoint.value)
        assertEquals(stored, ReadingStore(dir).load().books.getValue(book.identifier).place)
    }

    @Test
    fun `the end page follows only the last Page, forward on it does nothing, and back returns to the last Page`() {
        val vm = reading()
        while (!vm.onLastPage) {
            assertFalse(vm.atEnd.value)
            vm.nextPage()
        }
        assertFalse(vm.atEnd.value)
        val last = vm.frame.value
        val place = vm.spinePoint.value
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertSame(last, vm.frame.value)
        assertEquals(place, vm.spinePoint.value)
        assertEquals(vm.book.value!!.title, vm.topLine.value)
        assertNull(vm.progressLine.value)
        vm.nextPage()
        vm.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(vm.atEnd.value)
        assertSame(last, vm.frame.value)
        vm.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(vm.atEnd.value)
        assertSame(last, vm.frame.value)
        assertEquals(place, vm.spinePoint.value)
    }

    @Test
    fun `the end page sets Finished and the back turn from it clears it, each re-stamping the last Page's Place`() {
        val vm = reading()
        vm.toLastPage()
        val last = assertNotNull(stored(vm).place)
        assertNotNull(last.progress)
        vm.nextPage()
        val finished = stored(vm)
        assertTrue(finished.finished)
        assertEquals(last, finished.place?.copy(updatedAt = last.updatedAt))
        assertTrue(finished.place!!.updatedAt > last.updatedAt)
        vm.previousPage()
        val cleared = stored(vm)
        assertFalse(cleared.finished)
        assertEquals(last, cleared.place?.copy(updatedAt = last.updatedAt))
        assertTrue(cleared.place!!.updatedAt > finished.place!!.updatedAt)
        vm.nextPage()
        vm.onAppPause()
        assertTrue(stored(vm).finished)
    }

    @Test
    fun `a Finished Book reopens at its last Page, keeps Finished through a font change and a pause, and the first back turn clears it`() {
        val first = reading()
        first.toLastPage()
        first.nextPage()
        first.onAppPause()
        val lastPage = first.spinePoint.value

        val left = reader()
        left.openBook()
        settle()
        left.onAppPause()
        assertTrue(stored(left).finished)

        val vm = reading()
        assertEquals(lastPage, vm.spinePoint.value)
        assertFalse(vm.atEnd.value)
        assertTrue(stored(vm).finished)
        vm.changeFont(+1)
        vm.onAppPause()
        assertTrue(stored(vm).finished)
        assertEquals(lastPage, vm.spinePoint.value)
        vm.changeFont(-1)
        vm.previousPage()
        assertFalse(stored(vm).finished)
        assertTrue(vm.spinePoint.value < lastPage)
    }

    @Test
    fun `a forward turn from a reopened Finished Book shows the end page again, and at another font forward turns reach it without clearing Finished`() {
        val first = reading()
        first.toLastPage()
        first.nextPage()
        first.onAppPause()
        val reopened = reading()
        reopened.nextPage()
        assertTrue(reopened.atEnd.value)
        assertTrue(stored(reopened).finished)
        reopened.onAppPause()

        val vm = reading()
        vm.changeFont(+3)
        assertFalse(vm.onLastPage)
        vm.toLastPage()
        assertTrue(stored(vm).finished)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertTrue(stored(vm).finished)
    }

    @Test
    fun `the top line names the Chapter holding the Page's start, and every turn stores the Place's progress`() {
        val vm = reading()
        val book = vm.book.value!!
        vm.nextPage()
        vm.nextPage()
        val shown = vm.frame.value!!
        val start = SpinePoint(shown.pass.item, shown.page.start)
        assertEquals(book.chapterAt(start)?.let { book.chapters[it].title } ?: book.title, vm.topLine.value)
        assertEquals(book.progressAt(start), stored(vm).place?.progress)
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
