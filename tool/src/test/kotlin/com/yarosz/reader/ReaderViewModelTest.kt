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
 * Time on a Page in the speed tests: a [LineMeasurer] Page at [DEFAULT_FONT_STEP] holds about 600 words, 240 a
 * minute at this, under [SAMPLE_MAX_WPM]. The headroom doesn't hold at step 0: a step-0 Page is about 1,800
 * words, 720 a minute.
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

    /**
     * Where the keep-awake times out and the first-run hint waits out its delay: its own scheduler, so [settle]
     * never runs out [KEEP_AWAKE_MS] or [HINT_DELAY_MS].
     */
    private val idle = StandardTestDispatcher(TestCoroutineScheduler())

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
        return ReaderViewModel(File(dir, "alice.epub"), owner, start, io, idle) { clock }
    }

    /** Lets [ms] pass on [idle]'s scheduler, running what falls due by then. */
    private fun idleFor(ms: Long) {
        idle.scheduler.advanceTimeBy(ms)
        idle.scheduler.runCurrent()
    }

    private fun ReaderViewModel.press(keyCode: Int) = onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

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
        ReadingStore(dir).save { ReadingData(settings = Settings(fontSize = 30f)) }
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
        ReadingStore(dir).save { ReadingData(settings = Settings(fontSize = 30f)) }
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
        assertEquals(vm.book.value!!.title, vm.runningHead.value.title)
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
    fun `the running head names the Chapter holding the Page's start, and every turn stores the Place's progress`() {
        val vm = reading()
        val book = vm.book.value!!
        vm.nextPage()
        vm.nextPage()
        val shown = vm.frame.value!!
        val start = SpinePoint(shown.pass.item, shown.page.start)
        assertEquals(book.chapterAt(start)?.let { book.chapters[it].title } ?: book.title, vm.runningHead.value.title)
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
        assertEquals(book.chapters[book.chapterAt(place)!!].title, vm.runningHead.value.title)
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
            assertEquals(vm.book.value!!.title, vm.runningHead.value.title)
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
    fun `a volume key turns and hides the controls, and a jump from Contents hides them, but a font change and opening Contents keep them`() {
        val vm = reading()
        val first = vm.pageStart
        vm.showControls()
        vm.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(vm.controls.value)
        assertTrue(vm.pageStart > first)
        vm.showControls()
        vm.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(vm.controls.value)
        assertEquals(first, vm.pageStart)
        vm.showControls()
        vm.changeFont(+1)
        settle()
        assertTrue(vm.controls.value)
        assertNotNull(vm.openContents())
        assertTrue(vm.controls.value, "back from Contents shows the Page as it was, controls and all")
        vm.jumpTo(SpinePoint(0, 0))
        assertFalse(vm.controls.value)
    }

    @Test
    fun `a tap turn and the Tool pausing hide the controls`() {
        val vm = reading()
        vm.showControls()
        vm.nextPage()
        assertFalse(vm.controls.value)
        vm.showControls()
        vm.previousPage()
        assertFalse(vm.controls.value)
        vm.showControls()
        vm.onAppPause()
        assertFalse(vm.controls.value)
    }

    @Test
    fun `the reading view keeps the screen on from showing until KEEP_AWAKE_MS pass with no tap or key`() {
        val vm = reading()
        assertFalse(vm.keepAwake.value)
        vm.shown()
        assertTrue(vm.keepAwake.value)
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value)
        idleFor(1)
        assertFalse(vm.keepAwake.value)
    }

    @Test
    fun `a tap or a volume key restarts the time, and after it runs out the next one takes the screen again`() {
        val vm = reading()
        vm.shown()
        idleFor(KEEP_AWAKE_MS - 1)
        vm.stayAwake()
        idleFor(KEEP_AWAKE_MS - 1)
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        idleFor(KEEP_AWAKE_MS - 1)
        vm.press(KeyEvent.KEYCODE_VOLUME_UP)
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value)
        idleFor(1)
        assertFalse(vm.keepAwake.value)
        vm.stayAwake()
        assertTrue(vm.keepAwake.value)
        idleFor(KEEP_AWAKE_MS)
        assertFalse(vm.keepAwake.value)
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertTrue(vm.keepAwake.value)
    }

    @Test
    fun `the end page keeps the screen on too, and volume down there restarts the time though it doesn't turn`() {
        val vm = reading()
        vm.toLastPage()
        vm.nextPage()
        assertTrue(vm.atEnd.value)
        vm.shown()
        idleFor(KEEP_AWAKE_MS - 1)
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertTrue(vm.atEnd.value)
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value)
        idleFor(1)
        assertFalse(vm.keepAwake.value)
    }

    @Test
    fun `the Tool pausing lets the screen go, no tap or key takes it while paused, and resuming takes it for a fresh KEEP_AWAKE_MS`() {
        val vm = reading()
        vm.stayAwake()
        assertFalse(vm.keepAwake.value, "the reading view hasn't shown yet")
        vm.shown()
        idleFor(KEEP_AWAKE_MS / 2)
        vm.onAppPause()
        assertFalse(vm.keepAwake.value)
        vm.stayAwake()
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertFalse(vm.keepAwake.value)
        vm.shown()
        assertTrue(vm.keepAwake.value)
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value)
        idleFor(1)
        assertFalse(vm.keepAwake.value)
    }

    @Test
    fun `a screen reader's click, with no pointer press, restarts the time too, a blocked one included, and pausing lets the screen go`() {
        val vm = reading()
        vm.shown()
        val clicks = listOf<() -> Unit>(
            vm::tapNext, vm::tapMiddle, vm::tapBack, vm::showControls, vm::hideControls,
            { vm.changeFont(1) }, { vm.openContents() }, { vm.jumpTo(SpinePoint(0, 0)) },
        )
        for (click in clicks) {
            idleFor(KEEP_AWAKE_MS - 1)
            assertTrue(vm.keepAwake.value)
            click()
        }
        assertEquals(HintStep.Controls, vm.readingHint.value, "the middle tap came while the guide showed back")
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value)
        idleFor(1)
        assertFalse(vm.keepAwake.value)
        vm.shown()
        vm.showControls()
        vm.onAppPause()
        assertFalse(vm.keepAwake.value)
        assertFalse(vm.controls.value)
        idleFor(KEEP_AWAKE_MS)
        assertFalse(vm.keepAwake.value)
    }

    private val hintSaved: Boolean get() = ReadingStore(dir).load().settings.readingHintDismissed

    /** A [reading] reader whose view shows, [HINT_DELAY_MS] on, so the hint's first step is on screen. */
    private fun guided(): ReaderViewModel = reading().also {
        it.shown()
        idleFor(HINT_DELAY_MS)
    }

    @Test
    fun `the first-run hint walks next, back, then the middle, each step moved on only by its own zone's tap`() {
        val vm = reader()
        assertNull(vm.readingHint.value)
        vm.shown()
        settle()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value, "not while Opening…, before a Page shows")
        vm.bind(LineMeasurer())
        settle()
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        idleFor(1)
        assertEquals(HintStep.Next, vm.readingHint.value)
        val first = vm.spinePoint.value
        vm.tapNext()
        assertNotEquals(first, vm.spinePoint.value)
        assertNull(vm.readingHint.value, "the next step waits HINT_DELAY_MS too")
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        idleFor(1)
        assertEquals(HintStep.Back, vm.readingHint.value)
        vm.tapBack()
        assertEquals(first, vm.spinePoint.value)
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Controls, vm.readingHint.value)
        settle()
        assertFalse(hintSaved, "saved only at the middle tap")
        vm.tapMiddle()
        assertTrue(vm.controls.value)
        assertNull(vm.readingHint.value)
        settle()
        assertTrue(hintSaved)
        vm.hideControls()
        idleFor(HINT_DELAY_MS)
        vm.tapNext()
        vm.tapBack()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
    }

    @Test
    fun `a tap in the step's zone before the guide appears does what it always does but doesn't move the hint on`() {
        val vm = reading()
        vm.shown()
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        val first = vm.spinePoint.value
        vm.tapNext()
        assertNotEquals(first, vm.spinePoint.value, "the tap still turns")
        idleFor(1)
        assertEquals(HintStep.Next, vm.readingHint.value, "on time, and still the first step")
        vm.tapNext()
        idleFor(HINT_DELAY_MS - 1)
        val second = vm.spinePoint.value
        vm.tapBack()
        assertNotEquals(second, vm.spinePoint.value)
        idleFor(1)
        assertEquals(HintStep.Back, vm.readingHint.value)
        vm.tapBack()
        idleFor(HINT_DELAY_MS - 1)
        vm.tapMiddle()
        assertTrue(vm.controls.value, "the tap still shows the controls")
        vm.hideControls()
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Controls, vm.readingHint.value)
        settle()
        assertFalse(hintSaved)
    }

    @Test
    fun `the controls hiding waits HINT_DELAY_MS again before the step shows`() {
        val vm = guided()
        assertEquals(HintStep.Next, vm.readingHint.value)
        vm.showControls()
        assertNull(vm.readingHint.value)
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value, "never while the controls show")
        vm.hideControls()
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        vm.tapNext()
        idleFor(1)
        assertEquals(HintStep.Next, vm.readingHint.value, "a tap before it showed didn't move it on")
    }

    /** Asserts a press did nothing: [vm] still at [point] with the stored Place [place], no controls, at [step]. */
    private fun assertUnmoved(vm: ReaderViewModel, point: SpinePoint, place: Place?, step: HintStep) {
        assertEquals(point, vm.spinePoint.value)
        assertEquals(place, stored(vm).place)
        assertFalse(vm.controls.value)
        assertEquals(step, vm.readingHint.value)
    }

    @Test
    fun `while the guide shows a tap on another zone does nothing, and the step's own zone acts and moves it on`() {
        val vm = guided()
        val first = vm.spinePoint.value
        val firstPlace = stored(vm).place
        vm.tapBack()
        vm.tapMiddle()
        assertUnmoved(vm, first, firstPlace, HintStep.Next)
        vm.tapNext()
        val second = vm.spinePoint.value
        assertNotEquals(first, second)
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Back, vm.readingHint.value)
        val secondPlace = stored(vm).place
        vm.tapNext()
        vm.tapMiddle()
        assertUnmoved(vm, second, secondPlace, HintStep.Back)
        vm.tapBack()
        assertEquals(first, vm.spinePoint.value)
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Controls, vm.readingHint.value)
        val backPlace = stored(vm).place
        vm.tapNext()
        vm.tapBack()
        assertUnmoved(vm, first, backPlace, HintStep.Controls)
        assertFalse(hintSaved)
        vm.tapMiddle()
        assertTrue(vm.controls.value)
        settle()
        assertTrue(hintSaved)
    }

    @Test
    fun `while the guide shows the volume keys do nothing, yet are still consumed and keep the screen on`() {
        val vm = guided()
        val first = vm.spinePoint.value
        val place = stored(vm).place
        idleFor(KEEP_AWAKE_MS - HINT_DELAY_MS - 1)
        assertTrue(vm.keepAwake.value)
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertTrue(vm.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key)))
            assertTrue(vm.onKeyUp(key, KeyEvent(KeyEvent.ACTION_UP, key)))
            assertTrue(vm.onKeyMultiple(key, 2, KeyEvent(KeyEvent.ACTION_MULTIPLE, key)))
        }
        assertUnmoved(vm, first, place, HintStep.Next)
        idleFor(KEEP_AWAKE_MS - 1)
        assertTrue(vm.keepAwake.value, "a blocked key still restarts the time")
        idleFor(1)
        assertFalse(vm.keepAwake.value)
        vm.tapNext()
        idleFor(HINT_DELAY_MS)
        val second = vm.spinePoint.value
        vm.press(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(second, vm.spinePoint.value)
        assertEquals(HintStep.Back, vm.readingHint.value)
    }

    @Test
    fun `the volume keys turn before the guide appears and after the hint ends`() {
        val vm = reading()
        vm.shown()
        idleFor(HINT_DELAY_MS - 1)
        val first = vm.spinePoint.value
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        val second = vm.spinePoint.value
        assertNotEquals(first, second)
        vm.press(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(first, vm.spinePoint.value)
        idleFor(1)
        assertEquals(HintStep.Next, vm.readingHint.value, "the keys didn't move it on")
        vm.tapNext()
        idleFor(HINT_DELAY_MS)
        vm.tapBack()
        idleFor(HINT_DELAY_MS)
        vm.tapMiddle()
        vm.hideControls()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(second, vm.spinePoint.value)
        vm.press(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(first, vm.spinePoint.value)
        vm.tapMiddle()
        assertTrue(vm.controls.value, "every tap acts again")
    }

    @Test
    fun `a back tap at step two on the Book's first Page moves the hint on, though it can't turn`() {
        val vm = guided()
        vm.tapNext()
        idleFor(HINT_DELAY_MS)
        vm.jumpTo(SpinePoint(0, 0))
        val first = vm.spinePoint.value
        vm.tapBack()
        assertEquals(first, vm.spinePoint.value)
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Controls, vm.readingHint.value)
    }

    @Test
    fun `once dismissed at the middle tap the hint never shows again, not even in a later session`() {
        val vm = guided()
        vm.tapNext()
        idleFor(HINT_DELAY_MS)
        vm.tapBack()
        idleFor(HINT_DELAY_MS)
        vm.tapMiddle()
        settle()
        vm.onAppPause()
        settle()
        ShelfOwner.forget(dir)
        val next = guided()
        assertNull(next.readingHint.value)
        next.tapNext()
        next.tapBack()
        next.tapMiddle()
        next.onAppPause()
        settle()
        next.shown()
        idleFor(HINT_DELAY_MS)
        assertNull(next.readingHint.value)
        assertTrue(hintSaved)
    }

    @Test
    fun `the Tool pausing mid-walkthrough saves nothing, and the hint starts again from its first step`() {
        val vm = guided()
        vm.tapNext()
        idleFor(HINT_DELAY_MS)
        vm.tapBack()
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Controls, vm.readingHint.value)
        vm.onAppPause()
        assertNull(vm.readingHint.value)
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value, "never while paused")
        settle()
        assertFalse(hintSaved)
        vm.shown()
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        idleFor(1)
        assertEquals(HintStep.Next, vm.readingHint.value)
        ShelfOwner.forget(dir)
        assertEquals(HintStep.Next, guided().readingHint.value)
    }

    /**
     * A [guided] reader at the hint's second step, waiting out its delay, with the controls opened before it
     * showed, then in Contents and back as the SDK does it: hidden, then shown with the controls still up.
     */
    private fun backFromContents(): ReaderViewModel = guided().also { vm ->
        vm.tapNext()
        idleFor(HINT_DELAY_MS - 1)
        vm.tapMiddle()
        assertTrue(vm.controls.value)
        assertNotNull(vm.openContents())
        vm.hidden()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value, "not while in Contents")
        vm.shown()
        idleFor(HINT_DELAY_MS)
        assertTrue(vm.controls.value)
        assertNull(vm.readingHint.value, "not while the controls show")
    }

    @Test
    fun `mid-walkthrough, a jump from Contents hides the controls and the step returns HINT_DELAY_MS later`() {
        val vm = backFromContents()
        vm.jumpTo(SpinePoint(0, 0))
        assertFalse(vm.controls.value)
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        idleFor(1)
        assertEquals(HintStep.Back, vm.readingHint.value)
    }

    @Test
    fun `mid-walkthrough, plain back from Contents shows no step until the controls hide, then HINT_DELAY_MS later`() {
        val vm = backFromContents()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
        vm.hideControls()
        idleFor(HINT_DELAY_MS - 1)
        assertNull(vm.readingHint.value)
        idleFor(1)
        assertEquals(HintStep.Back, vm.readingHint.value)
    }

    @Test
    fun `a dev-start session never shows the hint`() {
        val vm = reader(DevStart(2))
        vm.shown()
        settle()
        vm.bind(LineMeasurer())
        settle()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
        vm.tapNext()
        vm.tapBack()
        vm.tapMiddle()
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
    }

    @Test
    fun `the end page shows no hint, and a tap there doesn't move it on, which the back turn off it shows again`() {
        val vm = guided()
        vm.toLastPage()
        vm.tapNext()
        assertTrue(vm.atEnd.value)
        assertNull(vm.readingHint.value)
        idleFor(HINT_DELAY_MS)
        assertNull(vm.readingHint.value)
        vm.tapBack()
        assertFalse(vm.atEnd.value)
        assertNull(vm.readingHint.value)
        idleFor(HINT_DELAY_MS)
        assertEquals(HintStep.Back, vm.readingHint.value)
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
        ReadingStore(dir).save { ReadingData(settings = Settings(fontSize = 24.5f)) }
        val vm = reader()
        vm.openBook()
        main.scheduler.runCurrent()
        io.scheduler.runCurrent()
        vm.openBook()
        main.scheduler.runCurrent()
        assertEquals(24.5f, FONT_SIZES[vm.fontStep.value])
        vm.changeFont(+1)
        settle()
        assertEquals(30f, FONT_SIZES[vm.fontStep.value])
        assertEquals(30f, ReadingStore(dir).load().settings.fontSize)
    }

    @Test
    fun `A− at 17 sp reaches 15 sp, saves it, and stops there`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontSize = 17f)) }
        val vm = reading()
        assertEquals(17f, FONT_SIZES[vm.fontStep.value])
        vm.changeFont(-1)
        settle()
        assertEquals(15f, FONT_SIZES[vm.fontStep.value])
        assertEquals(15f, ReadingStore(dir).load().settings.fontSize)
        assertFalse(canChangeFont(vm.fontStep.value, -1))
        vm.changeFont(-1)
        settle()
        assertEquals(0, vm.fontStep.value)
        assertEquals(15f, ReadingStore(dir).load().settings.fontSize)
    }

    @Test
    fun `A− does nothing at the smallest size and A+ nothing at the largest, so the controls show them disabled`() {
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
        assertEquals(24.5f, FONT_SIZES[vm.fontStep.value])
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
        idleFor(OPENING_DELAY_MS + OPENING_MIN_SHOWN_MS)
        assertFalse(vm.showsOpening.value, "nor \"Opening…\" later")
    }

    /** A reader whose open has just started, "Opening…" timed on [idle] from now; [settle] finishes the open. */
    private fun opening(): ReaderViewModel = reader().also { it.openBook() }

    @Test
    fun `a quick open never shows "Opening…", only the blank reading view, then the Page`() {
        val vm = opening()
        idleFor(OPENING_DELAY_MS - 1)
        assertFalse(vm.showsOpening.value)
        settle()
        assertNotNull(vm.book.value)
        idleFor(1)
        assertFalse(vm.showsOpening.value, "nothing shows at 500 ms, the Book being in")
        idleFor(OPENING_DELAY_MS + OPENING_MIN_SHOWN_MS)
        assertFalse(vm.showsOpening.value)
    }

    @Test
    fun `an open done at 550 ms shows "Opening…" from 500 ms and holds the Page back until 1,000 ms`() {
        val vm = opening()
        idleFor(OPENING_DELAY_MS - 1)
        assertFalse(vm.showsOpening.value)
        idleFor(1)
        assertTrue(vm.showsOpening.value, "shows at 500 ms")
        idleFor(50)
        settle()
        assertNotNull(vm.book.value)
        assertTrue(vm.showsOpening.value, "the Book is in at 550 ms, and the copy stays")
        idleFor(OPENING_DELAY_MS + OPENING_MIN_SHOWN_MS - 550 - 1)
        assertTrue(vm.showsOpening.value)
        idleFor(1)
        assertFalse(vm.showsOpening.value, "the Page at 1,000 ms")
        vm.bind(LineMeasurer())
        settle()
        assertNotNull(vm.frame.value)
    }

    @Test
    fun `a slow open shows "Opening…" from 500 ms and the Page as soon as the Book is in`() {
        val vm = opening()
        idleFor(OPENING_DELAY_MS)
        assertTrue(vm.showsOpening.value)
        idleFor(2_000 - OPENING_DELAY_MS)
        assertTrue(vm.showsOpening.value, "still opening at 2 s")
        settle()
        assertNotNull(vm.book.value)
        assertFalse(vm.showsOpening.value)
    }

    @Test
    fun `"Couldn't open this Book" replaces "Opening…" at once, and a quick failure shows no "Opening…" first`() {
        File(dir, "alice.epub").writeText("not a zip")
        val slow = opening()
        idleFor(OPENING_DELAY_MS)
        assertTrue(slow.showsOpening.value)
        settle()
        assertEquals(READING_COULDNT_OPEN, slow.status.value)
        assertFalse(slow.showsOpening.value)
        val quick = opening()
        settle()
        assertEquals(READING_COULDNT_OPEN, quick.status.value)
        idleFor(OPENING_DELAY_MS + OPENING_MIN_SHOWN_MS)
        assertFalse(quick.showsOpening.value)
    }

    @Test
    fun `a lazy open whose first step ends inside the hold and whose rest then fails shows "Couldn't open this Book" and no "Opening…" at once`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<h2>One</h2><p>${"word ".repeat(200)}</p>", "<p>never closed")))
        val vm = reader()
        vm.shown()
        idleFor(600)
        assertTrue(vm.showsOpening.value)
        firstStep()
        assertNotNull(vm.book.value, "the first step is done")
        assertTrue(vm.showsOpening.value, "and the copy holds at 600 ms")
        settle()
        assertEquals(READING_COULDNT_OPEN, vm.status.value)
        assertFalse(vm.showsOpening.value)
    }

    @Test
    fun `"This Book has no text" replaces "Opening…" at once`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<p></p>")))
        val vm = opening()
        idleFor(OPENING_DELAY_MS)
        assertTrue(vm.showsOpening.value)
        settle()
        assertEquals(emptyList(), vm.book.value!!.spineItems)
        assertFalse(vm.showsOpening.value)
    }

    @Test
    fun `pausing, hiding and showing again mid-open neither restart nor strand "Opening…"`() {
        val vm = reader()
        vm.shown()
        idleFor(OPENING_DELAY_MS)
        assertTrue(vm.showsOpening.value)
        vm.onAppPause()
        vm.hidden()
        vm.shown()
        assertTrue(vm.showsOpening.value, "showing again starts no second wait")
        settle()
        assertNotNull(vm.book.value)
        idleFor(OPENING_MIN_SHOWN_MS - 1)
        assertTrue(vm.showsOpening.value)
        idleFor(1)
        assertFalse(vm.showsOpening.value)
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
        assertEquals(BackMatterTest.LICENSE_HEADING, runningHead.value.title)
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
        assertEquals(book.title, vm.runningHead.value.title)
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
        assertEquals("Chapter I.", vm.runningHead.value.title)
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
        assertEquals(RunningHead("BOOK ONE", "Chapter I."), vm.runningHead.value)
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
        assertEquals("Two", vm.runningHead.value.title)
        assertEquals(1, vm.openContents()!!.current)
        val landed = vm.frame.value!!
        vm.jumpToChapter(1)
        assertSame(landed.pass, vm.frame.value!!.pass)
    }

    /**
     * The same Chapter, its line now starting mid-word (the tail of a hyphenated word): a jump is exempt
     * from moving a Page's start up to a whole word, so the Page still starts on the Chapter's line, the
     * running head and Contents name the Chapter, and the Place is that line.
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
        assertEquals("Two", vm.runningHead.value.title)
        assertEquals(1, vm.openContents()!!.current)
        assertEquals(vm.pageStart, vm.spinePoint.value)
    }

    /**
     * After that jump a font change re-packs at the Place, the Chapter's start, on a line that starts
     * mid-word again. The walk up to a whole word stops at the line holding the Chapter's start, so the
     * Page doesn't open in the Chapter before: the running head, Contents and the Place stay the Chapter's.
     * It starts at 17 sp, where a [LineMeasurer] line starts at the Chapter's start, so the jump's Page does.
     */
    @Test
    fun `a font change after a jump to a Chapter keeps its Page, running head and Contents on that Chapter`() {
        val bodies = listOf(
            "<h1>One</h1><p>First.</p>",
            "<p>${"word ".repeat(400)}<span id=\"two\">Two starts</span> ${"word ".repeat(600)}</p>",
        )
        File(dir, "alice.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx(navPoint("One", "text/c0.xhtml"), navPoint("Two", "text/c1.xhtml#two"))))
        ReadingStore(dir).save { ReadingData(settings = Settings(fontSize = 17f)) }
        val vm = reader()
        vm.openBook()
        settle()
        val opened = vm.book.value!!
        val two = opened.chapters[1].start
        vm.bind(LineMeasurer(tailAt = opened.spineItems[two.item].spineId to two.char))
        settle()
        vm.jumpToChapter(1)
        assertEquals(two, vm.pageStart)
        assertEquals("Two", vm.runningHead.value.title)
        vm.changeFont(+1)
        settle()
        val shown = vm.frame.value!!
        assertTrue(vm.pageStart <= two && two.char < shown.pass.firstLineEnd(shown.page), "the Page's first line holds $two")
        assertEquals("Two", vm.runningHead.value.title)
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
        assertEquals("Two", vm.runningHead.value.title)
        vm.changeFont(+1)
        settle()
        assertEquals(SpinePoint(0, heading), vm.pageStart)
        assertEquals("Two", vm.runningHead.value.title)
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
        assertEquals("Chapter I.", vm.runningHead.value.title)
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
        assertEquals("Book One", vm.runningHead.value.title)
        assertEquals(1, vm.openContents()!!.current)
        vm.jumpToChapter(2)
        assertEquals("Chapter I", vm.runningHead.value.title)
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
        assertEquals("Book One", vm.runningHead.value.title)
        vm.changeFont(+1)
        settle()
        assertTrue(vm.pageStart < opened.chapters[1].start, "the Page opens on the heading above the Part's")
        assertEquals("Book One", vm.runningHead.value.title)
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
        assertEquals("Two", vm.runningHead.value.title)
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
        assertEquals("Two", vm.runningHead.value.title)
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
        assertEquals("One", vm.runningHead.value.title)
        assertEquals(1, vm.openContents()!!.current)
    }

    /** Runs a lazy open's first step only: the Place's Spine item parsed and shown, the rest of the Book still waiting on IO. */
    private fun firstStep() {
        main.scheduler.advanceUntilIdle()
        io.scheduler.advanceUntilIdle()
        main.scheduler.advanceUntilIdle()
    }

    /** A reader opened as far as its first step, bound to [measurer]: a lazy open showing its one Spine item. */
    private fun lazyReader(measurer: LineMeasurer = LineMeasurer(), start: DevStart? = null): ReaderViewModel {
        val vm = reader(start)
        vm.shown()
        firstStep()
        vm.bind(measurer)
        main.scheduler.advanceUntilIdle()
        return vm
    }

    /** Stores a Place at [offset] in the Book's Spine item [item], or [fromEnd] characters before its end; returns the Book and the offset. */
    private fun storePlace(item: Int, offset: Int? = null, fromEnd: Int? = null): Pair<OpenBook, Int> {
        val book = parseEpub(File(dir, "alice.epub"))
        val spineItem = book.spineItems[item]
        val at = offset ?: (spineItem.text.length - fromEnd!!)
        ReadingStore(dir).save {
            ReadingData().shelve(book.identifier, book.title, "alice.epub").withPlace(book.identifier, spineItem.placeOf(at, 1))
        }
        return book to at
    }

    /** A Reading of the whole Book in dir's alice.epub, as a whole parse lays it out with [measurer]: what a lazy open must match. */
    private fun eagerReading(measurer: LineMeasurer = LineMeasurer()): Reading<WindowLayout> {
        val book = parseEpub(File(dir, "alice.epub"))
        return Reading(book.spineItems, measure = { pass, window -> measurer.measure(pass.spineItem, pass.windows[window], pass.key.fontStep) },
            linesOf = { it.lines }, textEnd = book.textEnd, chapterStarts = book.chapters.map { it.start })
    }

    private val key = LineMeasurer().key(DEFAULT_FONT_STEP)

    @Test
    fun `a lazy open shows the Place's Spine item alone, then the whole Book comes in under the identical Page, Place and running head`() {
        val (book, offset) = storePlace(3, offset = 2_000)
        val vm = lazyReader()
        assertEquals(listOf(book.spineItems[3]), vm.book.value!!.spineItems)
        val page = vm.frame.value!!.page
        val head = vm.runningHead.value
        assertNull(vm.progressLine.value, "no Progress line before the whole Book is in")
        settle()
        assertEquals(book, vm.book.value)
        assertEquals(SpinePoint(3, offset), vm.spinePoint.value)
        assertEquals(3, vm.frame.value!!.pass.item)
        assertSame(page, vm.frame.value!!.page)
        assertEquals(eagerReading().open(3, offset, key).page, page)
        assertEquals(head, vm.runningHead.value)
        assertNotNull(vm.progressLine.value)
    }

    @Test
    fun `before the whole Book is in, a turn off the Spine item waits, the turns after it queue in order, and they all turn once it is in`() {
        val (_, offset) = storePlace(3, fromEnd = 10)
        val vm = lazyReader()
        val before = vm.frame.value
        vm.nextPage()
        vm.previousPage()
        vm.previousPage()
        assertSame(before, vm.frame.value, "nothing turned yet, not even the back turns within the Spine item")
        settle()
        val eager = eagerReading()
        val last = eager.open(3, offset, key)
        assertEquals(4, eager.next()!!.pass.item)
        assertEquals(last.page, eager.previous()!!.page)
        val expected = eager.previous()!!
        assertEquals(SpinePoint(3, expected.page.start), vm.spinePoint.value)
        assertEquals(3, vm.frame.value!!.pass.item)
        assertEquals(expected.page, vm.frame.value!!.page)
    }

    @Test
    fun `before the whole Book is in, turns and font changes within the Spine item work, and its Place is written only once the Book is in`() {
        val (book, offset) = storePlace(3, offset = 2_000)
        val storedPlace = ReadingStore(dir).load().books.getValue(book.identifier).place
        val vm = lazyReader()
        vm.nextPage()
        val turned = vm.frame.value!!.page
        assertTrue(turned.start > offset)
        assertEquals(SpinePoint(0, turned.start), vm.spinePoint.value)
        vm.changeFont(+1)
        val resized = vm.frame.value!!
        assertEquals(DEFAULT_FONT_STEP + 1, resized.pass.key.fontStep)
        assertEquals(storedPlace, vm.saver.data.books.getValue(book.identifier).place, "the turn's Place waits for the whole Book")
        settle()
        assertSame(resized.page, vm.frame.value!!.page)
        assertEquals(SpinePoint(3, turned.start), vm.spinePoint.value)
        val place = stored(vm).place!!
        assertEquals(book.placeAt(SpinePoint(3, turned.start), place.updatedAt), place)
        assertEquals(book.progressAt(SpinePoint(3, turned.start)), place.progress)
    }

    @Test
    fun `before the whole Book is in, the end page never shows, as the turn onto it waits, then shows it and sets Finished`() {
        val footer = """<div id="pg-footer"><h2>${BackMatterTest.LICENSE_HEADING}</h2><p>${"terms ".repeat(2_000)}</p></div>"""
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<h2>I</h2><p>${"word ".repeat(3_000)}</p>", "<h2>II</h2><p>${"more ".repeat(3_000)}</p>$footer")))
        val textEnd = parseEpub(File(dir, "alice.epub")).textEnd
        assertEquals(1, textEnd.item)
        storePlace(1, offset = textEnd.char - 10)
        val vm = lazyReader()
        assertTrue(vm.frame.value!!.page.end < vm.frame.value!!.pass.length, "the license follows on in the Spine item")
        vm.nextPage()
        assertFalse(vm.atEnd.value)
        settle()
        assertTrue(vm.atEnd.value)
        assertTrue(stored(vm).finished)
    }

    @Test
    fun `Contents asked for before the whole Book is in opens once it is in, once however many taps, and not once the reader has left`() {
        storePlace(3, offset = 2_000)
        val vm = lazyReader()
        val opened = mutableListOf<Contents>()
        vm.requestContents { opened += it }
        vm.requestContents { opened += it }
        assertEquals(emptyList(), opened)
        settle()
        assertEquals(listOf(vm.openContents()!!), opened)
        assertEquals(opened.single().rows.indexOfFirst { it.title == vm.runningHead.value.title }, opened.single().current)

        val left = lazyReader()
        var shown = false
        left.requestContents { shown = true }
        left.hidden()
        settle()
        assertFalse(shown)
        left.shown()
        left.requestContents { shown = true }
        assertTrue(shown, "once the Book is in, Contents opens at once")
    }

    @Test
    fun `Contents asked for before the whole Book is in is dropped when the app pauses, though the Reader shows again before the swap`() {
        storePlace(3, offset = 2_000)
        val vm = lazyReader()
        var shown = false
        vm.requestContents { shown = true }
        vm.onAppPause()
        vm.shown()
        settle()
        assertTrue(vm.book.value!!.spineItems.size > 1, "the whole Book came in")
        assertFalse(shown)
    }

    @Test
    fun `a never-opened Book opens lazily at its start, and its first Place is written once the whole Book is in`() {
        val vm = reader()
        vm.shown()
        firstStep()
        assertEquals(1, vm.book.value!!.spineItems.size)
        val identifier = vm.book.value!!.identifier
        assertNull(vm.saver.data.books[identifier]?.place)
        settle()
        assertEquals(0.0, stored(vm).place!!.progress)
        assertEquals(vm.book.value!!.placeAt(SpinePoint(0, 0), stored(vm).place!!.updatedAt), stored(vm).place)
    }

    /** A Book that marks no body matter, opening on a document typed as a title page, which the whole Book therefore keeps. */
    private fun writeUnmarkedTitlePage() = File(dir, "alice.epub").writeEpub(tocEpubFiles(
        listOf("""<section epub:type="titlepage"><h1>A Book</h1></section>""", "<h2>One</h2><p>${"word ".repeat(2_000)}</p>", "<h2>Two</h2><p>The end.</p>"),
        ncx = ncx(navPoint("One", "text/c1.xhtml"), navPoint("Two", "text/c2.xhtml")),
    ))

    @Test
    fun `a never-opened Book led by a title page it keeps opens past it first, then on it once the whole Book is in, as a whole parse opens`() {
        writeUnmarkedTitlePage()
        val vm = lazyReader()
        assertEquals("c1", vm.book.value!!.spineItems.single().spineId)
        settle()
        assertEquals(SpinePoint(0, 0), vm.spinePoint.value)
        assertEquals("c0", vm.book.value!!.spineItems[vm.frame.value!!.pass.item].spineId)
        assertEquals(0, vm.frame.value!!.page.start)
        assertEquals("c0", stored(vm).place!!.spineId)
    }

    @Test
    fun `a never-opened Book led by a title page it keeps stays where a turn took the reader before the whole Book came in`() {
        writeUnmarkedTitlePage()
        val vm = lazyReader()
        vm.nextPage()
        val turned = vm.frame.value!!.page
        settle()
        assertEquals(SpinePoint(1, turned.start), vm.spinePoint.value)
        assertSame(turned, vm.frame.value!!.page)
        assertEquals("c1", stored(vm).place!!.spineId)
    }

    @Test
    fun `a Place in a Spine item the whole Book drops shows that Spine item first, then the Book's start once the whole Book is in, as a whole parse opens`() {
        val book = parseEpub(File(dir, "alice.epub"))
        val imprint = EpubOpening(File(dir, "alice.epub"), "").use { it.placed("imprint.xhtml")!! }.spineItems.single()
        assertTrue(book.spineItems.none { it.spineId == imprint.spineId })
        val place = imprint.placeOf(10, 1)
        ReadingStore(dir).save { ReadingData().shelve(book.identifier, book.title, "alice.epub").withPlace(book.identifier, place) }
        val vm = lazyReader()
        assertEquals(listOf(imprint), vm.book.value!!.spineItems)
        settle()
        assertEquals(SpinePoint(0, 0), vm.spinePoint.value)
        assertEquals(0, vm.frame.value!!.pass.item)
        assertEquals(eagerReading().open(0, 0, key).page, vm.frame.value!!.page)
        assertEquals(place, stored(vm).place, "opening at a Place writes nothing")
    }

    @Test
    fun `a Spine item after the first Page that won't parse takes the Page away for Couldn't open this Book, and showing again doesn't retry`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<h2>One</h2><p>${"word ".repeat(200)}</p>", "<p>never closed")))
        val vm = lazyReader()
        assertNotNull(vm.frame.value)
        settle()
        assertNull(vm.book.value)
        assertNull(vm.frame.value)
        assertEquals(READING_COULDNT_OPEN, vm.status.value)
        vm.hidden()
        vm.shown()
        settle()
        assertNull(vm.book.value)
        assertEquals(READING_COULDNT_OPEN, vm.status.value)
    }

    @Test
    fun `a dev-start session naming its Spine item's id opens lazily there, and at that index in the whole Book once it is in`() {
        val book = parseEpub(File(dir, "alice.epub"))
        val vm = lazyReader(start = DevStart(3, 100, spineId = book.spineItems[3].spineId))
        assertEquals(listOf(book.spineItems[3]), vm.book.value!!.spineItems)
        assertEquals(SpinePoint(0, 100), vm.spinePoint.value)
        settle()
        assertEquals(SpinePoint(3, 100), vm.spinePoint.value)
        assertEquals(3, vm.frame.value!!.pass.item)
    }

    @Test
    fun `showing the controls drops a turn waiting for the whole Book, so none turns once it is in and the controls stay`() {
        val (_, offset) = storePlace(3, fromEnd = 10)
        val vm = lazyReader()
        val before = vm.frame.value!!.page
        vm.tapNext()
        vm.tapMiddle()
        assertTrue(vm.controls.value)
        settle()
        assertEquals(SpinePoint(3, offset), vm.spinePoint.value)
        assertEquals(3, vm.frame.value!!.pass.item)
        assertEquals(before, vm.frame.value!!.page)
        assertTrue(vm.controls.value)
    }

    @Test
    fun `a font change after a turn that waits, the controls shown between, lays out at the Place, and no turn follows once the Book is in`() {
        val (_, offset) = storePlace(3, fromEnd = 10)
        val vm = lazyReader()
        vm.nextPage()
        vm.tapMiddle()
        vm.changeFont(+1)
        assertEquals(DEFAULT_FONT_STEP + 1, vm.frame.value!!.pass.key.fontStep)
        settle()
        assertEquals(SpinePoint(3, offset), vm.spinePoint.value)
        assertEquals(eagerReading().open(3, offset, LineMeasurer().key(DEFAULT_FONT_STEP + 1)).page, vm.frame.value!!.page)
        assertTrue(vm.controls.value)
    }

    @Test
    fun `a turn while Contents waits for the whole Book drops the Contents, and the turn turns once the Book is in`() {
        storePlace(3, fromEnd = 10)
        val vm = lazyReader()
        vm.tapMiddle()
        var opened = false
        vm.requestContents { opened = true }
        vm.press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertFalse(vm.controls.value)
        settle()
        assertFalse(opened, "back from Contents would show another Page than it marked")
        assertEquals(4, vm.frame.value!!.pass.item)
    }

    @Test
    fun `a layout change while a turn waits for the whole Book drops the turn, and the Page stays at the Place`() {
        val (_, offset) = storePlace(3, fromEnd = 10)
        val vm = lazyReader()
        vm.nextPage()
        vm.bind(LineMeasurer(pageHeightPx = 600))
        settle()
        assertEquals(SpinePoint(3, offset), vm.spinePoint.value)
        assertEquals(eagerReading(LineMeasurer(pageHeightPx = 600)).open(3, offset, LineMeasurer(pageHeightPx = 600).key(DEFAULT_FONT_STEP)).page, vm.frame.value!!.page)
    }

    /** A Gutenberg-shaped Book in Alice's file, its license 18 K characters into its last Spine item, Finished with its Place [intoLicense] characters into the license. */
    private fun finishedInLicense(intoLicense: Int): OpenBook {
        val footer = """<div id="pg-footer"><h2>${BackMatterTest.LICENSE_HEADING}</h2><p>${"terms ".repeat(3_000)}</p></div>"""
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<h2>I</h2><p>${"word ".repeat(3_000)}</p>", "<h2>II</h2><p>${"more ".repeat(3_000)}</p>$footer")))
        val book = parseEpub(File(dir, "alice.epub"))
        assertEquals(1, book.textEnd.item)
        val place = book.placeAt(SpinePoint(1, book.textEnd.char + intoLicense), 1)
        ReadingStore(dir).save { ReadingData().shelve(book.identifier, book.title, "alice.epub").withFinished(book.identifier, true, place) }
        return book
    }

    @Test
    fun `a back turn inside Back matter before the whole Book is in keeps Finished, a Spine item's index not being the whole Book's`() {
        val book = finishedInLicense(12_000)
        val vm = lazyReader()
        vm.previousPage()
        val landed = vm.frame.value!!.page
        assertTrue(landed.start > book.textEnd.char, "the turn stays in the license")
        settle()
        val saved = stored(vm)
        assertTrue(saved.finished)
        assertEquals(book.placeAt(SpinePoint(1, landed.start), saved.place!!.updatedAt), saved.place)
    }

    @Test
    fun `a back turn from Back matter onto the text before the whole Book is in clears Finished once it is in`() {
        val book = finishedInLicense(10)
        val vm = lazyReader()
        assertEquals(book.textEnd.char, vm.frame.value!!.page.start)
        vm.previousPage()
        assertTrue(vm.frame.value!!.page.start < book.textEnd.char)
        settle()
        assertFalse(stored(vm).finished)
    }

    @Test
    fun `a never-opened Book led by a title page it keeps stays where a waiting turn took the reader, that turn having moved off its start`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(
            listOf("""<section epub:type="titlepage"><h1>A Book</h1></section>""", "<h2>One</h2><p>Short.</p>", "<h2>Two</h2><p>The end.</p>"),
            ncx = ncx(navPoint("One", "text/c1.xhtml"), navPoint("Two", "text/c2.xhtml")),
        ))
        val vm = lazyReader()
        assertEquals("c1", vm.book.value!!.spineItems.single().spineId)
        vm.nextPage()
        settle()
        assertEquals("c2", vm.book.value!!.spineItems[vm.frame.value!!.pass.item].spineId)
        assertEquals("c2", stored(vm).place!!.spineId)
    }

    @Test
    fun `the whole Book coming in lays the Page out afresh at the Place when it floors the Page otherwise, as a table of contents of one entry does`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(
            listOf("<h2>A</h2><p>${"word ".repeat(500)}</p>", """<p>${"lead ".repeat(1_000)}</p><h2 id="b">B</h2><p>${"more ".repeat(3_000)}</p>"""),
            ncx = ncx(navPoint("B", "text/c1.xhtml#b")),
        ))
        assertEquals(listOf(SpinePoint(0, 0), SpinePoint(1, 0)), parseEpub(File(dir, "alice.epub")).chapters.map { it.start }, "one entry isn't usable")
        val (_, offset) = storePlace(1, offset = 9_000)
        val vm = lazyReader()
        assertEquals(1, vm.book.value!!.chapters.size, "the Spine item alone takes its one entry")
        val before = vm.frame.value!!
        settle()
        assertNotSame(before.pass, vm.frame.value!!.pass)
        assertEquals(SpinePoint(1, offset), vm.spinePoint.value)
        assertEquals(eagerReading().open(1, offset, key).page, vm.frame.value!!.page)
    }

    @Test
    fun `a Book that couldn't be opened before its first Page is opened again when the Reader shows again`() {
        val epub = File(dir, "alice.epub").readBytes()
        File(dir, "alice.epub").writeText("not a zip")
        val vm = reader()
        vm.shown()
        settle()
        assertEquals(READING_COULDNT_OPEN, vm.status.value)
        assertFalse(vm.showsOpening.value)
        File(dir, "alice.epub").writeBytes(epub)
        vm.hidden()
        vm.shown()
        assertFalse(vm.showsOpening.value, "the retry's wait starts afresh")
        idleFor(OPENING_DELAY_MS)
        assertTrue(vm.showsOpening.value)
        settle()
        assertNotNull(vm.book.value)
    }

    @Test
    fun `a Spine item that won't parse after the first Page drops what waited, so Contents doesn't open, and neither the Shelf nor a Place is written`() {
        File(dir, "alice.epub").writeEpub(tocEpubFiles(listOf("<h2>One</h2><p>${"word ".repeat(3_000)}</p>", "<p>never closed")))
        val before = ReadingStore(dir).load()
        val vm = lazyReader()
        vm.nextPage()
        vm.tapMiddle()
        var opened = false
        vm.requestContents { opened = true }
        settle()
        assertEquals(READING_COULDNT_OPEN, vm.status.value)
        assertFalse(opened)
        assertEquals(before, ReadingStore(dir).load())
    }

    @Test
    fun `leaving the Reader while the rest of the Book is parsed shows no "Couldn't open", and writes neither the Shelf nor a Place`() {
        val before = ReadingStore(dir).load()
        val vm = lazyReader()
        vm.nextPage()
        vm.viewModelScope.cancel()
        settle()
        assertEquals(READING_OPENING, vm.status.value)
        assertEquals(1, vm.book.value!!.spineItems.size)
        assertEquals(before, ReadingStore(dir).load())
    }
}
