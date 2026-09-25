package com.yarosz.reader

/**
 * One laid-out line: the text offset it starts at, its vertical extent in pixels, whether it ends at a
 * legal break (see [endsAtBreak]), and whether it starts inside a heading.
 */
data class LineMetrics(val start: Int, val top: Float, val bottom: Float, val endsAtBreak: Boolean, val heading: Boolean)

/** A page is the half-open text range [start, end) plus the pixel band it occupies in the layout. */
data class Page(val start: Int, val end: Int, val top: Float, val bottom: Float)

/**
 * Whether a line followed by one starting at [nextLineStart] ends at whitespace or a paragraph end.
 * Judged on the source text, so soft-hyphen ("trou-/ble") and compound ("well-/known") breaks don't count.
 */
fun endsAtBreak(text: CharSequence, nextLineStart: Int): Boolean =
    nextLineStart >= text.length || text[nextLineStart - 1].isWhitespace()

/**
 * Packs whole lines into pages no taller than [pageHeight]. Each Page ends on the last fitting line that
 * ends at a legal break and isn't a heading; if no such line leaves the Page at least [MIN_PAGE_FILL]
 * full, it ends on the last line that fits.
 *
 * Pages always break on line boundaries, so drawing the full layout clipped to [Page.top, Page.bottom)
 * shows exactly the page's lines, with no reflow. A single line taller than the page gets a page to itself.
 */
fun paginate(lines: List<LineMetrics>, textLength: Int, pageHeight: Float): List<Page> {
    if (lines.isEmpty()) return listOf(Page(0, textLength, 0f, 0f))
    val pages = mutableListOf<Page>()
    var first = 0
    while (first < lines.size) {
        val top = lines[first].top
        var last = first
        while (last < lines.lastIndex && lines[last + 1].bottom - top <= pageHeight) last++
        val end = if (last == lines.lastIndex) last else (last downTo first).firstOrNull {
            val line = lines[it]
            line.endsAtBreak && !line.heading && line.bottom - top >= MIN_PAGE_FILL * pageHeight
        } ?: last
        val endOffset = if (end == lines.lastIndex) textLength else lines[end + 1].start
        pages += Page(lines[first].start, endOffset, top, lines[end].bottom)
        first = end + 1
    }
    return pages
}

/** Index of the page containing [offset]; offsets past the end land on the last page. */
fun pageIndexFor(pages: List<Page>, offset: Int): Int {
    val found = pages.binarySearch { it.start.compareTo(offset) }
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}
