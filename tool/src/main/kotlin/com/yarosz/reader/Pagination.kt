package com.yarosz.reader

/**
 * One laid-out line: the [SpineItem.text] offset it starts at, its vertical extent in the pixels of the
 * layout that measured it, whether it ends at a legal break (see [endsAtBreak]), and whether it starts
 * where a Page may not end ([SpineItem.keepsWithNext]).
 */
data class LineMetrics(val start: Int, val top: Float, val bottom: Float, val endsAtBreak: Boolean, val heading: Boolean)

/** The vertical slice [top, bottom) of window [window]'s layout, in that layout's pixels. */
data class Band(val window: Int, val top: Float, val bottom: Float)

/**
 * A page is the half-open text range [start, end) plus the bands that draw it: one per window it spans,
 * in reading order. Pages break on line boundaries, so drawing each band's window layout shifted up by
 * [Band.top], clipped to the band's height, and stacking the bands shows exactly the page's lines with
 * no reflow.
 */
data class Page(val start: Int, val end: Int, val bands: List<Band>)

/**
 * Pages packed outward from an anchor. [before] ends where [fromAnchor] begins, and [fromAnchor]'s
 * first page starts on the line holding the anchor, or a line or two above it where that line starts
 * mid-word (see [pack]). [needBefore] and [needAfter] are null when no
 * unmeasured window can add pages on that side; otherwise the index of the next one that could, or the
 * anchor's own window, which must be measured first.
 *
 * [fromAnchor] is empty, and [anchorPage] null, in two cases: the anchor is at the Spine item's end, or
 * the anchor's page is withheld because window [needAfter] might still add lines to it (see [pack]).
 * [needAfter] tells the two apart.
 */
data class PackedPages(val before: List<Page>, val fromAnchor: List<Page>, val needBefore: Int?, val needAfter: Int?) {
    /**
     * The page to show. Null means "not yet" when [needAfter] is set, and "anchor at the Spine item's end,
     * show `before.last()`" when it is not.
     */
    val anchorPage: Page? = fromAnchor.firstOrNull()

    /**
     * Every page in reading order, for navigation. `pageIndexFor(pages, anchor)` finds the anchor's page
     * only while [anchorPage] is non-null; with it withheld, the anchor lands on the page before it.
     */
    val pages: List<Page> = before + fromAnchor
}

/** Below this fill a Page ignores the page-break rules and breaks greedily (DESIGN.md). */
const val MIN_PAGE_FILL = 0.7f

/**
 * Whether a line followed by one starting at [nextLineStart] (> 0) ends at whitespace or a paragraph end.
 * Judged on the source text, so soft-hyphen ("trou-/ble") and compound ("well-/known") breaks don't count.
 */
fun endsAtBreak(text: CharSequence, nextLineStart: Int): Boolean =
    nextLineStart >= text.length || text[nextLineStart - 1].isWhitespace()

/**
 * Packs a Spine item's measured windows into pages no taller than [pageHeight], outward from [anchor], an
 * offset in [SpineItem.text]. [lines] holds each window's lines in the window's own layout pixels, or null
 * for a window not measured yet, one entry per window. A negative anchor packs from the Spine item's start;
 * one at or past its end packs everything backward.
 *
 * The line holding the anchor starts a page, or, when that line starts mid-word, the nearest line above it
 * that starts a word, if that is within 1 − [MIN_PAGE_FILL] of a page (see [wordStart]). Forward from it, each page ends on the last fitting line
 * that ends at a legal break and isn't a heading, or, if no such line leaves the page at least
 * [MIN_PAGE_FILL] full, on the last line that fits. Backward from it the rules mirror: each page ends
 * where the page below starts, and takes the earliest fitting start whose preceding line is such a legal
 * end (the Spine item's first line always is), or, if no such start leaves the page at least
 * [MIN_PAGE_FILL] full, the earliest start that fits. So every page end is legal unless the guard fired,
 * in both directions; what remains asymmetric is that a backward pass may tile a stretch differently,
 * but as legally, from a forward one. Backward, only the Spine item's first page, and the pages on either
side of [pageBreak], may be short. A single
 * line taller than the page gets a page to itself. Heights add across a window seam, and a page spanning
 * windows gets a band per window.
 *
 * A page is emitted only once no further window can change it: the next line on its side doesn't fit,
 * the Spine item ends there, or [pageBreak] follows it. So when the anchor lies within about a page of its measured run's end and
 * the next window is unmeasured, the anchor's own page is withheld: [PackedPages.fromAnchor] is empty
 * and [PackedPages.needAfter] names the window to measure. Each page depends only on lines on its own
 * side of the anchor, so re-packing with more windows measured, for the same anchor, only appends pages
 * at either end, and the caller may keep pages across calls.
 *
 * [pageBreak], when a line starts there, starts a Page whatever the rules above say: no Page holds lines
 * on both sides of it, so the Page before it ends there however short. It is the Book's text end
 * ([OpenBook.textEnd]) inside its Spine item. [windows] starts a window there, so the Page before it is
 * emitted without waiting for the next window's lines.
 */
