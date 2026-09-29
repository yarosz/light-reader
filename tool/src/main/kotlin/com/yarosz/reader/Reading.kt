package com.yarosz.reader

/** Windows measured in the background on each side of the one being read (ADR 0007's neighbours). */
const val PREFETCH_WINDOWS = 2

/** Spine items whose pass stays cached, so turning back into one shows the Pages the reader saw (see [backwardLanding]). */
const val CACHED_PASSES = 4

/** What a layout depends on besides its text: the type size and the column. Any change starts a new pass. */
data class LayoutKey(val fontStep: Int, val widthPx: Int, val pageHeightPx: Int)

/**
 * One layout pass (ADR 0007): a Spine item's windows at one [LayoutKey], measured outward from [anchor]
 * and re-packed as each window lands. [M] is a measured window as the platform keeps it, its layout
 * for drawing and its lines for packing; the pass reads only the lines, through [linesOf]. A Page once
 * packed never changes (see [pack]), so turning back shows the Page just read. [id] is for logs: it
 * is unique within one [Reading] only, so compare passes by identity. [item] is [spineItem]'s index in
 * the Book's Spine items. A Page never spans [pageBreak] (see [pack]). With [exact], as for a Chapter
 * jump, the first Page starts on [anchor]'s own line, even one that starts mid-word ([pack]).
 */
class Pass<M>(
    val id: Int,
    val item: Int,
    val spineItem: SpineItem,
    val key: LayoutKey,
    val windows: List<Window>,
    val anchor: Int,
    private val pageBreak: Int? = null,
    private val exact: Boolean = false,
    private val linesOf: (M) -> List<LineMetrics>,
) {
    private val measured = MutableList<M?>(windows.size) { null }

    val length: Int = spineItem.text.length

    var packed: PackedPages = repack()
        private set

    val pages: List<Page> get() = packed.pages

    fun measured(window: Int): M? = measured[window]

    /** Keeps [layout] for [window] unless it is already measured: the Pages drawn from the first layout must not change under the reader. */
    fun record(window: Int, layout: M) {
        if (measured[window] != null) return
        measured[window] = layout
        packed = repack()
    }

    private fun repack() = pack(windows, measured.map { it?.let(linesOf) }, anchor, key.pageHeightPx.toFloat(), pageBreak, exact)

    /** Where [page]'s first line ends: the next line's start, capped at the Page's end. [page] is one of [pages], so its first window is measured. */
    fun firstLineEnd(page: Page): Int {
        val window = page.bands.first().window
        val next = measured[window]?.let(linesOf)?.firstOrNull { it.start > page.start }?.start ?: windows[window].end
        return minOf(next, page.end)
    }

    /** The Page holding [offset], the last Page for offsets past the end, or null while it isn't packed yet. */
    fun pageAt(offset: Int): Page? = when {
        pages.isEmpty() || offset < pages.first().start -> null
        offset >= pages.last().end && packed.needAfter != null -> null
        else -> pages[pageIndexFor(pages, offset)]
    }

    /** The window to measure next so that [pageAt] can find [offset]; null when it already can, or never will (an empty Spine item). */
    fun neededFor(offset: Int): Int? = when {
        pages.isEmpty() -> packed.needAfter ?: packed.needBefore
        offset < pages.first().start -> packed.needBefore
        offset >= pages.last().end -> packed.needAfter
        else -> null
    }

    /**
     * The unmeasured window nearest the one holding [offset] and within [budget] windows of it, the
     * later side first since reading runs forward; null once both sides are covered that far.
     */
    fun prefetchFor(offset: Int, budget: Int): Int? {
        val at = windowIndexFor(windows, offset)
        return packed.needAfter?.takeIf { it <= at + budget } ?: packed.needBefore?.takeIf { it >= at - budget }
    }
}

/** The Page on screen and the pass it came from. */
data class Shown<M>(val pass: Pass<M>, val page: Page)

/** Where turning back into a Spine item lands (see [backwardLanding]). */
sealed interface Landing {
    /** On [page], the last Page the reader saw there, from a pass still cached. */
    data class Cached(val page: Page) : Landing

    /** On the last Page of a new pass anchored at [anchor], the Spine item's end. */
    data class Fresh(val anchor: Int) : Landing
}

/**
 * Turning back past a Spine item's first Page shows the last Page the reader already saw in the Spine item
 * before, when that Spine item's pass is [cached] at the same [key] and reached the Spine item's end
 * ([length]); otherwise the last Page of a fresh pass packed backward from the end under the mirrored
 * page-break rules (ADR 0007, DESIGN.md).
 */
fun backwardLanding(cached: Pass<*>?, key: LayoutKey, length: Int): Landing {
    val last = cached?.takeIf { it.key == key }?.pages?.lastOrNull()
    return if (last != null && last.end == length) Landing.Cached(last) else Landing.Fresh(length)
}

