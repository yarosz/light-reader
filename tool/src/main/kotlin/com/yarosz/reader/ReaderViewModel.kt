package com.yarosz.reader

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logcat tag for layout timings; `scripts/perf.sh` parses these lines, so the key `chapters=` stays
 * as it is (it counts Spine items).
 */
private const val PERF_TAG = "ReaderPerf"

/** How long page turns and font changes settle before the reading data is saved. */
const val SAVE_DEBOUNCE_MS = 1_000L

/**
 * Reads the Book in [file], a view onto [owner] like the Shelf, so the Reader's Places and font step
 * reach the reading data the Shelf shows. [start] is a dev-start session's Place (see
 * [DEV_BOOK_FILE]), opened at the default font. [io] is where the Book is opened; tests pass one
 * they control.
 */
class ReaderViewModel(
    private val file: File,
    private val owner: ShelfOwner,
    private val start: DevStart? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LightViewModel<Unit>() {
    /**
     * The owner's saver, or in a dev-start session one that starts empty and writes nothing, so perf
     * and CI runs leave the device's reading data as they found it.
     */
    internal val saver = if (start == null) owner.saver else ReadingSaver(viewModelScope, io, SAVE_DEBOUNCE_MS, { }) { }

    val book = MutableStateFlow<OpenBook?>(null)
    val status = MutableStateFlow("Opening…")

    /** The Place: the top of the page being read. Relayouts never rewrite it, so font changes can't drift. */
    val spinePoint = MutableStateFlow(SpinePoint(0, 0))
    val fontStep = MutableStateFlow(DEFAULT_FONT_STEP)

    /** The Page to draw and the pass whose layouts draw it; null until the view binds a [Typesetter]. */
    val frame = MutableStateFlow<Shown<WindowLayout>?>(null)

    private var typesetter: Typesetter? = null
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
            runCatching { withContext(io) { parseEpub(file, stored.storedTitle(file.name) ?: file.nameWithoutExtension) } }
                .onSuccess { opened ->
                    val parseMs = ms(System.nanoTime() - parseStart)
                    val largest = opened.spineItems.indices.maxByOrNull { opened.spineItems[it].text.length }
                    if (largest != null) {
                        Log.i(PERF_TAG, "book chapters=${opened.spineItems.size} largest=$largest " +
                            "largestChars=${opened.spineItems[largest].text.length} parseMs=$parseMs")
                    }
                    val now = System.currentTimeMillis()
                    val title = saver.data.books[opened.identifier]?.title?.takeIf { it.isNotBlank() } ?: opened.title
                    saver.change { it.shelve(opened.identifier, title, file.name, opened.author, now = now) }
                    if (start == null) fontStep.value = saver.data.settings.fontStep.coerceIn(FONT_SIZES.indices)
                    if (start != null && opened.spineItems.isNotEmpty()) {
                        val item = start.item.coerceIn(opened.spineItems.indices)
                        windowChars = start.windowChars ?: WINDOW_CHARS
                        spinePoint.value = SpinePoint(item, start.char.coerceIn(0, opened.spineItems[item].text.length))
                        val ends = windows(opened.spineItems[item], windowChars).joinToString(",") { it.end.toString() }
                        Log.i(PERF_TAG, "windows item=$item windowChars=$windowChars ends=$ends")
                    } else {
                        val place = saver.data.books[opened.identifier]?.place
                        place?.let(opened::resolve)?.let { spinePoint.value = it }
                        // Opening counts as reading: the first Page starts at the Place, so a Book opened but never paged sorts as in progress.
                        if (place == null && opened.spineItems.isNotEmpty()) {
                            val (item, char) = spinePoint.value
                            saver.change { it.withPlace(opened.identifier, opened.spineItems[item].placeOf(char, now)) }
                        }
                    }
                    book.value = opened
                }
                .onFailure { status.value = "Couldn't open the book: ${it.message}" }
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

    /** The view's column and measurer. Each binding lays the book out afresh at the Place. */
    fun bind(typesetter: Typesetter) {
        val spineItems = book.value?.spineItems ?: return
        this.typesetter = typesetter
        prefetching?.cancel()
        reading = Reading(spineItems, measure = { pass, window -> measure(typesetter, pass, window, sync = true) }, linesOf = { it.lines }, windowChars = windowChars)
        open(if (frame.value == null) "open" else "relayout")
    }

    fun changeFont(delta: Int) {
        val step = (fontStep.value + delta).coerceIn(FONT_SIZES.indices)
        if (step == fontStep.value) return
        fontStep.value = step
        saver.change { it.copy(settings = it.settings.copy(fontStep = step)) }
        open("font")
    }

    fun nextPage() = turn { it.next() }

    fun previousPage() = turn { it.previous() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> true.also { nextPage() }
        KeyEvent.KEYCODE_VOLUME_UP -> true.also { previousPage() }
        else -> false
    }

    /** Activity.onPause: the last hook guaranteed to run before the process can be killed. */
    override fun onAppPause() = saver.flush()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = saver.flush()

    override fun onCleared() {
        saver.flush()
        super.onCleared()
    }

    /** A pass at the Place (a cached one when it holds the Place's Page) at the current font and column. */
    private fun open(reason: String) {
        val typesetter = typesetter ?: return
        val (item, char) = spinePoint.value
        show(reason) { it.open(item, char, typesetter.key(fontStep.value)) }
    }

    private fun turn(step: (Reading<WindowLayout>) -> Shown<WindowLayout>?) {
        val shown = show("turn", step) ?: return
        spinePoint.value = SpinePoint(shown.pass.item, shown.page.start)
        val identifier = book.value?.identifier ?: return
        val place = shown.pass.spineItem.placeOf(shown.page.start, System.currentTimeMillis())
        saver.change { it.withPlace(identifier, place) }
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
        val typesetter = typesetter ?: return
        val (pass, window) = reading?.prefetchTarget() ?: return
        prefetching = viewModelScope.launch {
            try {
                val layout = withContext(Dispatchers.Default) { measure(typesetter, pass, window, sync = false) }
                if (frame.value?.pass === pass) pass.record(window, layout)
            } finally {
                prefetching = null
                prefetch()
            }
        }
    }

    private fun measure(typesetter: Typesetter, pass: Pass<WindowLayout>, window: Int, sync: Boolean): WindowLayout {
        val start = System.nanoTime()
        val layout = typesetter.measure(pass.spineItem, pass.windows[window], pass.key.fontStep)
        if (sync) syncWindows++
        Log.i(PERF_TAG, "window pass=${pass.id} item=${pass.item} index=$window " +
            "chars=${pass.windows[window].let { it.end - it.start }} measureMs=${ms(System.nanoTime() - start)} sync=$sync")
        return layout
    }

    private fun ms(ns: Long) = "%.1f".format(Locale.ROOT, ns / 1e6)
}
