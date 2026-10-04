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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logcat tag for layout timings; `scripts/perf.sh` parses these lines (book, windows, warmup, pass, window,
 * and in a dev-start session turn, shown, record), so their keys stay as they are: `chapters=` counts Spine
 * items, `tocChapters=` the Book's Chapters. The `book` line is logged once the whole Book is in; a lazy open's
 * adds placedMs, restMs, loadedMs and parsedChars ([swap]). A line naming a Spine item names it by its index in
 * the whole Book ([perfItem]).
 */
private const val PERF_TAG = "ReaderPerf"

private const val TAG = "Reader"

/**
 * An open's first step: the [opening] it read, the Book it shows first, [whole] when that is the whole Book and not a
 * lazy open's one Spine item (ADR 0009), its stored Place with where [resolve] found it, the characters parsed so far,
 * and for the whole Book its word index; [wordsNs] is the ns the index took, 0 without one.
 */
private class Opened(
    val opening: EpubOpening,
    val book: OpenBook,
    val whole: Boolean,
    val found: Pair<Place, SpinePoint?>?,
    val parsedChars: Long,
    val words: WordIndex?,
    val wordsNs: Long,
)

/** A lazy open's rest: the whole Book, its word index, and the ns the parse and the index took. */
private class Rest(val book: OpenBook, val words: WordIndex, val parseNs: Long, val wordsNs: Long)

/**
 * A Page put in the frame and not yet drawn, for the `shown` perf line: the [reason] it showed, when that
 * began ([System.nanoTime]), and [extra] fields for the line.
 */
private class Undrawn(val shown: Shown<WindowLayout>, val reason: String, val since: Long, val extra: String)

/** How long page turns and font changes settle before the reading data is saved. */
const val SAVE_DEBOUNCE_MS = 1_000L

/**
 * Reads the Book in [file], a view onto [owner] like the Shelf, so the Reader's Places and font size
 * reach the reading data the Shelf shows. [start] is a dev-start session's Place (see
 * [DEV_BOOK_FILE]), opened at the default font. [io] is where the Book is opened, [idle] where the keep-awake
 * times out ([keepAwake]), "Opening…" waits out its delay and least time ([showsOpening]) and the first-run hint its
 * delay ([readingHint]), and [now] is the monotonic millis that time Pages for the reading speed; tests pass ones they
 * control.
 */