/**
 * The open Book's layout state: the passes of recently read Spine items at the current [LayoutKey],
 * the Page being shown, and which Page follows or precedes it. Measuring is injected so this stays
 * pure: [measure] runs synchronously when a Page can't show without it, and [prefetchTarget] names the
 * window worth measuring in the background. Passes are cached per Spine item, most recently shown last,
 * and dropped on a key change or beyond [CACHED_PASSES]. A Page never spans [textEnd] ([OpenBook.textEnd]):
 * the last Page of the text ends there, and Back matter starts on a Page of its own.
 */
class Reading<M>(
    private val spineItems: List<SpineItem>,
    private val measure: (Pass<M>, Int) -> M,
    private val linesOf: (M) -> List<LineMetrics>,
    private val windowChars: Int = WINDOW_CHARS,
    private val textEnd: SpinePoint? = null,
) {
    private val passes = LinkedHashMap<Int, Pass<M>>()
    /** Passes this Reading has started; also the next pass's id. */
    var passesStarted = 0
        private set
    private var shown: Shown<M>? = null

    /** Shows the Page holding [offset] in Spine item [item] at [key]: from a cached pass with that Page, else a new pass anchored there. */
    fun open(item: Int, offset: Int, key: LayoutKey): Shown<M> {
        passes.values.removeAll { it.key != key }
        return enter(item, offset, key)
    }

    /**
     * Shows the Page whose first line holds [offset] in Spine item [item] at [key], as a jump to a Chapter
     * needs: from a cached pass only when it has such a Page, else a new exact pass anchored there, whose
     * first Page starts on [offset]'s line even when that line starts mid-word, as a Chapter anchored
     * mid-paragraph may ([pack]). At a line start, as Chapters mostly are, that Page starts exactly at
     * [offset]. An [offset] at the end of a Spine item jumps to the next one's start.
     */
    fun jump(item: Int, offset: Int, key: LayoutKey): Shown<M> {
        passes.values.removeAll { it.key != key }
        if (offset == spineItems[item].text.length && item < spineItems.lastIndex) return enter(item + 1, 0, key, exact = true)
        return enter(item, offset, key, exact = true)
    }

    /** The Page after the shown one, the next Spine item's first past a Spine item's end; null at the book's end. */
    fun next(): Shown<M>? {
        val (pass, page) = shown ?: return null
        return when {
            page.end < pass.length -> turnTo(pass, page.end)
            pass.item + 1 < spineItems.size -> enter(pass.item + 1, 0, pass.key)
            else -> null
        }
    }

    /** The Page before the shown one, into the previous Spine item per [backwardLanding]; null at the book's start. */
    fun previous(): Shown<M>? {
        val (pass, page) = shown ?: return null
        if (page.start > 0) return turnTo(pass, page.start - 1)
        if (pass.item == 0) return null
        val before = pass.item - 1
        return when (val landing = backwardLanding(passes[before], pass.key, spineItems[before].text.length)) {
            is Landing.Cached -> enter(before, landing.page.start, pass.key)
            is Landing.Fresh -> enter(before, landing.anchor, pass.key)
        }
    }

    /** The shown pass and the window to measure for it in the background; null when covered [PREFETCH_WINDOWS] deep. */
    fun prefetchTarget(): Pair<Pass<M>, Int>? {
        val (pass, page) = shown ?: return null
        return pass.prefetchFor(page.start, PREFETCH_WINDOWS)?.let { pass to it }
    }

    /** Shows the Page holding [offset], or with [exact] the Page whose first line holds it, from Spine item [item]'s cached pass when it has that Page, else a new pass anchored there. */
    private fun enter(item: Int, offset: Int, key: LayoutKey, exact: Boolean = false): Shown<M> {
        val spineItem = spineItems[item]
        val pageBreak = textEnd?.takeIf { it.item == item }?.char
        val pass = passes.remove(item)?.takeIf { cached -> cached.pageAt(offset)?.let { !exact || offset < cached.firstLineEnd(it) } == true }
            ?: Pass(passesStarted++, item, spineItem, key, windows(spineItem, windowChars, pageBreak), offset, pageBreak, exact, linesOf)
        passes[item] = pass
        while (passes.size > CACHED_PASSES) passes.remove(passes.keys.first())
        return turnTo(pass, offset)
    }

    /** Shows [pass]'s Page holding [offset], measuring windows synchronously until it is packed. */
    private fun turnTo(pass: Pass<M>, offset: Int): Shown<M> {
        var page = pass.pageAt(offset)
        while (page == null) {
            val window = pass.neededFor(offset) ?: error("Spine item ${pass.item} has no Page holding $offset")
            pass.record(window, measure(pass, window))
            page = pass.pageAt(offset)
        }
        return Shown(pass, page).also { shown = it }
    }
}
