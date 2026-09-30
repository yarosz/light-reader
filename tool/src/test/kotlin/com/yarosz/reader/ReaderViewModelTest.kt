package com.yarosz.reader

import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Time on a Page in the speed tests: a [LineMeasurer] Page at [DEFAULT_FONT_STEP] holds about 900 words, 360 a
 * minute at this, under [SAMPLE_MAX_WPM]. The headroom holds only there: a step-0 Page is about 1,800 words,
 * 720 a minute.
 */
private const val READ_MS = 150_000L

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

    /** The monotonic millis every reader here reads for timing Pages. */
    private var clock = 0L

    private val speed: ReadingSpeed get() = ShelfOwner.of(dir).speed

    private fun reader(start: DevStart? = null): ReaderViewModel {
        val owner = ShelfOwner.of(dir) { ShelfOwner(dir, io, FakeTransport(emptyMap())) }
        owner.refresh()
        settle()
        return ReaderViewModel(File(dir, "alice.epub"), owner, start, io) { clock }
    }

    /**
     * Lays a window out as 30 px lines of 1,000 / (font step + 1) characters, each a legal Page end: [pageHeightPx] / 30 lines to a
     * Page. With [tailAt] (a Spine item's id and an offset in it), the line holding that offset starts mid-word instead. With
     * [byBlock], lines restart at every block, and a heading's lines are headings, as the real layout's are.
     */
    private data class LineMeasurer(
        private val pageHeightPx: Int = 300,
        private val tailAt: Pair<String, Int>? = null,
        private val byBlock: Boolean = false,
    ) : WindowMeasurer {
        override fun key(fontStep: Int) = LayoutKey(fontStep, 1_000, pageHeightPx)

        override fun measure(spineItem: SpineItem, window: Window, fontStep: Int): WindowLayout {
            val step = 1_000 / (fontStep + 1)
            val starts = if (!byBlock) (window.start until window.end step step).toList() else (window.firstBlock..window.lastBlock).flatMap { b ->
                val start = spineItem.blockStarts[b]
                (start until start + maxOf(spineItem.blocks[b].text.length, 1) step step).toList()
            }
            val tail = tailAt?.takeIf { (id, char) -> id == spineItem.spineId && char in window.start until window.end }
                ?.let { (_, char) -> starts.indexContaining(char) { it } }
            val lines = starts.mapIndexed { i, start ->
                LineMetrics(start, i * 30f, (i + 1) * 30f, endsAtBreak = tail == null || i != tail - 1, heading = byBlock && spineItem.keepsWithNext(start))
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
        get() = frame.value!!.let {
            val textEnd = book.value!!.textEnd
            SpinePoint(it.pass.item, it.page.start) < textEnd && SpinePoint(it.pass.item, it.page.end) >= textEnd
        }

    private fun ReaderViewModel.jumpToChapter(chapter: Int) = jumpTo(book.value!!.chapters[chapter].start)

    private fun ReaderViewModel.toLastPage() {
        repeat(10_000) { if (onLastPage) return else nextPage() }
        error("never reached the last Page")
    }

    private val aliceWords by lazy { WordIndex(parseEpub(File("src/test/fixtures/alice.epub")).spineItems) }

    private val ReaderViewModel.shownWords: Int
        get() = frame.value!!.let { aliceWords.between(SpinePoint(it.pass.item, it.page.start), SpinePoint(it.pass.item, it.page.end)) }

    /** Reads the shown Page for [ms], then turns forward. The default reads [LineMeasurer]'s Pages under [SAMPLE_MAX_WPM]. */
    private fun ReaderViewModel.readThenTurn(ms: Long = READ_MS) {
        clock += ms
        nextPage()
    }

    /** Four samples, one short of the reader's own speed: a turn off the opened Page, which is untimed, then four Pages read forward. */
    private fun ReaderViewModel.fourSamples() {
        readThenTurn()
        repeat(MEASURED_AFTER - 1) { readThenTurn() }
        assertEquals(PRIOR_WPM, speed.wpm)
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
        val measurer = LineMeasurer()
        val book = vm.book.value!!
        val session = Reading(book.spineItems, { pass, window -> measurer.measure(pass.spineItem, pass.windows[window], pass.key.fontStep) }, { it.lines }, textEnd = book.textEnd)
        session.open(place.item, place.char, measurer.key(DEFAULT_FONT_STEP))
        val after = session.next()
        assertTrue(after == null || SpinePoint(after.pass.item, after.page.start) >= book.textEnd, "the Reading's next Page is in Back matter")
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

    private fun assertRelayoutLeavesTheEndPage(relayout: (ReaderViewModel) -> Unit) {
        val vm = reading()
        vm.toLastPage()
        vm.nextPage()
        val place = vm.spinePoint.value
        relayout(vm)
        assertFalse(vm.atEnd.value)
        assertFalse(vm.onLastPage)
        val shown = vm.frame.value!!
        assertTrue(shown.pass.item == place.item && place.char in shown.page.start until shown.page.end)
        assertEquals(place, vm.spinePoint.value)
        val book = vm.book.value!!
        assertEquals(book.chapters[book.chapterAt(place)!!].title, vm.topLine.value)
        assertTrue(stored(vm).finished)
        vm.toLastPage()
        assertTrue(stored(vm).finished)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
    }

    @Test
    fun `a font change under the end page that leaves the Place's Page short of the text's end shows that Page, and Finished stays`() =
        assertRelayoutLeavesTheEndPage { it.changeFont(+3) }

    @Test
    fun `a bind under the end page that leaves the Place's Page short of the text's end shows that Page, and Finished stays`() =
        assertRelayoutLeavesTheEndPage { it.bind(LineMeasurer(pageHeightPx = 90)) }

    @Test
    fun `a relayout under the end page keeps it while the Place's Page still reaches the text's end`() {
        for (relayout in listOf<(ReaderViewModel) -> Unit>({ it.changeFont(-1) }, { it.bind(LineMeasurer(pageHeightPx = 600)) })) {
            val vm = reading()
            vm.toLastPage()
            vm.nextPage()
            relayout(vm)
            assertTrue(vm.atEnd.value)
            assertTrue(vm.onLastPage)
            assertEquals(vm.book.value!!.title, vm.topLine.value)
            assertNull(vm.progressLine.value)
            vm.previousPage()
            vm.changeFont(+1)
        }
    }

    @Test
    fun `a Finished Book at its first Page clears Finished on a back turn, though no Page comes before`() {
        val book = parseEpub(File(dir, "alice.epub"))
        ReadingStore(dir).save {
            ReadingData().shelve(book.identifier, book.title, "alice.epub").withFinished(book.identifier, true, book.placeAt(SpinePoint(0, 0), 1))
        }
        val vm = reading()
        assertTrue(stored(vm).finished)
        vm.previousPage()
        val cleared = stored(vm)
        assertFalse(cleared.finished)
        assertEquals(SpinePoint(0, 0), vm.spinePoint.value)
        vm.previousPage()
        assertEquals(cleared, stored(vm))
    }

    @Test
    fun `forward turns time each Page, and the speed leaves the prior at the fifth sample`() {
        val vm = reading()
        vm.readThenTurn()
        val samples = List(MEASURED_AFTER) {
            assertEquals(PRIOR_WPM, speed.wpm)
            val words = vm.shownWords
            assertTrue(words >= SAMPLE_MIN_WORDS)
            vm.readThenTurn()
            words * 60_000.0 / READ_MS
        }
        assertEquals(samples.sorted()[MEASURED_AFTER / 2], speed.wpm)
        val shown = vm.frame.value!!
        val start = SpinePoint(shown.pass.item, shown.page.start)
        val book = vm.book.value!!
        assertEquals(book.minutesLine(aliceWords, start, speed.wpm), vm.progressLine.value)
        assertNotEquals(book.minutesLine(aliceWords, start, PRIOR_WPM), vm.progressLine.value)
    }

    @Test
    fun `a back turn drops the Page's timing, and the Page it reaches gives no sample`() {
        val vm = reading()
        vm.fourSamples()
        clock += 20_000
        vm.previousPage()
        vm.readThenTurn()
        assertEquals(PRIOR_WPM, speed.wpm)
        vm.readThenTurn()
        assertNotEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `a font change drops the running timing`() {
        val vm = reading()
        vm.fourSamples()
        clock += 20_000
        vm.changeFont(+1)
        vm.readThenTurn()
        assertEquals(PRIOR_WPM, speed.wpm)
        vm.readThenTurn()
        assertNotEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `a pause drops the running timing`() {
        val vm = reading()
        vm.fourSamples()
        clock += 20_000
        vm.onAppPause()
        vm.readThenTurn()
        assertEquals(PRIOR_WPM, speed.wpm)
        vm.readThenTurn()
        assertNotEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `leaving the last Page for the end page gives no sample, since the end page isn't a Page`() {
        val vm = reading()
        vm.toLastPage()
        assertTrue(sampleWpm(vm.shownWords, READ_MS) != null, "the last Page would otherwise count")
        repeat(MEASURED_AFTER - 1) { speed.record(100, 20_000) }
        vm.readThenTurn()
        assertTrue(vm.atEnd.value)
        assertEquals(PRIOR_WPM, speed.wpm)
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

    @Test
    fun `A− does nothing at the smallest size and A+ nothing at the largest, so the footer shows them disabled`() {
        assertFalse(canChangeFont(0, -1))
        assertTrue(canChangeFont(0, +1))
        assertTrue(canChangeFont(FONT_SIZES.lastIndex, -1))
        assertFalse(canChangeFont(FONT_SIZES.lastIndex, +1))
        assertTrue(canChangeFont(DEFAULT_FONT_STEP, -1) && canChangeFont(DEFAULT_FONT_STEP, +1))
    }

    @Test
    fun `a change of 3 steps goes as far as the sizes go, and only its direction decides whether it can`() {
        assertTrue(canChangeFont(DEFAULT_FONT_STEP, +3) && canChangeFont(DEFAULT_FONT_STEP, -3))
        assertFalse(canChangeFont(0, -3))
        assertFalse(canChangeFont(FONT_SIZES.lastIndex, +3))
        val vm = reading()
        vm.changeFont(-3)
        assertEquals(0, vm.fontStep.value)
        vm.changeFont(+3)
        assertEquals(3, vm.fontStep.value)
        vm.changeFont(+3)
        assertEquals(FONT_SIZES.lastIndex, vm.fontStep.value)
    }

    @Test
    fun `leaving the Reader mid-open does nothing, neither "couldn't open" nor a Book`() {
        val vm = reader()
        vm.openBook()
        main.scheduler.runCurrent()
        vm.viewModelScope.cancel()
        settle()
        assertEquals(READING_OPENING, vm.status.value)
        assertNull(vm.book.value)
    }

    /**
     * A reader open on [BackMatterTest]'s Gutenberg-shaped Book, in Alice's file, its license mid-way through
     * its last Spine item, its Chapters from [ncx]. [place] stores a Place first, with [finished].
     */
    private fun gutenberg(finished: Boolean = false, ncx: String = BackMatterTest.gutenbergNcx(), place: ((OpenBook) -> SpinePoint)? = null): ReaderViewModel {
        val file = File(dir, "alice.epub").writeEpub(tocEpubFiles(BackMatterTest.gutenbergBodies(paragraphs = 150), ncx = ncx))
        if (place != null) {
            val book = parseEpub(file)
            ReadingStore(dir).save {
                ReadingData().shelve(book.identifier, book.title, "alice.epub").withFinished(book.identifier, finished, book.placeAt(place(book), 1))
            }
        }
        return reading()
    }

    private val ReaderViewModel.pageStart: SpinePoint get() = frame.value!!.let { SpinePoint(it.pass.item, it.page.start) }

    private val ReaderViewModel.pageEnd: SpinePoint get() = frame.value!!.let { SpinePoint(it.pass.item, it.page.end) }

    private val ReaderViewModel.inBackMatter: Boolean get() = pageStart >= book.value!!.textEnd

    private fun ReaderViewModel.assertBackMatterLines() {
        assertEquals(BackMatterTest.LICENSE_HEADING, topLine.value)
        assertNull(progressLine.value)
    }

    @Test
    fun `the end page follows the last Page of the text, which ends where Back matter starts, and sets Finished`() {
        val vm = gutenberg()
        val book = vm.book.value!!
        assertTrue(book.textEnd < SpinePoint(book.spineItems.lastIndex, book.spineItems.last().text.length))
        while (!vm.onLastPage) {
            assertFalse(vm.atEnd.value)
            assertFalse(vm.inBackMatter)
            vm.nextPage()
        }
        val last = vm.frame.value
        assertEquals(book.textEnd, vm.pageEnd)
        assertFalse(vm.inBackMatter)
        assertNotNull(vm.progressLine.value)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertSame(last, vm.frame.value)
        assertTrue(stored(vm).finished)
        assertEquals(book.title, vm.topLine.value)
        assertNull(vm.progressLine.value)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertSame(last, vm.frame.value)
    }

    @Test
    fun `a Place in Back matter opens there with no end page, turns forward through it, and forward on the Book's last Page does nothing`() {
        val vm = gutenberg { it.textEnd.copy(char = it.textEnd.char + 1) }
        val book = vm.book.value!!
        assertFalse(vm.atEnd.value)
        assertEquals(book.textEnd, vm.pageStart)
        vm.assertBackMatterLines()
        var turns = 0
        while (true) {
            val before = vm.frame.value
            vm.nextPage()
            assertFalse(vm.atEnd.value)
            if (vm.frame.value === before) break
            turns++
            assertTrue(vm.inBackMatter)
            vm.assertBackMatterLines()
        }
        assertTrue(turns >= 2, "the license is several Pages")
        assertEquals(SpinePoint(book.spineItems.lastIndex, book.spineItems.last().text.length), vm.pageEnd)
        assertFalse(stored(vm).finished)
    }

    @Test
    fun `forward on the Book's last Page gives no sample, and the Page stays timed`() {
        val vm = gutenberg { it.textEnd.copy(char = it.textEnd.char + 1) }
        val book = vm.book.value!!
        val bookEnd = SpinePoint(book.spineItems.lastIndex, book.spineItems.last().text.length)
        repeat(10_000) { if (vm.pageEnd < bookEnd) vm.nextPage() }
        val shown = vm.frame.value!!
        assertTrue(vm.inBackMatter)
        assertEquals(bookEnd, vm.pageEnd)
        val words = WordIndex(book.spineItems).between(vm.pageStart, vm.pageEnd)
        assertTrue(sampleWpm(words, READ_MS) != null, "the last Page, $words words, would otherwise count")
        repeat(MEASURED_AFTER - 1) { speed.record(100, 20_000) }
        vm.readThenTurn()
        assertSame(shown, vm.frame.value)
        assertEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `back turns inside Back matter keep Finished, and the one onto the last Page of the text clears it`() {
        val vm = gutenberg(finished = true) { SpinePoint(it.spineItems.lastIndex, it.spineItems.last().text.length - 1) }
        val book = vm.book.value!!
        assertTrue(vm.inBackMatter)
        assertTrue(stored(vm).finished)
        while (vm.pageStart != book.textEnd) {
            vm.previousPage()
            assertFalse(vm.atEnd.value)
            assertTrue(vm.inBackMatter)
            assertTrue(stored(vm).finished)
        }
        vm.previousPage()
        assertFalse(vm.inBackMatter)
        assertEquals(book.textEnd, vm.pageEnd)
        assertFalse(stored(vm).finished)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertTrue(stored(vm).finished)
    }

    private fun ReaderViewModel.storedPlace(): SpinePoint? = stored(this).place?.let(book.value!!::resolve)

    @Test
    fun `a jump from the end page leaves it for the Page starting the Chapter, stamped as the Place, and clears Finished`() {
        val vm = gutenberg()
        val book = vm.book.value!!
        vm.toLastPage()
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertTrue(stored(vm).finished)
        vm.jumpToChapter(1)
        assertFalse(vm.atEnd.value)
        assertEquals(book.chapters[1].start, vm.pageStart)
        assertEquals(vm.pageStart, vm.spinePoint.value)
        assertEquals("Chapter I.", vm.topLine.value)
        assertNotNull(vm.progressLine.value)
        assertFalse(stored(vm).finished)
        assertEquals(vm.pageStart, vm.storedPlace())
    }

    @Test
    fun `a jump to the last Chapter of the text clears Finished and lands on its first Page, not the end page`() {
        val vm = gutenberg(finished = true) { SpinePoint(1, 500) }
        val book = vm.book.value!!
        assertTrue(stored(vm).finished)
        vm.jumpToChapter(2)
        assertFalse(vm.atEnd.value)
        assertEquals(book.chapters[2].start, vm.pageStart)
        assertFalse(stored(vm).finished)
        assertEquals(vm.pageStart, vm.storedPlace())
    }

    /** Jumps from a Place in Chapter I. into Back matter, and checks it lands there, stamped, with Finished still [finished]. */
    private fun assertJumpIntoBackMatter(finished: Boolean) {
        val vm = gutenberg(finished) { SpinePoint(1, 500) }
        val book = vm.book.value!!
        vm.jumpToChapter(3)
        assertFalse(vm.atEnd.value)
        assertEquals(book.textEnd, vm.pageStart)
        vm.assertBackMatterLines()
        assertEquals(finished, stored(vm).finished)
        assertEquals(book.textEnd, vm.storedPlace())
    }

    @Test
    fun `a jump into Back matter keeps Finished`() = assertJumpIntoBackMatter(finished = true)

    @Test
    fun `a jump into Back matter never sets Finished`() = assertJumpIntoBackMatter(finished = false)

    @Test
    fun `a jump to a Part's heading lands there, and the Page goes by the Part's first Chapter`() {
        val words = List(150) { "<p>It is a truth universally acknowledged. $it</p>" }.joinToString("")
        val bodies = listOf("<h1>BOOK ONE</h1>", "<h2 id=\"ch1\">Chapter I.</h2>$words", "<h2 id=\"ch2\">Chapter II.</h2>$words")
        val ncx = ncx(navPoint("BOOK ONE", "text/c0.xhtml", navPoint("Chapter I.", "text/c1.xhtml#ch1"), navPoint("Chapter II.", "text/c2.xhtml#ch2")))
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx))
        val vm = reading()
        vm.bind(LineMeasurer(byBlock = true))
        settle()
        vm.jumpToChapter(1)
        val contents = vm.openContents()!!
        assertEquals(listOf("BOOK ONE", "Chapter I.", "Chapter II."), contents.rows.map { it.title })
        assertEquals(2, contents.current)
        vm.jumpTo(contents.rows[0].start)
        assertEquals(SpinePoint(0, 0), vm.pageStart)
        assertEquals(vm.pageStart, vm.storedPlace())
        assertEquals("Chapter I.", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
    }

    @Test
    fun `a jump to the current Chapter goes to its start`() {
        val vm = gutenberg()
        val book = vm.book.value!!
        vm.jumpToChapter(1)
        repeat(3) { vm.nextPage() }
        assertTrue(vm.pageStart > book.chapters[1].start)
        assertEquals(1, vm.openContents()!!.current)
        vm.jumpToChapter(1)
        assertEquals(book.chapters[1].start, vm.pageStart)
        assertEquals(vm.pageStart, vm.storedPlace())
    }

    @Test
    fun `a jump drops the running timing, and the Page it lands on gives no sample`() {
        val vm = reading()
        vm.fourSamples()
        clock += 20_000
        vm.jumpToChapter(3)
        assertEquals(vm.book.value!!.chapters[3].start, vm.pageStart)
        vm.readThenTurn()
        assertEquals(PRIOR_WPM, speed.wpm)
        vm.readThenTurn()
        assertNotEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `opening Contents drops the running timing, though back returns without a jump`() {
        val vm = reading()
        vm.fourSamples()
        clock += 20_000
        assertNotNull(vm.openContents())
        vm.readThenTurn()
        assertEquals(PRIOR_WPM, speed.wpm)
        vm.readThenTurn()
        assertNotEquals(PRIOR_WPM, speed.wpm)
    }

    @Test
    fun `a jump to a Chapter starting mid-line lands on the Page holding that line and names the Chapter chosen`() {
        val bodies = listOf(
            "<h1>One</h1><p>First.</p>",
            "<p>${"word ".repeat(301)}<span id=\"two\">Two starts</span> ${"word ".repeat(600)}</p>",
        )
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c1.xhtml#two"))))
        val vm = reading()
        val two = vm.book.value!!.chapters[1].start
        vm.jumpToChapter(1)
        val shown = vm.frame.value!!
        assertTrue(vm.pageStart < two && two.char < shown.pass.firstLineEnd(shown.page), "lands on the Page whose first line holds $two")
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
        val landed = vm.frame.value!!
        vm.jumpToChapter(1)
        assertSame(landed.pass, vm.frame.value!!.pass)
    }

    /**
     * The same Chapter, its line now starting mid-word (the tail of a hyphenated word): a jump is exempt
     * from moving a Page's start up to a whole word, so the Page still starts on the Chapter's line, the
     * top line and Contents name the Chapter, and the Place is that line.
     */
    @Test
    fun `a jump to a Chapter whose line starts mid-word still lands on the Page starting on that line`() {
        val bodies = listOf(
            "<h1>One</h1><p>First.</p>",
            "<p>${"word ".repeat(301)}<span id=\"two\">Two starts</span> ${"word ".repeat(600)}</p>",
        )
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c1.xhtml#two"))))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        val two = opened.chapters[1].start
        vm.bind(LineMeasurer(tailAt = opened.spineItems[two.item].spineId to two.char))
        settle()
        vm.jumpToChapter(1)
        val shown = vm.frame.value!!
        assertTrue(vm.pageStart <= two && two.char < shown.pass.firstLineEnd(shown.page), "lands on the Page whose first line holds $two")
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
        assertEquals(vm.pageStart, vm.spinePoint.value)
    }

    /**
     * After that jump a font change re-packs at the Place, the Chapter's start, on a line that starts
     * mid-word again. The walk up to a whole word stops at the line holding the Chapter's start, so the
     * Page doesn't open in the Chapter before: the top line, Contents and the Place stay the Chapter's.
     */
    @Test
    fun `a font change after a jump to a Chapter keeps its Page, top line and Contents on that Chapter`() {
        val bodies = listOf(
            "<h1>One</h1><p>First.</p>",
            "<p>${"word ".repeat(400)}<span id=\"two\">Two starts</span> ${"word ".repeat(600)}</p>",
        )
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c1.xhtml#two"))))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        val two = opened.chapters[1].start
        vm.bind(LineMeasurer(tailAt = opened.spineItems[two.item].spineId to two.char))
        settle()
        vm.jumpToChapter(1)
        assertEquals(two, vm.pageStart)
        assertEquals("Two", vm.topLine.value)
        vm.changeFont(+1)
        settle()
        val shown = vm.frame.value!!
        assertTrue(vm.pageStart <= two && two.char < shown.pass.firstLineEnd(shown.page), "the Page's first line holds $two")
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
        assertEquals(two, vm.spinePoint.value)
    }

    /**
     * A table of contents pointing at the paragraph after a Chapter's heading: the heading is the
     * Chapter's. A jump starts the Page on the paragraph; a font change at that Place starts it on the
     * heading, keeping it with its text, and the Page still goes by the Chapter, as its first line under
     * the heading holds the Chapter's start.
     */
    @Test
    fun `a font change at a Chapter pointed at past its heading starts the Page on the heading and names the Chapter`() {
        val body = "<h1>One</h1><p>${"word ".repeat(400)}</p><h2>II</h2><p id=\"two\">${"word ".repeat(400)}</p>"
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf(body), ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c0.xhtml#two"))))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        val two = opened.chapters[1].start
        val heading = opened.spineItems[0].blockStarts[2]
        assertEquals(opened.spineItems[0].blockStarts[3], two.char)
        vm.bind(LineMeasurer(byBlock = true))
        settle()
        vm.jumpToChapter(1)
        assertEquals(two, vm.pageStart)
        assertEquals("Two", vm.topLine.value)
        vm.changeFont(+1)
        settle()
        assertEquals(SpinePoint(0, heading), vm.pageStart)
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
        assertEquals(two, vm.spinePoint.value)
    }

    @Test
    fun `coming back from Contents to a measurer that lays out alike keeps the Pages shown and cached`() {
        val vm = reading()
        repeat(3) { vm.nextPage() }
        val before = vm.frame.value!!
        vm.nextPage()
        val shown = vm.frame.value!!
        vm.openContents()
        vm.bind(LineMeasurer())
        assertSame(shown, vm.frame.value)
        vm.previousPage()
        assertEquals(before.page, vm.frame.value!!.page)
        vm.jumpToChapter(3)
        val landed = vm.frame.value
        vm.bind(LineMeasurer())
        assertSame(landed, vm.frame.value)
        vm.bind(LineMeasurer(pageHeightPx = 301))
        assertNotSame(landed, vm.frame.value)
        assertEquals(landed!!.page.start, vm.frame.value!!.page.start)
    }

    @Test
    fun `Contents lists the Chapters, and its current row follows the Place, the end page and Back matter`() {
        val vm = gutenberg()
        val contents = vm.openContents()!!
        assertEquals(listOf("Title", "Chapter I.", "Chapter II.", BackMatterTest.LICENSE_HEADING), contents.rows.map { it.title })
        assertEquals(0, contents.current)
        vm.toLastPage()
        assertEquals(2, vm.openContents()!!.current)
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        assertEquals(2, vm.openContents()!!.current)
        vm.jumpToChapter(3)
        assertEquals(3, vm.openContents()!!.current)
    }

    @Test
    fun `of two Chapters starting at one point the later is current, and a jump to either lands on the same Page`() {
        val ncx = ncx(
            navPoint("Title", "text/c0.xhtml"),
            navPoint("Part One", "text/c1.xhtml"),
            navPoint("Chapter I.", "text/c1.xhtml#ch1"),
            navPoint("Chapter II.", "text/c2.xhtml#ch2"),
        )
        val vm = gutenberg(ncx = ncx)
        val book = vm.book.value!!
        assertEquals(book.chapters[1].start, book.chapters[2].start)
        vm.jumpToChapter(1)
        val first = vm.frame.value!!.page
        assertEquals(2, vm.openContents()!!.current)
        assertEquals("Chapter I.", vm.topLine.value)
        vm.jumpToChapter(3)
        vm.jumpToChapter(2)
        assertEquals(first, vm.frame.value!!.page)
        assertEquals(2, vm.openContents()!!.current)
    }

    /**
     * A flat table of contents listing a Part and then its first Chapter, whose headings sit one above the
     * other: a jump to the Part starts the Page on the Part's heading, and the Page goes by the Part, the
     * Chapter starting in its first line, not by the Chapter under both headings.
     */
    @Test
    fun `a jump to a Part heading right above its first Chapter's heading names the Part`() {
        val body = "<h1>One</h1><p>${"word ".repeat(400)}</p><h2 id=\"b1\">BOOK ONE</h2><h2 id=\"c1\">CHAPTER I</h2><p>${"word ".repeat(400)}</p>"
        val ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Book One", "text/c0.xhtml#b1"), navPoint("Chapter I", "text/c0.xhtml#c1"))
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf(body), ncx = ncx))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        vm.bind(LineMeasurer(byBlock = true))
        settle()
        vm.jumpToChapter(1)
        assertEquals(opened.chapters[1].start, vm.pageStart)
        assertEquals("Book One", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
        vm.jumpToChapter(2)
        assertEquals("Chapter I", vm.topLine.value)
        assertEquals(2, vm.openContents()!!.current)
    }

    /**
     * An unlisted heading above a Part's heading and its first Chapter's: after a jump to the Part, a larger
     * font packs the Page from the unlisted heading ([pageFloor] walks up the headings), so no Chapter starts
     * in its first line. The Page goes by the first Chapter starting in its headings, the Part, not by the
     * Chapter under them.
     */
    @Test
    fun `after a font change a Page opening on an unlisted heading above a Part's still names the Part`() {
        partUnderUnlistedHeading("<h2>VOLUME I</h2><h2 id=\"b1\">BOOK ONE</h2>")
    }

    /** The same with a heading's illustration caption above the Part's heading, kept with it like a heading. */
    @Test
    fun `after a font change a Page opening on a caption above a Part's heading still names the Part`() {
        partUnderUnlistedHeading("<h2><span class=\"caption\">The Emperor</span><span id=\"b1\">BOOK ONE</span></h2>")
    }

    private fun partUnderUnlistedHeading(above: String) {
        val body = "<h1>One</h1><p>${"word ".repeat(400)}</p>$above<h2 id=\"c1\">CHAPTER I</h2><p>${"word ".repeat(400)}</p>"
        val ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Book One", "text/c0.xhtml#b1"), navPoint("Chapter I", "text/c0.xhtml#c1"))
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf(body), ncx = ncx))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        vm.bind(LineMeasurer(byBlock = true))
        settle()
        vm.jumpToChapter(1)
        assertEquals("Book One", vm.topLine.value)
        vm.changeFont(+1)
        settle()
        assertTrue(vm.pageStart < opened.chapters[1].start, "the Page opens on the heading above the Part's")
        assertEquals("Book One", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
    }

    /**
     * A Part's title page as a Spine item of its own, only its heading and unlisted, before the Spine item
     * its first Chapter starts: that Page ends at its Spine item's end, the Chapter's start, and goes by it.
     */
    @Test
    fun `a Part's title page of its own Spine item goes by the Chapter after it`() {
        val bodies = listOf("<h1>One</h1><p>${"word ".repeat(40)}</p>", "<h1>PART TWO</h1>", "<h2>Two</h2><p>${"word ".repeat(400)}</p>")
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c2.xhtml"))))
        val vm = reader()
        vm.openBook()
        settle()
        vm.bind(LineMeasurer(byBlock = true))
        settle()
        vm.jumpToChapter(0)
        vm.nextPage()
        assertEquals(SpinePoint(1, 0), vm.pageStart, "the Page is the Part's title page")
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
    }

    /**
     * Three-line Pages: Chapter Two's three headings fill a Page of their own and its first paragraph, where
     * the table of contents points, opens the next. The headings are Two's, so that Page goes by Two.
     */
    @Test
    fun `a Page of only headings goes by the Chapter starting right after them`() {
        val short = "<p>${"word ".repeat(40)}</p>"
        val body = "<h1>One</h1>$short$short<h2>II</h2><h3>The Second</h3><h3>Part</h3><p id=\"two\">${"word ".repeat(400)}</p>"
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf(body), ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c0.xhtml#two"))))
        val vm = reader()
        vm.openBook()
        settle()
        val two = vm.book.value!!.chapters[1].start
        vm.bind(LineMeasurer(pageHeightPx = 90, byBlock = true))
        settle()
        vm.nextPage()
        assertEquals(two, vm.pageEnd, "the Page holds only Two's headings")
        assertEquals("Two", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
    }

    /**
     * The same shape at the end of the text: the last Page of the text holds only headings and ends where
     * Back matter starts, and it still goes by its own Chapter.
     */
    @Test
    fun `a last Page of the text of only headings never goes by the Back matter after it`() {
        val footer = """<div class="pg-boilerplate pgheader footer" id="pg-footer"><h2>${BackMatterTest.LICENSE_HEADING}</h2><p>${BackMatterTest.LICENSE_TERMS}</p></div>"""
        val short = "<p>${"word ".repeat(40)}</p>"
        val bodies = listOf("<h1>Title</h1>", "<h1>One</h1>$short$short<h2>THE END</h2><h3>Finis</h3><h3>Fin</h3>$footer")
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("Title", "text/c0.xhtml"))))
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        assertEquals(listOf("Title", "One", BackMatterTest.LICENSE_HEADING), opened.chapters.map { it.title })
        vm.bind(LineMeasurer(pageHeightPx = 90, byBlock = true))
        settle()
        vm.jumpToChapter(1)
        vm.nextPage()
        assertEquals(opened.textEnd, vm.pageEnd, "the last Page of the text holds only headings")
        assertEquals("One", vm.topLine.value)
        assertEquals(1, vm.openContents()!!.current)
    }
}
