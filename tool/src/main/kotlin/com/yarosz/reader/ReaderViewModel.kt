package com.yarosz.reader

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logcat tag for layout timings; `scripts/perf.sh` parses these lines, so the key `chapters=` stays
 * as it is (it counts Spine items). `tocChapters=` counts the Book's Chapters.
 */
private const val PERF_TAG = "ReaderPerf"

private const val TAG = "Reader"

/** A parsed Book, its word index and the time it took, and its stored Place with where [resolve] found it. */
private data class Opened(val book: OpenBook, val words: WordIndex, val wordsNs: Long, val found: Pair<Place, SpinePoint?>?)

/** How long page turns and font changes settle before the reading data is saved. */
const val SAVE_DEBOUNCE_MS = 1_000L

/**
 * Reads the Book in [file], a view onto [owner] like the Shelf, so the Reader's Places and font size
 * reach the reading data the Shelf shows. [start] is a dev-start session's Place (see
 * [DEV_BOOK_FILE]), opened at the default font. [io] is where the Book is opened, and [now] is the
 * monotonic millis that time Pages for the reading speed; tests pass ones they control.
 */
class ReaderViewModel(
    private val file: File,
    private val owner: ShelfOwner,
    private val start: DevStart? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : LightViewModel<Unit>() {
    /**
     * The owner's saver, or in a dev-start session one that starts empty and writes nothing, so perf
     * and CI runs leave the device's reading data as they found it.
     */
    internal val saver = if (start == null) owner.saver else ReadingSaver(viewModelScope, io, SAVE_DEBOUNCE_MS, { }) { }

    val book = MutableStateFlow<OpenBook?>(null)
    val status = MutableStateFlow(READING_OPENING)

    /**
     * The Place: the top of the page being read, or, after a relayout, a few lines down it when its line
     * starts mid-word ([pack]). Relayouts never rewrite it, so font changes can't drift.
     */
    val spinePoint = MutableStateFlow(SpinePoint(0, 0))

    /** The font step, an index into [FONT_SIZES]; the reading data stores the size itself. */
    val fontStep = MutableStateFlow(DEFAULT_FONT_STEP)

    /** The Page to draw and the pass whose layouts draw it; null until the view binds a [Typesetter]. */
    val frame = MutableStateFlow<Shown<WindowLayout>?>(null)

    /**
     * Whether the end page is showing, after the last Page of the text, the one reaching [OpenBook.textEnd]
     * ([reachesEnd]). It is not a Page: [frame] and the Place stay on that last Page.
     */
    val atEnd = MutableStateFlow(false)

    /**
     * The controls' running head: the Chapter the Page goes by ([pagePoint]), Back matter's included, with
     * its Part ([runningHeadOf]), else (Front matter, the end page) the Book's Shelf title.
     */
    val runningHead = MutableStateFlow(RunningHead(null, ""))

    /** Whether the controls show over the Page. Every turn, opening Contents and the Tool pausing hide them. */
    val controls = MutableStateFlow(false)

    fun showControls() {
        controls.value = true
    }

    fun hideControls() {
        controls.value = false
    }

    /** The controls' Progress line ([minutesLine]); null for none. */
    val progressLine = MutableStateFlow<ProgressLine?>(null)

    /** The title the Shelf shows for the Book. */
    private var shelfTitle = ""
    private var words: WordIndex? = null
    private val timer = PageTimer(owner.speed)

    private var measurer: WindowMeasurer? = null
    private var reading: Reading<WindowLayout>? = null
    private var prefetching: Job? = null
    private var syncWindows = 0
    private var windowChars = WINDOW_CHARS

    private var loading: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = openBook()

    /** Opens the Book once: a show while the first open is still running (a pause and resume) starts no second one. */
    internal fun openBook() {
        if (book.value != null || loading?.isActive == true) return
        loading = viewModelScope.launch {
            owner.awaitLoaded()
            val stored = saver.data
            val parseStart = System.nanoTime()
            runCatching {
                withContext(io) {
                    val opened = parseEpub(file, stored.storedTitle(file.name) ?: file.nameWithoutExtension)
                    val wordsStart = System.nanoTime()
                    val index = WordIndex(opened.spineItems)
                    val wordsNs = System.nanoTime() - wordsStart
                    // Re-finding a Place by its text scans its Spine item, so it runs here, off the main thread.
                    val found = stored.books[opened.identifier]?.place?.let { it to opened.resolve(it) }
                    Opened(opened, index, wordsNs, found)
                }
            }
                .onSuccess { (opened, index, wordsNs, found) ->
                    val parseMs = ms(System.nanoTime() - parseStart - wordsNs)
                    val largest = opened.spineItems.indices.maxByOrNull { opened.spineItems[it].text.length }
                    if (largest != null) {
                        Log.i(PERF_TAG, "book chapters=${opened.spineItems.size} tocChapters=${opened.chapters.size} largest=$largest " +
                            "largestChars=${opened.spineItems[largest].text.length} parseMs=$parseMs wordsMs=${ms(wordsNs)}")
                    }
                    val openedAt = System.currentTimeMillis()
                    val title = saver.data.books[opened.identifier]?.title?.takeIf { it.isNotBlank() } ?: opened.title
                    shelfTitle = title
                    words = index
                    saver.change { it.shelve(opened.identifier, title, file.name, opened.author, now = openedAt) }
                    if (start == null) fontStep.value = nearestFontStep(saver.data.settings.fontSize)
                    if (start != null && opened.spineItems.isNotEmpty()) {
                        val item = start.item.coerceIn(opened.spineItems.indices)
                        windowChars = start.windowChars ?: WINDOW_CHARS
                        spinePoint.value = SpinePoint(item, start.char.coerceIn(0, opened.spineItems[item].text.length))
                        val ends = windows(opened.spineItems[item], windowChars, opened.textEnd.takeIf { it.item == item }?.char).joinToString(",") { it.end.toString() }
                        Log.i(PERF_TAG, "windows item=$item windowChars=$windowChars ends=$ends")
                    } else {
                        val place = saver.data.books[opened.identifier]?.place
                        (found?.takeIf { it.first == place }?.second ?: place?.let(opened::resolve))?.let { spinePoint.value = it }
                        // Opening counts as reading: the first Page starts at the Place, so a Book opened but never paged sorts as in progress.
                        if (place == null && opened.spineItems.isNotEmpty()) {
                            saver.change { it.withPlace(opened.identifier, opened.placeAt(spinePoint.value, openedAt)) }
                        }
                    }
                    book.value = opened
                    publishLines()
                }
                .onFailure {
                    // Leaving the Reader mid-open cancels it: that isn't a Book that couldn't be opened.
                    if (it is CancellationException) throw it
                    Log.w(TAG, "couldn't open ${file.name}", it)
                    status.value = READING_COULDNT_OPEN
                }
        }
    }

    /** Loads the typefaces and hyphenator off the main thread while the book is still opening (ADR 0007). */
    fun warmUp(measurer: TextMeasurer) {
        viewModelScope.launch(Dispatchers.Default) {
            val start = System.nanoTime()
            warmUpMeasurer(measurer)
            Log.i(PERF_TAG, "warmup ms=${ms(System.nanoTime() - start)}")
        }
    }

    /**
     * The view's column and measurer ([Typesetter]). A binding lays the book out afresh at the Place, unless
     * [measurer] lays out like the bound one, as when the Reader comes back from Contents: then the Pages
     * shown and cached stand, so turning back shows the Pages just read.
     */
    fun bind(measurer: WindowMeasurer) {
        val spineItems = book.value?.spineItems ?: return
        if (reading != null && this.measurer?.let(measurer::laysOutLike) == true) return
        this.measurer = measurer
        prefetching?.cancel()
        timer.discard()
        reading = Reading(
            spineItems, measure = { pass, window -> measure(measurer, pass, window, sync = true) }, linesOf = { it.lines },
            windowChars = windowChars, textEnd = book.value?.textEnd,
            chapterStarts = book.value?.chapters?.map { it.start }.orEmpty(),
        )
        open(if (frame.value == null) "open" else "relayout")
    }

    fun changeFont(delta: Int) {
        val step = (fontStep.value + delta).coerceIn(FONT_SIZES.indices)
        if (step == fontStep.value) return
        fontStep.value = step
        saver.change { it.copy(settings = it.settings.copy(fontSize = FONT_SIZES[step])) }
        timer.discard()
        open("font")
    }

    /**
     * A forward turn: the next Page, or from the last Page of the text ([reachesEnd]) the end page, which
     * sets Finished; nothing on the end page or on the Book's last Page, so Back matter never shows a
     * second end page. Leaving a Page for the next one gives a speed sample when
     * it was reached that way too ([PageTimer]); the end page is not a Page, so leaving for it gives none.
     * The next Page is timed from when it shows, so its layout doesn't count as reading. Any turn hides the controls.
     */
    fun nextPage() {
        hideControls()
        val shown = frame.value ?: return
        if (atEnd.value) return
        if (reachesEnd(shown)) {
            timer.discard()
            atEnd.value = true
            stamp(finished = true)
            publishLines()
            return
        }
        if (isLastPage(shown)) return
        timer.finish(now())
        val next = turn(back = false) { it.next() } ?: return
        val words = words ?: return
        timer.start(now(), words.between(SpinePoint(next.pass.item, next.page.start), SpinePoint(next.pass.item, next.page.end)))
    }

    /**
     * A back turn: from the end page to the last Page, else to the Page before. It clears Finished from the
     * end page, and when it lands on a Page of the text, even on the first Page, which has no Page before
     * it; a back turn inside Back matter keeps Finished. Any turn hides the controls.
     */
    fun previousPage() {
        hideControls()
        if (frame.value == null) return
        timer.discard()
        if (atEnd.value) {
            atEnd.value = false
            stamp(finished = false)
            publishLines()
            return
        }
        turn(back = true) { it.previous() }
    }

    /** Opening Contents: drops the Page's timing, even when back then returns without a jump, and gives what Contents lists. */
    fun openContents(): Contents? {
        timer.discard()
        hideControls()
        return book.value?.contentsAt(pagePoint, atEnd.value)
    }

    /**
     * A jump from Contents to [start], a Chapter's start or a Part's heading: leaves the end page and shows the
     * Page starting there ([Reading.jump]), even for the Chapter already current, recording it as the Place,
     * stamped. It clears Finished when [start] is in the text, keeps it in Back matter, and never sets it. The
     * running timing is dropped and the landed Page is untimed, so it gives no sample.
     */
    fun jumpTo(start: SpinePoint) {
        val opened = book.value ?: return
        val measurer = measurer ?: return
        timer.discard()
        atEnd.value = false
        val shown = show("jump") { it.jump(start.item, start.char, measurer.key(fontStep.value)) } ?: return
        spinePoint.value = SpinePoint(shown.pass.item, shown.page.start)
        val clears = start < opened.textEnd && saver.data.books[opened.identifier]?.finished == true
        stamp(finished = if (clears) false else null)
    }

    /** The page turn a key makes: volume down forward, volume up back; null for any other key. */
    private fun turnFor(keyCode: Int): (() -> Unit)? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { { nextPage() } }
        KeyEvent.KEYCODE_VOLUME_UP -> { { previousPage() } }
        else -> null
    }

    /**
     * A page key turns on key-down (a held key repeats there) and is consumed on key-up and key-multiple too,
     * or LightActivity forwards it to LightOS, as it does any Light Phone key a screen declines (the wheel's
     * click lights the flashlight that way). Any other key is declined everywhere and stays LightOS's.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = turnFor(keyCode)?.let { it(); true } ?: false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = turnFor(keyCode) != null

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        turnFor(keyCode)?.let { turn -> repeat(repeatCount) { turn() }; true } ?: false

    /** Activity.onPause: the last hook guaranteed to run before the process can be killed. */
    override fun onAppPause() {
        timer.discard()
        hideControls()
        saver.flush()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = saver.flush()

    override fun onCleared() {
        saver.flush()
        super.onCleared()
    }

    /**
     * A pass at the Place (a cached one when it holds the Place's Page) at the current font and column.
     * The end page stays only while the Page at the Place still reaches [OpenBook.textEnd]; otherwise
     * that Page shows and Finished stays, so forward turns reach the end page again.
     */
    private fun open(reason: String) {
        val measurer = measurer ?: return
        val (item, char) = spinePoint.value
        val shown = show(reason) { it.open(item, char, measurer.key(fontStep.value)) } ?: return
        if (atEnd.value && !reachesEnd(shown)) {
            atEnd.value = false
            publishLines()
        }
    }

    /** Whether [shown] is the last Page of the text: it starts before [OpenBook.textEnd] and ends at or after it. */
    private fun reachesEnd(shown: Shown<WindowLayout>): Boolean = book.value?.let {
        inText(shown) && SpinePoint(shown.pass.item, shown.page.end) >= it.textEnd
    } == true

    /** Whether [shown] is the Book's last Page, where a forward turn does nothing and its timer keeps running. */
    private fun isLastPage(shown: Shown<WindowLayout>): Boolean =
        book.value?.let { shown.pass.item == it.spineItems.lastIndex && shown.page.end >= shown.pass.length } == true

    /** Whether [shown] is a Page of the text, not of Back matter: it starts before [OpenBook.textEnd]. */
    private fun inText(shown: Shown<WindowLayout>): Boolean =
        book.value?.let { SpinePoint(shown.pass.item, shown.page.start) < it.textEnd } == true

    /**
     * Shows the Page [step] finds and records it as the Place. A [back] turn clears Finished too when it
     * lands on a Page of the text ([inText]), re-stamping the Place even when [step] finds no Page (the
     * first Page, which is text).
     */
    private fun turn(back: Boolean, step: (Reading<WindowLayout>) -> Shown<WindowLayout>?): Shown<WindowLayout>? {
        val shown = show("turn", step)
        if (shown != null) spinePoint.value = SpinePoint(shown.pass.item, shown.page.start)
        val clears = back && (shown == null || inText(shown)) && book.value?.let { saver.data.books[it.identifier]?.finished } == true
        if (shown != null || clears) stamp(finished = if (clears) false else null)
        return shown
    }

    /** Records the Place at [spinePoint], and [finished] when it isn't null ([withFinished]). */
    private fun stamp(finished: Boolean? = null) {
        val opened = book.value ?: return
        val place = opened.placeAt(spinePoint.value, System.currentTimeMillis())
        saver.change { data ->
            if (finished == null) data.withPlace(opened.identifier, place) else data.withFinished(opened.identifier, finished, place)
        }
    }

    /**
     * The point the Page on screen goes by for its Chapter and minutes: its start, or the start of a Chapter
     * starting later in its first line (a table of contents may point mid-line, or at a Part's heading
     * above its first Chapter's), so a jump there names the Chapter chosen. With no Chapter starting in
     * its first line, a Page opening on headings goes by the first Chapter starting in them (the later of
     * several starting at one point), else by the first line under them ([Pass.leadEnd]): a relayout may
     * start the Page on the heading of a Chapter anchored at the paragraph below it ([pageFloor]), or on an
     * unlisted heading above a Part's. A Page of only headings goes by the Chapter starting at its end, the
     * headings being that Chapter's, unless Back matter starts there; the end of a Spine item counts as the
     * next one's start, so a Part's title page of its own names the Part's first Chapter. Before a view
     * binds, the Place.
     */
    private val pagePoint: SpinePoint get() {
        val (pass, page) = frame.value ?: return spinePoint.value
        val start = SpinePoint(pass.item, page.start)
        val opened = book.value ?: return start
        val itemEnd = pass.item < opened.spineItems.lastIndex && page.end == opened.spineItems[pass.item].text.length
        val end = if (itemEnd) SpinePoint(pass.item + 1, 0) else SpinePoint(pass.item, page.end)
        val inFirst = opened.chapterAt(SpinePoint(pass.item, pass.firstLineEnd(page) - 1))?.takeIf { opened.chapters[it].start >= start }
        val lead = pass.leadEnd(page)
        val under = if (lead != null) {
            val first = opened.chapters.indexOfFirst { it.start >= start }
                .takeIf { it >= 0 && opened.chapters[it].start < SpinePoint(pass.item, lead) }
            first?.let { f -> opened.chapters.indexOfLast { it.start == opened.chapters[f].start } }
                ?: opened.chapterAt(SpinePoint(pass.item, lead - 1))
        } else {
            opened.chapterAt(end)?.takeIf { opened.chapters[it].start == end && end < opened.textEnd }
                ?: opened.chapterAt(SpinePoint(pass.item, page.end - 1))
        }
        return (inFirst ?: under)?.let { opened.chapters[it].start }?.takeIf { it > start } ?: start
    }

    private fun publishLines() {
        val opened = book.value ?: return
        val point = pagePoint
        val chapter = opened.chapterAt(point).takeUnless { atEnd.value }
        runningHead.value = chapter?.let { runningHeadOf(opened.chapters[it]) } ?: RunningHead(null, shelfTitle)
        progressLine.value = words?.takeUnless { atEnd.value }?.let { opened.minutesLine(it, point, owner.speed.wpm) }
    }

    /**
     * Runs one step of the session, publishes what it shows, logs the pass when the step started one
     * (re-entering a cached pass logs nothing), and keeps the neighbouring windows coming. firstPageMs
     * runs from the step's start to the Page being ready to draw: the windows' styled text, their
     * measures and the packing, on this thread.
     */
    private fun show(reason: String, step: (Reading<WindowLayout>) -> Shown<WindowLayout>?): Shown<WindowLayout>? {
        val reading = reading ?: return null
        val before = frame.value?.pass
        val started = reading.passesStarted
        syncWindows = 0
        val start = System.nanoTime()
        val shown = step(reading) ?: return null
        val elapsed = System.nanoTime() - start
        frame.value = shown
        publishLines()
        val pass = shown.pass
        if (pass !== before) prefetching?.cancel()
        if (reading.passesStarted != started) {
            Log.i(PERF_TAG, "pass reason=$reason item=${pass.item} chars=${pass.length} font=${FONT_SIZES[pass.key.fontStep]} " +
                "windows=${pass.windows.size} syncWindows=$syncWindows firstPageMs=${ms(elapsed)}")
        }
        prefetch()
        return shown
    }

    /**
     * Measures the next window the shown pass wants, off the main thread, one at a time, until it is
     * covered. A cancel can't interrupt a measure already running, so the next one starts only once it
     * returns: rapid font taps never stack measures. A turn that needs the window being measured here
     * measures it on the main thread, and [Pass.record] then drops this late copy.
     */
    private fun prefetch() {
        if (prefetching != null) return
        val measurer = measurer ?: return
        val (pass, window) = reading?.prefetchTarget() ?: return
        prefetching = viewModelScope.launch {
            try {
                val layout = withContext(Dispatchers.Default) { measure(measurer, pass, window, sync = false) }
                if (frame.value?.pass === pass) pass.record(window, layout)
            } finally {
                prefetching = null
                prefetch()
            }
        }
    }

    private fun measure(measurer: WindowMeasurer, pass: Pass<WindowLayout>, window: Int, sync: Boolean): WindowLayout {
        val start = System.nanoTime()
        val layout = measurer.measure(pass.spineItem, pass.windows[window], pass.key.fontStep)
        if (sync) syncWindows++
        Log.i(PERF_TAG, "window pass=${pass.id} item=${pass.item} index=$window " +
            "chars=${pass.windows[window].let { it.end - it.start }} measureMs=${ms(System.nanoTime() - start)} sync=$sync")
        return layout
    }

    private fun ms(ns: Long) = "%.1f".format(Locale.ROOT, ns / 1e6)
}

/** The controls' running head: [title], the Chapter's or the Book's, under [part], its Part's title, when it has one. */
data class RunningHead(val part: String?, val title: String)

/** A Chapter's running head: its title under its nearest Part's, the one its table of contents nests it in last. */
fun runningHeadOf(chapter: Chapter): RunningHead = RunningHead(chapter.parts.lastOrNull()?.title, chapter.title)
