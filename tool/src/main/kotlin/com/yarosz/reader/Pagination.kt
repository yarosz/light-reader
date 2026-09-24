package com.yarosz.reader

/** One laid-out line: the text offset it starts at and its vertical extent in pixels. */
data class LineMetrics(val start: Int, val top: Float, val bottom: Float)

/** A page is the half-open text range [start, end) plus the pixel band it occupies in the layout. */
data class Page(val start: Int, val end: Int, val top: Float, val bottom: Float)

/**
 * Greedily packs whole lines into pages no taller than [pageHeight].
 *
 * Pages always break on line boundaries, so drawing the full layout clipped to [Page.top, Page.bottom)
 * shows exactly the page's lines, with no reflow. A single line taller than the page gets a page to itself.
 */
fun paginate(lines: List<LineMetrics>, textLength: Int, pageHeight: Float): List<Page> {
    if (lines.isEmpty()) return listOf(Page(0, textLength, 0f, 0f))
    val pages = mutableListOf<Page>()
    var first = 0
    for (i in 1..lines.size) {
        val done = i == lines.size
        if (done || lines[i].bottom - lines[first].top > pageHeight) {
            val end = if (done) textLength else lines[i].start
            pages += Page(lines[first].start, end, lines[first].top, lines[i - 1].bottom)
            first = i
        }
    }
    return pages
}

/** Index of the page containing [offset]; offsets past the end land on the last page. */
fun pageIndexFor(pages: List<Page>, offset: Int): Int {
    val found = pages.binarySearch { it.start.compareTo(offset) }
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}
