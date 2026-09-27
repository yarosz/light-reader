package com.yarosz.reader

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.io.File
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BOOK_URL = "https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/" +
    "john-tenniel/downloads/lewis-carroll_alices-adventures-in-wonderland_john-tenniel.epub?source=download"

/** Logcat tag for layout timings; `scripts/perf.sh` parses these lines. */
private const val PERF_TAG = "ReaderPerf"

private const val TAG = "Reader"

/** How long page turns and font changes settle before the reading data is saved. */
const val SAVE_DEBOUNCE_MS = 1_000L

/** What opening the Book off the main thread produced. */
private data class Opened(val fileName: String, val book: Book, val start: DevStart?, val fromDisk: ReadingData)

/** [io] is where the Book is opened and the reading data saved; tests pass one they control. */
class ReaderViewModel(private val filesDir: File, private val io: CoroutineDispatcher = Dispatchers.IO) : LightViewModel<Unit>() {
    val book = MutableStateFlow<Book?>(null)
    val status = MutableStateFlow("Opening…")

    /** The Place: the top of the page being read. Relayouts never rewrite it, so font changes can't drift. */
    val position = MutableStateFlow(Position(0, 0))
    val fontStep = MutableStateFlow(DEFAULT_FONT_STEP)

    /** The Page to draw and the pass whose layouts draw it; null until the view binds a [Typesetter]. */
    val frame = MutableStateFlow<Shown<WindowLayout>?>(null)

    private var typesetter: Typesetter? = null
    private var reading: Reading<WindowLayout>? = null
    private var prefetching: Job? = null
    private var syncWindows = 0
    private var windowChars = WINDOW_CHARS

    private val store = ReadingStore(filesDir)
    /** False in a dev-start session, so perf and CI runs leave the device's reading data as they found it. */
    @Volatile
    private var persisting = true
    private val saver = ReadingSaver(viewModelScope, io, SAVE_DEBOUNCE_MS, { mine -> if (persisting) store.save(mine) }) {
        Log.w(TAG, "reading data save failed", it)
    }
    private var loading: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = openBook()

    /** Opens the Book once: a show while the first open is still running (a pause and resume) starts no second one. */
    internal fun openBook() {
        if (book.value != null || loading?.isActive == true) return
        loading = viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val file = downloadIfMissing()
                    Opened(file.name, parseEpub(file), devStart(), store.load())
                }
            }
                .onSuccess { (fileName, opened, start, fromDisk) ->
                    val largest = opened.chapters.indices.maxByOrNull { opened.chapters[it].text.length }
                    if (largest != null) {
                        Log.i(PERF_TAG, "book chapters=${opened.chapters.size} largest=$largest " +
                            "largestChars=${opened.chapters[largest].text.length}")
                    }
                    persisting = start == null
                    saver.loaded(fromDisk)
                    saver.change { it.shelve(opened.identifier, opened.title, fileName) }
                    if (start == null) fontStep.value = fromDisk.settings.fontStep.coerceIn(FONT_SIZES.indices)
                    if (start != null && opened.chapters.isNotEmpty()) {
                        val chapter = start.chapter.coerceIn(opened.chapters.indices)
                        windowChars = start.windowChars ?: WINDOW_CHARS
                        position.value = Position(chapter, start.offset.coerceIn(0, opened.chapters[chapter].text.length))
                        val ends = windows(opened.chapters[chapter], windowChars).joinToString(",") { it.end.toString() }
                        Log.i(PERF_TAG, "windows chapter=$chapter windowChars=$windowChars ends=$ends")
                    } else {
                        fromDisk.books[opened.identifier]?.place?.let(opened::resolve)?.let { position.value = it }
                    }
                    book.value = opened
                }
                .onFailure { status.value = "Couldn't open the book: ${it.message}" }
        }
    }

    private fun downloadIfMissing(): File {
        val file = File(filesDir, "alice.epub")
        if (!file.exists()) {
            status.value = "Downloading Alice…"
            val partial = File(filesDir, "alice.epub.part")
            URL(BOOK_URL).openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
        }
        return file
    }

    /**
     * Dev hook for `scripts/perf.sh`: filesDir/dev-start opens the book at a chapter and offset, at the
     * default font, with an optional window size (see [parseDevStart]). Such a session neither reads nor
     * saves the reading data, so a run never depends on or changes the device's Place and font step.
     * Only `adb shell run-as` can write that file, and run-as works on debuggable builds only. A read
     * error or garbage opens the book normally.
     */
    private fun devStart(): DevStart? = runCatching {
        File(filesDir, "dev-start").takeIf { it.exists() }?.readText()?.let(::parseDevStart)
    }.getOrNull()

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
        val chapters = book.value?.chapters ?: return
        this.typesetter = typesetter
        prefetching?.cancel()
        reading = Reading(chapters, measure = { pass, window -> measure(typesetter, pass, window, sync = true) }, linesOf = { it.lines }, windowChars = windowChars)
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
        val (chapter, offset) = position.value
        show(reason) { it.open(chapter, offset, typesetter.key(fontStep.value)) }
    }

    private fun turn(step: (Reading<WindowLayout>) -> Shown<WindowLayout>?) {
        val shown = show("chapter", step) ?: return
        position.value = Position(shown.pass.chapterIndex, shown.page.start)
        val identifier = book.value?.identifier ?: return
        val place = shown.pass.chapter.placeOf(shown.page.start, System.currentTimeMillis())
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
            Log.i(PERF_TAG, "pass reason=$reason chapter=${pass.chapterIndex} chars=${pass.length} font=${FONT_SIZES[pass.key.fontStep]} " +
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
        val layout = typesetter.measure(pass.chapter, pass.windows[window], pass.key.fontStep)
        if (sync) syncWindows++
        Log.i(PERF_TAG, "window pass=${pass.id} chapter=${pass.chapterIndex} index=$window " +
            "chars=${pass.windows[window].let { it.end - it.start }} measureMs=${ms(System.nanoTime() - start)} sync=$sync")
        return layout
    }

    private fun ms(ns: Long) = "%.1f".format(Locale.ROOT, ns / 1e6)
}