fun pack(windows: List<Window>, lines: List<List<LineMetrics>?>, anchor: Int, pageHeight: Float, pageBreak: Int? = null): PackedPages {
    require(lines.size == windows.size) { "${lines.size} line lists for ${windows.size} windows" }
    if (windows.isEmpty()) return PackedPages(emptyList(), emptyList(), needBefore = null, needAfter = null)
    val at = windowIndexFor(windows, anchor)
    if (lines[at] == null) return PackedPages(emptyList(), emptyList(), needBefore = at.takeIf { anchor > 0 }, needAfter = at)
    var lo = at
    while (lo > 0 && lines[lo - 1] != null) lo--
    var hi = at
    while (hi < windows.lastIndex && lines[hi + 1] != null) hi++
    val stacked = stack(lines.subList(lo, hi + 1).filterNotNull(), lo, at - lo)
    val runEnd = windows[hi].end
    val from = if (anchor >= runEnd) stacked.size else wordStart(stacked, stacked.indexContaining(anchor) { it.line.start }, pageHeight)

    fun page(first: Int, last: Int): Page {
        val end = if (last < stacked.lastIndex) stacked[last + 1].line.start else runEnd
        val bands = (first..last).groupBy { stacked[it].window }
            .map { (window, i) -> Band(window, stacked[i.first()].line.top, stacked[i.last()].line.bottom) }
        return Page(stacked[first].line.start, end, bands)
    }

    fun startsPage(i: Int) = stacked[i].line.start == pageBreak

    val fromAnchor = mutableListOf<Page>()
    var first = from
    while (first < stacked.size) {
        val y = stacked[first].y
        var last = first
        while (last < stacked.lastIndex && !startsPage(last + 1) && stacked[last + 1].yEnd - y <= pageHeight) last++
        val reachedEnd = last == stacked.lastIndex
        val broken = if (reachedEnd) runEnd == pageBreak else startsPage(last + 1)
        if (reachedEnd && hi < windows.lastIndex && !broken) break // the next window may hold more fitting lines
        val end = if (reachedEnd || broken) last else (last downTo first).firstOrNull {
            val line = stacked[it].line
            line.endsAtBreak && !line.heading && stacked[it].yEnd - y >= MIN_PAGE_FILL * pageHeight
        } ?: last
        fromAnchor += page(first, end)
        first = end + 1
    }

    val before = ArrayDeque<Page>()
    var last = from - 1
    while (last >= 0) {
        val yEnd = stacked[last].yEnd
        var start = last
        while (start > 0 && !startsPage(start) && yEnd - stacked[start - 1].y <= pageHeight) start--
        val broken = startsPage(start)
        if (start == 0 && lo > 0 && !broken) break // the window above may hold more fitting lines
        val begin = if (broken) start else (start..last).firstOrNull {
            val above = if (it == 0) null else stacked[it - 1].line
            (above == null || above.endsAtBreak && !above.heading) && yEnd - stacked[it].y >= MIN_PAGE_FILL * pageHeight
        } ?: start
        before.addFirst(page(begin, last))
        last = begin - 1
    }
    return PackedPages(
        before.toList(),
        fromAnchor,
        needBefore = (lo - 1).takeIf { lo > 0 },
        needAfter = (hi + 1).takeIf { hi < windows.lastIndex },
    )
}

/**
 * The line a page anchored on line [line] starts on: the nearest line at or above it that starts a word,
 * so a Place from another layout that falls on the tail of a hyphenated word ("hor-/rors") shows the whole
 * word, and the page above ends at a legal break. Only when that line is more than 1 − [MIN_PAGE_FILL] of
 * a page up (a long cascade), or would push [line] off the page, does [line] itself start the page. A
 * window's first line always starts a word (windows are cut at blocks), so the answer never depends on
 * the window above, and the packer stays deterministic.
 */
private fun wordStart(stacked: List<Stacked>, line: Int, pageHeight: Float): Int {
    var start = line
    while (start > 0 && stacked[start - 1].window == stacked[start].window && !stacked[start - 1].line.endsAtBreak) start--
    val fits = stacked[line].y - stacked[start].y <= (1 - MIN_PAGE_FILL) * pageHeight && stacked[line].yEnd - stacked[start].y <= pageHeight
    return if (fits) start else line
}

/**
 * A line placed in one coordinate space shared by a run of windows: [y] to [yEnd] there, while [line]
 * keeps its own window's pixels for the bands.
 */
private class Stacked(val window: Int, val line: LineMetrics, val y: Float, val yEnd: Float)

/**
 * Lines of a contiguous run of windows, the first at index [firstWindow], stacked so each window starts
 * where the previous ends. Window [origin] of the run sits at 0 and the rest are placed outward from it,
 * so a line's coordinates depend only on the windows between it and the anchor, never on how far the
 * run has grown: that is what keeps the packer bit-for-bit deterministic across calls.
 *
 * Every window has a line: [windows] never emits an empty one, and a layout of non-empty text has at
 * least one line. An empty list here is a caller bug and fails loudly.
 */
private fun stack(run: List<List<LineMetrics>>, firstWindow: Int, origin: Int): List<Stacked> {
    val heights = run.map { it.last().bottom - it.first().top }
    val floors = FloatArray(run.size)
    for (w in origin + 1 until run.size) floors[w] = floors[w - 1] + heights[w - 1]
    for (w in origin - 1 downTo 0) floors[w] = floors[w + 1] - heights[w]
    return run.flatMapIndexed { w, lines ->
        val shift = floors[w] - lines.first().top
        lines.map { Stacked(firstWindow + w, it, it.top + shift, it.bottom + shift) }
    }
}

/** Index of the page containing [offset]; offsets past the end land on the last page. */
fun pageIndexFor(pages: List<Page>, offset: Int): Int = pages.indexContaining(offset) { it.start }

/** Index of the last element whose [start] is at most [offset], starts ascending; 0 when none is. */
internal fun <T> List<T>.indexContaining(offset: Int, start: (T) -> Int): Int {
    val found = binarySearch { start(it).compareTo(offset) }
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}