class ReaderViewModel(
    private val file: File,
    private val owner: ShelfOwner,
    private val start: DevStart? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idle: CoroutineDispatcher = Dispatchers.Main,
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
     * Whether the reading view shows "Opening…" (DESIGN.md "Reading"): only once an open has gone [OPENING_DELAY_MS]
     * without its first Page's Book, blank until then, and from then at least [OPENING_MIN_SHOWN_MS], holding the Page
     * back even once the Book is in, so it never flashes. "Couldn't open this Book." and "This Book has no text."
     * replace it at once.
     */
    val showsOpening = MutableStateFlow(false)

    /** An open's wait before "Opening…" shows, then its least time on screen ([showsOpening]), on [idle]. */
    private var openingTimes: Job? = null

    /** Starts an open's [openingTimes]: once its least time is up, "Opening…" goes if the Book is in, else when it is ([openingDone]). */
    private fun timeOpening() {
        openingTimes?.cancel()
        showsOpening.value = false
        openingTimes = viewModelScope.launch(idle) {
            delay(OPENING_DELAY_MS)
            showsOpening.value = true
            delay(OPENING_MIN_SHOWN_MS)
            if (book.value != null) showsOpening.value = false
        }
    }

    /**
     * An open's first step is done: "Opening…" not yet shown never shows, and one shown goes now when its least time is
     * up or [atOnce] (a message replaces it), else once that time is ([timeOpening]).
     */
    private fun openingDone(atOnce: Boolean) {
        if (!atOnce && showsOpening.value && openingTimes?.isActive == true) return
        openingTimes?.cancel()
        showsOpening.value = false
    }

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

    /**
     * Whether the controls show over the Page. Every turn, a jump from Contents and the Tool pausing hide them;
     * opening Contents keeps them, so back from it returns to the Page as it was.
     */
    val controls = MutableStateFlow(false)

    /** Shows the controls. The reader's latest intent wins: turns still waiting for the whole Book are dropped ([queuedTurns]). */
    fun showControls() {
        stayAwake()
        queuedTurns.clear()
        controls.value = true
        publishHint()
    }

    /** Hides the controls, dropping a Contents still waiting for the whole Book ([contentsRequest]), as it was asked from them. */
    fun hideControls() {
        stayAwake()
        contentsRequest = null
        controls.value = false
        publishHint()
    }

    /** The controls' Progress line ([minutesLine]); null for none. */
    val progressLine = MutableStateFlow<ProgressLine?>(null)

    /**
     * Whether the reading view keeps the screen on while it shows a Page or the end page (DESIGN.md "Reading"):
     * from the reading view showing, until [KEEP_AWAKE_MS] pass with no tap or volume key press there
     * ([stayAwake]). Never while the reading view is hidden or the Tool is paused.
     */
    val keepAwake = MutableStateFlow(false)

    private var showing = false
    private var awake: Job? = null

    /**
     * A press on the reading view, a tap, a screen reader's click or a volume key: the screen stays on for
     * [KEEP_AWAKE_MS] from now, while it shows. A screen reader's click reaches no pointer handler, so every
     * action the reading view offers calls this itself.
     */
    fun stayAwake() {
        if (!showing) return
        keepAwake.value = true
        awake?.cancel()
        awake = viewModelScope.launch(idle) {
            delay(KEEP_AWAKE_MS)
            keepAwake.value = false
        }
    }

    private fun sleep() {
        showing = false
        awake?.cancel()
        keepAwake.value = false
        publishHint()
    }

    /**
     * The first-run hint's step on screen (DESIGN.md "Reading"), [HINT_DELAY_MS] after it can show: the reading
     * view showing a Page (not "Opening…", not the end page) with the controls hidden. Null for none, at once
     * when it can't show, and once the hint is dismissed or in a dev-start session. While a step is on screen only
     * a tap in its zone acts ([tapped]) and the volume keys don't turn ([turnFor]); a step waiting out its delay
     * isn't on screen, so every tap and key then acts as usual, and a tap in its zone doesn't move it on.
     */
    val readingHint = MutableStateFlow<HintStep?>(null)

    /** The walkthrough's step, on screen or not; null once it is over, or when it never runs. */
    private var hintStep: HintStep? = null

    /** The wait before [pending] shows ([HINT_DELAY_MS]), on [idle]. */
    private var hintDelay: Job? = null
    private var pending: HintStep? = null

    /**
     * Puts [hintStep] on screen [HINT_DELAY_MS] after it can show, and takes it off at once when it can't. A step
     * already on screen or already waiting keeps its time, so a turn doesn't restart the wait.
     */
    private fun publishHint() {
        val step = hintStep.takeIf { showing && frame.value != null && !atEnd.value && !controls.value }
        if (step != null && (readingHint.value == step || (hintDelay?.isActive == true && pending == step))) return
        hintDelay?.cancel()
        readingHint.value = null
        pending = step
        if (step == null) return
        hintDelay = viewModelScope.launch(idle) {
            delay(HINT_DELAY_MS)
            readingHint.value = step
        }
    }

    /** A tap on the next-page zone: a forward turn ([nextPage]), moving the hint on from [HintStep.Next]. */
    fun tapNext() = tapped(HintStep.Next) { nextPage() }

    /** A tap on the previous-page zone: a back turn ([previousPage]), moving the hint on from [HintStep.Back], turned or not. */
    fun tapBack() = tapped(HintStep.Back) { previousPage() }

    /** A tap on the middle zone: shows the controls, and from [HintStep.Controls] ends the hint for good. */
    fun tapMiddle() = tapped(HintStep.Controls) { showControls() }

    /**
     * A tap on [zone]'s tap zone, doing [action] while no guide is on screen. While the guide shows, the walkthrough
     * is followed: a tap on another zone does nothing, and a tap on [zone] acts and moves it on, to the next step,
     * or from the last one to none, dismissing it ([dismissReadingHint]). A volume key never moves it.
     */
    private fun tapped(zone: HintStep, action: () -> Unit) {
        stayAwake()
        val guide = readingHint.value
        if (guide != null && guide != zone) return
        action()
        if (guide == null) return
        hintStep = HintStep.entries.getOrNull(zone.ordinal + 1)
        if (hintStep == null) dismissReadingHint() else publishHint()
    }

    /** Ends the hint for good, saving that it was dismissed; no write when it already was. */
    private fun dismissReadingHint() {
        hintStep = null
        publishHint()
        if (saver.data.settings.readingHintDismissed) return
        saver.change { it.copy(settings = it.settings.copy(readingHintDismissed = true)) }
    }

    /** The title the Shelf shows for the Book. */
    private var shelfTitle = ""
    private var words: WordIndex? = null
    private val timer = PageTimer(owner.speed)

    private var measurer: WindowMeasurer? = null
    private var reading: Reading<WindowLayout>? = null
    private var prefetching: Job? = null
    private var syncWindows = 0
    private var windowChars = WINDOW_CHARS

    /**
     * Whether this is a dev-start session, the only kind `scripts/perf.sh` reads: only then are the `turn`, `shown`
     * and `record` lines logged and their state kept, so normal reading pays a check per step.
     */
    private val perf = start != null

    /**
     * Of [syncWindows], those a background measure ([prefetch]) was measuring at the time, for the `turn` line:
     * the very window of the very pass, which a step measures again on this thread rather than wait for, and whose
     * late copy [Pass.record] drops. Background measures of other windows, competing for the CPU, aren't counted.
     */
    private var raced = 0

    /** The pass and window [prefetching] is measuring, in a dev-start session; null while it measures none. */
    private var prefetchingWindow: Pair<Pass<WindowLayout>, Int>? = null

    /**
     * When [openBook] began ([System.nanoTime]), before the parse: the `shown` line's origin for an open, which
     * also carries the parse's and the word index's ms as the `book` line logs them.
     */
    private var openBegan = 0L
    private var openParseMs = ""
    private var openWordsMs = ""

    /** When the open put the Book in [book] ([System.nanoTime]), for the `shown` line. */
    private var bookAt = 0L

    /**
     * Whether [book] is the whole Book. A lazy open (ADR 0009) first shows the Place's Spine item alone and parses the
     * rest behind it; until the whole Book is in ([swap]), what needs it waits: [queuedTurns], [contentsRequest], and
     * the writes ([shelvedAt], [placeDirty]).
     */
    private var loaded = false

    /**
     * Turns waiting for the whole Book, in order: one off the Spine item or onto the end page, and every turn after it,
     * so taps keep their order. Showing the controls drops them, so a font change or Contents never overtakes them.
     */
    private val queuedTurns = mutableListOf<() -> Unit>()

    /** Where Contents goes once the whole Book is in, if the controls still show; one tap's, so a second adds nothing. */
    private var contentsRequest: ((Contents) -> Unit)? = null

    /** When a lazy open opened the Book: its Shelf write (title, author, opened time) waits for the whole Book. */
    private var shelvedAt: Long? = null

    /**
     * Whether a Place write waits for the whole Book, which its progress needs ([stamp]): one write, at [dirtyAt]. It
     * never sets Finished: the end page can't show before the swap, so no Finished change can wait. [backTurned]: a back
     * turn moved the Place meanwhile, so the write clears Finished if the Place is in the text, which only the whole
     * Book's text end tells.
     */
    private var placeDirty = false
    private var dirtyAt = 0L
    private var backTurned = false

    /**
     * Whether a lazy open's second step, the rest of the Book, failed. Showing again then doesn't retry, so a resume
     * doesn't show the Page only to take it away again; reopening from the Shelf does. A first step that failed is
     * retried.
     */
    private var failedLate = false

    /**
     * In a lazy dev-start session, the whole Book's index of the Spine item shown first: [start]'s, or null when its
     * id named none with text and another was placed, whose index only the whole Book knows.
     */
    private var placedItem: Int? = null

    /**
     * Whether a lazy open showed the Book's start for want of a stored Place found in its Spine item, and no turn,
     * not even one still waiting ([waitForBook]), has moved off it. The whole Book may start on another Spine item
     * (a title page is dropped only from a Book that marks its body matter), so then the swap opens there, as a whole
     * parse would have.
     */
    private var atOpeningStart = false

    /** When the last step put its Page in [frame] ([System.nanoTime]). */
    private var framedAt = 0L

    /** The Page [drawn] waits for; null once logged, and outside a dev-start session. */
    private var undrawn: Undrawn? = null

    private var loading: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = shown()

    /** The reading view showing, on opening, back from Contents, or the Tool resuming: opens the Book, and keeps the screen on afresh. */
    internal fun shown() {
        showing = true
        openBook()
        stayAwake()
        publishHint()
    }

    /**
     * Opens the Book once, lazily (ADR 0009): a show while an open runs starts no second one, nor one after a late
     * failure ([failedLate]). A dev-start session naming no Spine item id parses the whole Book first.
     */
    internal fun openBook() {
        if (book.value != null || loading?.isActive == true || failedLate) return
        openBegan = System.nanoTime()
        status.value = READING_OPENING
        timeOpening()
        loading = viewModelScope.launch {
            owner.awaitLoaded()
            val stored = saver.data
            val parseStart = System.nanoTime()
            var epub: EpubOpening? = null
            try {
                val first = try {
                    withContext(io) {
                        val opening = EpubOpening(file, stored.storedTitle(file.name) ?: file.nameWithoutExtension).also { epub = it }
                        val place = stored.books[opening.pkg.identifier]?.place
                        val placed = if (start != null && start.spineId == null) null else opening.placed(start?.spineId ?: place?.spineId)
                        val opened = placed ?: opening.whole()
                        val wordsStart = System.nanoTime()
                        val index = if (placed == null) WordIndex(opened.spineItems) else null
                        val wordsNs = System.nanoTime() - wordsStart
                        // Re-finding a Place by its text scans its Spine item, so it runs here, off the main thread.
                        val found = place?.let { it to opened.resolve(it) }
                        Opened(opening, opened, whole = placed == null, found, opening.parsedChars, index, wordsNs)
                    }
                } catch (e: Throwable) {
                    return@launch couldntOpen(e, late = false)
                }
                val placedNs = System.nanoTime() - parseStart - first.wordsNs
                showFirst(first, placedNs)
                if (first.whole) return@launch
                val rest = try {
                    withContext(io) {
                        val restStart = System.nanoTime()
                        val whole = first.opening.whole()
                        val wordsStart = System.nanoTime()
                        Rest(whole, WordIndex(whole.spineItems), wordsStart - restStart, System.nanoTime() - wordsStart)
                    }
                } catch (e: Throwable) {
                    return@launch couldntOpen(e, late = true)
                }
                swap(rest.book, rest.words)
                logBook(rest.book, ms(placedNs + rest.parseNs), ms(rest.wordsNs),
                    " placedMs=${ms(placedNs)} restMs=${ms(rest.parseNs)} loadedMs=${ms(System.nanoTime() - openBegan)} parsedChars=${first.parsedChars}")
                runWaiting()
            } finally {
                epub?.close()
            }
        }
    }

    /** Shows an open's first step, the whole Book or a lazy open's one Spine item (ADR 0009); [parseNs] is its parse. */
    private fun showFirst(first: Opened, parseNs: Long) {
        val opened = first.book
        openParseMs = ms(parseNs)
        openWordsMs = ms(first.wordsNs)
        loaded = first.whole
        if (loaded) logBook(opened, openParseMs, openWordsMs)
        val openedAt = System.currentTimeMillis()
        shelfTitle = saver.data.books[opened.identifier]?.title?.takeIf { it.isNotBlank() } ?: opened.title
        words = first.words
        if (loaded) shelve(opened, openedAt) else shelvedAt = openedAt
        if (start == null) fontStep.value = nearestFontStep(saver.data.settings.fontSize)
        if (start == null && !saver.data.settings.readingHintDismissed) hintStep = HintStep.Next
        var unstarted = false
        if (start != null && opened.spineItems.isNotEmpty()) {
            val item = if (loaded) start.item.coerceIn(opened.spineItems.indices) else 0
            if (!loaded) placedItem = start.item.takeIf { opened.spineItems[0].spineId == start.spineId }
            windowChars = start.windowChars ?: WINDOW_CHARS
            spinePoint.value = SpinePoint(item, start.char.coerceIn(0, opened.spineItems[item].text.length))
            val ends = windows(opened.spineItems[item], windowChars, opened.textEnd.takeIf { it.item == item }?.char).joinToString(",") { it.end.toString() }
            Log.i(PERF_TAG, "windows item=${perfItem(item)} spineId=${opened.spineItems[item].spineId} windowChars=$windowChars ends=$ends")
        } else {
            val place = saver.data.books[opened.identifier]?.place
            val found = first.found?.takeIf { it.first == place }?.second ?: place?.let(opened::resolve)
            found?.let { spinePoint.value = it }
            atOpeningStart = !loaded && found == null
            // Opening counts as reading: the first Page starts at the Place, so a Book opened but never paged sorts as in progress.
            unstarted = place == null && opened.spineItems.isNotEmpty()
        }
        bookAt = System.nanoTime()
        openingDone(atOnce = opened.spineItems.isEmpty())
        book.value = opened
        if (unstarted) stamp(now = openedAt)
        publishLines()
    }

    /**
     * Puts the whole Book in for a lazy open's one Spine item, the Place and the Page on screen moving to its index
     * ([Reading.rebase]), then makes the writes that waited (ADR 0009 "Rules that keep it exact").
     */
    private fun swap(whole: OpenBook, index: WordIndex) {
        val first = book.value?.spineItems?.singleOrNull()?.spineId
        val at = whole.spineItems.indexOfFirst { it.spineId == first }.takeIf { it >= 0 }
        // The whole Book drops the Spine item, or, no turn having moved, starts on another ([atOpeningStart]).
        val item = at.takeUnless { atOpeningStart && at != 0 }
        words = index
        loaded = true
        book.value = whole
        if (whole.spineItems.isEmpty()) {
            reading = null
            frame.value = null
        } else {
            spinePoint.value = item?.let { spinePoint.value.copy(item = it) } ?: SpinePoint(0, 0)
            val kept = reading?.rebase(whole.spineItems, whole.textEnd, whole.chapters.map { it.start }, item) ?: true
            if (!kept) open("relayout")
        }
        publishLines()
        publishHint()
        shelvedAt?.let { shelve(whole, it) }
        shelvedAt = null
        if (placeDirty) {
            // A back turn clears Finished when it lands in the text, which the Spine item alone couldn't tell.
            val clears = backTurned && item != null && spinePoint.value < whole.textEnd && saver.data.books[whole.identifier]?.finished == true
            placeDirty = false
            stamp(finished = if (clears) false else null, now = dirtyAt)
        }
    }

    /**
     * Once [swap] has put the whole Book in: opens a waiting Contents if the reading view still shows, then turns the
     * waiting turns in order. The two never wait together: showing the controls drops the turns, a turn the Contents.
     */
    private fun runWaiting() {
        contentsRequest?.let { show ->
            contentsRequest = null
            if (showing) openContents()?.let(show)
        }
        val turns = queuedTurns.toList()
        queuedTurns.clear()
        turns.forEach { it() }
    }

    /**
     * The Book couldn't be opened: before its first Page ([late] false, retried when the Reader shows again), or for
     * a lazy open when a Spine item after it won't parse, when the Page goes. What waited for the whole Book is
     * dropped, its writes too. A cancel, as leaving the Reader mid-open is, isn't a Book that couldn't be opened, and
     * passes on.
     */
    private fun couldntOpen(e: Throwable, late: Boolean) {
        if (e is CancellationException) throw e
        Log.w(TAG, "couldn't open ${file.name}", e)
        failedLate = late
        queuedTurns.clear()
        contentsRequest = null
        shelvedAt = null
        placeDirty = false
        prefetching?.cancel()
        reading = null
        frame.value = null
        book.value = null
        status.value = READING_COULDNT_OPEN
        openingDone(atOnce = true)
        publishHint()
    }

    /** Puts [opened] on the Shelf, opened [at], with the title the Shelf shows and its author. */
    private fun shelve(opened: OpenBook, at: Long) {
        saver.change { it.shelve(opened.identifier, shelfTitle, file.name, opened.author, now = at) }
    }

    /** Logs the `book` line for [opened], the whole Book: its Spine items, Chapters and largest Spine item, the parse's and word index's ms, then [lazy]'s fields. */
    private fun logBook(opened: OpenBook, parseMs: String, wordsMs: String, lazy: String = "") {
        val largest = opened.spineItems.indices.maxByOrNull { opened.spineItems[it].text.length } ?: return
        Log.i(PERF_TAG, "book chapters=${opened.spineItems.size} tocChapters=${opened.chapters.size} largest=$largest " +
            "largestChars=${opened.spineItems[largest].text.length} parseMs=$parseMs wordsMs=$wordsMs$lazy")
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
     * shown and cached stand, so turning back shows the Pages just read. A fresh layout drops turns waiting for the
     * whole Book ([queuedTurns]).
     */
    fun bind(measurer: WindowMeasurer) {
        val spineItems = book.value?.spineItems ?: return
        if (reading != null && this.measurer?.let(measurer::laysOutLike) == true) return
        this.measurer = measurer
        prefetching?.cancel()
        timer.discard()
        // The Page lays out afresh at the Place, so turns waiting for the whole Book from the Page that was go.
        queuedTurns.clear()
        reading = Reading(
            spineItems, measure = { pass, window -> measure(measurer, pass, window, sync = true, perfItem(pass.item)) }, linesOf = { it.lines },
            windowChars = windowChars, textEnd = book.value?.textEnd,
            chapterStarts = book.value?.chapters?.map { it.start }.orEmpty(),
        )
        open(if (frame.value == null) "open" else "relayout")
    }

    fun changeFont(delta: Int) {
        val begun = System.nanoTime()
        stayAwake()
        val step = (fontStep.value + delta).coerceIn(FONT_SIZES.indices)
        if (step == fontStep.value) return
        fontStep.value = step
        saver.change { it.copy(settings = it.settings.copy(fontSize = FONT_SIZES[step])) }
        timer.discard()
        open("font", begun)
    }

    /**
     * A forward turn: the next Page, or from the last Page of the text ([reachesEnd]) the end page, which
     * sets Finished; nothing on the end page or on the Book's last Page, so Back matter never shows a
     * second end page. Leaving a Page for the next one gives a speed sample when
     * it was reached that way too ([PageTimer]); the end page is not a Page, so leaving for it gives none.
     * The next Page is timed from when it shows, so its layout doesn't count as reading. Any turn hides the controls.
     * Before a lazy open has the whole Book, a turn off its Spine item's last Page, or onto the end page, waits for it.
     */
    fun nextPage() {
        val begun = System.nanoTime()
        hideControls()
        val shown = frame.value ?: return
        if (atEnd.value) return
        if (!loaded && (queuedTurns.isNotEmpty() || shown.page.end >= shown.pass.length || reachesEnd(shown))) return waitForBook(::nextPage)
        if (reachesEnd(shown)) {
            timer.discard()
            atEnd.value = true
            stamp(finished = true)
            publishLines()
            publishHint()
            return
        }
        if (isLastPage(shown)) return
        timer.finish(now())
        val next = turn(back = false, begun) { it.next() } ?: return
        val words = words ?: return
        timer.start(now(), words.between(SpinePoint(next.pass.item, next.page.start), SpinePoint(next.pass.item, next.page.end)))
    }

    /**
     * A back turn: from the end page to the last Page, else to the Page before. It clears Finished from the
     * end page, and when it lands on a Page of the text, even on the first Page, which has no Page before
     * it; a back turn inside Back matter keeps Finished. Any turn hides the controls. Before a lazy open has the
     * whole Book, a turn off its Spine item's first Page waits for it.
     */
    fun previousPage() {
        val begun = System.nanoTime()
        hideControls()
        val shown = frame.value ?: return
        if (!loaded && (queuedTurns.isNotEmpty() || shown.page.start == 0)) return waitForBook(::previousPage)
        timer.discard()
        if (atEnd.value) {
            atEnd.value = false
            stamp(finished = false)
            publishLines()
            publishHint()
            return
        }
        turn(back = true, begun) { it.previous() }
    }

    /** Queues [turn] until the whole Book is in ([runWaiting]), with every turn after it; a queued turn moves the reader. */
    private fun waitForBook(turn: () -> Unit) {
        atOpeningStart = false
        queuedTurns += turn
    }

    /**
     * The list icon: [show]s Contents ([openContents]) now, or once a lazy open has the whole Book if the controls still
     * show then ([contentsRequest]). A second tap while it waits adds nothing.
     */
    fun requestContents(show: (Contents) -> Unit) {
        if (!loaded) {
            if (contentsRequest == null) contentsRequest = show
        } else if (showing) {
            openContents()?.let(show)
        }
    }

    /** Opening Contents: drops the Page's timing, even when back then returns without a jump, and gives what Contents lists. */
    fun openContents(): Contents? {
        stayAwake()
        timer.discard()
        return book.value?.contentsAt(pagePoint, atEnd.value)
    }

    /**
     * A jump from Contents to [start], a Chapter's start or a Part's heading: leaves the end page and shows the
     * Page starting there ([Reading.jump]), even for the Chapter already current, recording it as the Place,
     * stamped. It clears Finished when [start] is in the text, keeps it in Back matter, and never sets it. The
     * running timing is dropped and the landed Page is untimed, so it gives no sample.
     */
    fun jumpTo(start: SpinePoint) {
        val begun = System.nanoTime()
        stayAwake()
        val opened = book.value ?: return
        val measurer = measurer ?: return
        timer.discard()
        hideControls()
        atEnd.value = false
        publishHint()
        val shown = show("jump", begun) { it.jump(start.item, start.char, measurer.key(fontStep.value)) } ?: return
        spinePoint.value = SpinePoint(shown.pass.item, shown.page.start)
        val clears = start < opened.textEnd && saver.data.books[opened.identifier]?.finished == true
        stamp(finished = if (clears) false else null)
    }

    /**
     * What a page key's press does, keeping the screen on ([stayAwake]): volume down turns forward, volume up back,
     * but neither turns while the first-run hint's guide shows ([readingHint]), as it teaches the taps. Null for any
     * other key.
     */
    private fun turnFor(keyCode: Int): (() -> Unit)? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> keyTurn(::nextPage)
        KeyEvent.KEYCODE_VOLUME_UP -> keyTurn(::previousPage)
        else -> null
    }

    private fun keyTurn(turn: () -> Unit): () -> Unit = {
        stayAwake()
        if (readingHint.value == null) turn()
    }

    /**
     * A page key turns on key-down (a held key repeats there) and is consumed on key-up and key-multiple too, even
     * while the guide keeps it from turning, or LightActivity forwards it to LightOS, as it does any Light Phone
     * key a screen declines (the wheel's click lights the flashlight that way). Any other key is declined
     * everywhere and stays LightOS's.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = turnFor(keyCode)?.let { it(); true } ?: false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = turnFor(keyCode) != null

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        turnFor(keyCode)?.let { turn -> repeat(repeatCount) { turn() }; true } ?: false

    /**
     * Activity.onPause: the last hook guaranteed to run before the process can be killed, and only the current
     * screen's, so a pause in Contents leaves the controls and the hint as they were. A hint mid-walkthrough
     * starts again from its first step, having saved nothing.
     */
    override fun onAppPause() {
        timer.discard()
        hintStep = hintStep?.let { HintStep.Next }
        hideControls()
        sleep()
        saver.flush()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = hidden()

    /** The reading view hiding, for Contents or on leaving: lets the screen go, takes the hint off, and saves. */
    internal fun hidden() {
        contentsRequest = null
        sleep()
        saver.flush()
    }

    override fun onCleared() {
        saver.flush()
        super.onCleared()
    }

    /**
     * A pass at the Place (a cached one when it holds the Place's Page) at the current font and column.
     * The end page stays only while the Page at the Place still reaches [OpenBook.textEnd]; otherwise
     * that Page shows and Finished stays, so forward turns reach the end page again. [begun] is when the
     * action asking for it began, for the `shown` line; an open goes from [openBook]'s start.
     */
    private fun open(reason: String, begun: Long = if (reason == "open") openBegan else System.nanoTime()) {
        val measurer = measurer ?: return
        val (item, char) = spinePoint.value
        val shown = show(reason, begun) { it.open(item, char, measurer.key(fontStep.value)) } ?: return
        if (atEnd.value && !reachesEnd(shown)) {
            atEnd.value = false
            publishLines()
            publishHint()
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
     * first Page, which is text). In a dev-start session, a turn that shows a Page logs a `turn` line: the window
     * its first band is drawn from, stateMs from [begun] (the turn reaching the view model) to the Page being in
     * [frame], ms to the turn's main-thread work being done (the running head, Progress and hint published, the
     * Place stamped; the background measures it starts run later, off it), and the windows it measured on this
     * thread (ADR 0007: none), [raced] among them.
     */
    private fun turn(back: Boolean, begun: Long, step: (Reading<WindowLayout>) -> Shown<WindowLayout>?): Shown<WindowLayout>? {
        val shown = show("turn", begun, step)
        if (shown != null) {
            spinePoint.value = SpinePoint(shown.pass.item, shown.page.start)
            atOpeningStart = false
        }
        if (!loaded) {
            // Whether the Page is text is known once the whole Book is: a lazy open's Spine item may be Back matter ([swap]).
            if (shown != null) stamp()
            backTurned = backTurned || back
        } else {
            val clears = back && (shown == null || inText(shown)) && book.value?.let { saver.data.books[it.identifier]?.finished } == true
            if (shown != null || clears) stamp(finished = if (clears) false else null)
        }
        if (perf && shown != null) {
            val done = System.nanoTime()
            Log.i(PERF_TAG, "turn dir=${if (back) "back" else "next"} item=${perfItem(shown.pass.item)} window=${shown.page.bands.first().window} " +
                "stateMs=${ms(framedAt - begun)} ms=${ms(done - begun)} syncWindows=$syncWindows raced=$raced")
        }
        return shown
    }

    /**
     * Records the Place at [spinePoint], stamped [now], and [finished] when it isn't null ([withFinished]). Before a
     * lazy open has the whole Book, which its progress needs, it marks one write to make then ([placeDirty]); only a
     * Place waits, as the callers that set [finished] (the end page, a jump from Contents) can't run before then.
     */
    private fun stamp(finished: Boolean? = null, now: Long = System.currentTimeMillis()) {
        if (!loaded) {
            placeDirty = true
            dirtyAt = now
            return
        }
        val opened = book.value?.takeIf { it.spineItems.isNotEmpty() } ?: return
        val place = opened.placeAt(spinePoint.value, now)
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
     * measures and the packing, on this thread. [begun] is when the action behind the step began: in a
     * dev-start session the `shown` line runs from it to the Page's first draw ([drawn]). A step finding the
     * Page already in [frame] waits for no draw: an equal [Shown] doesn't emit, so nothing redraws for it.
     */
    private fun show(reason: String, begun: Long, step: (Reading<WindowLayout>) -> Shown<WindowLayout>?): Shown<WindowLayout>? {
        val reading = reading ?: return null
        val before = frame.value?.pass
        val started = reading.passesStarted
        syncWindows = 0
        raced = 0
        val start = System.nanoTime()
        val shown = step(reading) ?: return null
        val elapsed = System.nanoTime() - start
        val framed = frame.value != shown
        frame.value = shown
        framedAt = System.nanoTime()
        publishLines()
        publishHint()
        val pass = shown.pass
        if (pass !== before) prefetching?.cancel()
        val passStarted = reading.passesStarted != started
        if (passStarted) {
            Log.i(PERF_TAG, "pass reason=$reason item=${perfItem(pass.item)} chars=${pass.length} font=${FONT_SIZES[pass.key.fontStep]} " +
                "windows=${pass.windows.size} syncWindows=$syncWindows firstPageMs=${ms(elapsed)}")
        }
        if (perf) {
            val opening = if (reason != "open") "" else
                " parseMs=$openParseMs wordsMs=$openWordsMs bookMs=${ms(bookAt - begun)} passStartMs=${ms(start - begun)}"
            val extra = opening + if (passStarted) " firstPageMs=${ms(elapsed)}" else ""
            undrawn = if (framed) Undrawn(shown, reason, begun, extra) else null
        }
        prefetch()
        return shown
    }

    /**
     * Measures the next window the shown pass wants, off the main thread, one at a time, until it is
     * covered. A cancel can't interrupt a measure already running, so the next one starts only once it
     * returns: rapid font taps never stack measures. A turn that needs the window being measured here
     * measures it on the main thread, and [Pass.record] then drops this late copy. A window that lands is
     * recorded on the main thread ([record]).
     */
    private fun prefetch() {
        if (prefetching != null) return
        val measurer = measurer ?: return
        val (pass, window) = reading?.prefetchTarget() ?: return
        if (perf) prefetchingWindow = pass to window
        val item = perfItem(pass.item)
        prefetching = viewModelScope.launch {
            try {
                val layout = withContext(Dispatchers.Default) { measure(measurer, pass, window, sync = false, item) }
                if (frame.value?.pass === pass) record(pass, window, layout)
            } finally {
                prefetchingWindow = null
                prefetching = null
                prefetch()
            }
        }
    }

    /**
     * Records a background measure of [window] in [pass], which re-packs the pass on this thread. In a dev-start
     * session it logs a `record` line with the re-pack's ms: a tap arriving meanwhile waits in the input queue, which
     * no `turn` line shows. A window a step already measured again ([raced]) is dropped, unlogged.
     */
    private fun record(pass: Pass<WindowLayout>, window: Int, layout: WindowLayout) {
        if (!perf || pass.measured(window) != null) return pass.record(window, layout)
        val start = System.nanoTime()
        pass.record(window, layout)
        Log.i(PERF_TAG, "record pass=${pass.id} item=${perfItem(pass.item)} window=$window repackMs=${ms(System.nanoTime() - start)}")
    }

    /** Measures [window] of [pass]; [item] names its Spine item for the `window` line, read on the main thread ([perfItem]). */
    private fun measure(measurer: WindowMeasurer, pass: Pass<WindowLayout>, window: Int, sync: Boolean, item: String): WindowLayout {
        val start = System.nanoTime()
        val layout = measurer.measure(pass.spineItem, pass.windows[window], pass.key.fontStep)
        if (sync) {
            syncWindows++
            if (prefetchingWindow?.let { (p, w) -> p === pass && w == window } == true) raced++
        }
        Log.i(PERF_TAG, "window pass=${pass.id} item=$item index=$window " +
            "chars=${pass.windows[window].let { it.end - it.start }} measureMs=${ms(System.nanoTime() - start)} sync=$sync")
        return layout
    }

    /**
     * The view drew [shown]: in a dev-start session, logs the `shown` line once for the step that put it in
     * [frame], with the ms from that step's start (for an open, [openBook]'s, before the parse) to this draw.
     * Called from the draw phase, so it leaves out the render thread and the display. An open's line adds where
     * the time went: parseMs and wordsMs; from the open's start, bookMs to the Book being in [book] and
     * passStartMs to its layout pass's start (the view composing and binding its measurer, once "Opening…" has gone
     * if it showed: [showsOpening]); and the pass's firstPageMs.
     */
    fun drawn(shown: Shown<WindowLayout>) {
        val step = undrawn?.takeIf { it.shown === shown } ?: return
        undrawn = null
        Log.i(PERF_TAG, "shown reason=${step.reason} ms=${ms(System.nanoTime() - step.since)}${step.extra}")
    }

    private fun ms(ns: Long) = "%.1f".format(Locale.ROOT, ns / 1e6)

    /**
     * Spine item [item] as the perf lines name it, by its index in the whole Book: before a lazy dev-start session has
     * the whole Book, the one it placed ([placedItem]), or "?" when that index isn't known yet. Main thread only.
     */
    private fun perfItem(item: Int): String = if (loaded || start == null) "$item" else placedItem?.toString() ?: "?"
}

/**
 * The first-run hint's steps, in order (DESIGN.md "Reading"): each points at a tap zone, and a tap there moves it
 * on. Forward first, the first need and the largest zone; back second, to the starting Page; the middle last,
 * opening the controls and ending it.
 */
enum class HintStep { Next, Back, Controls }

/** The controls' running head: [title], the Chapter's or the Book's, under [part], its Part's title, when it has one. */
data class RunningHead(val part: String?, val title: String)

/** A Chapter's running head: its title under its nearest Part's, the one its table of contents nests it in last. */
fun runningHeadOf(chapter: Chapter): RunningHead = RunningHead(chapter.parts.lastOrNull()?.title, chapter.title)
