package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Property tests: each runs against thousands of random chapters, font sizes, and page heights.
 * The layout is simulated (paragraphs broken into lines by a font-dependent width), which is all
 * [paginate] consumes; the real Compose layout feeds it the same shape of data.
 */
class PaginationTest {

    private val runs = 3_000

    /**
     * A chapter as paragraph lengths, the first one a heading; laid out at a font size into [LineMetrics].
     * Some lines end mid-word (hyphenated), and a mid-word line tends to be followed by another (cascades).
     */
    private class FakeChapter(val paragraphs: List<Int>) {
        val length = paragraphs.sum() + paragraphs.size - 1 // '\n' between paragraphs

        fun layout(fontSize: Float, rnd: Random): List<LineMetrics> {
            val charsPerLine = (1000f / (fontSize * 0.55f)).toInt().coerceAtLeast(1)
            val lineHeight = fontSize * 1.35f
            val hyphenRate = rnd.nextDouble(0.0, 0.5)
            val lines = mutableListOf<LineMetrics>()
            var offset = 0
            var y = 0f
            var midWord = false
            paragraphs.forEachIndexed { i, len ->
                var remaining = len
                do {
                    // Real line breaking wraps at word boundaries, so lines run a bit short.
                    val take = minOf(remaining, (charsPerLine * rnd.nextDouble(0.75, 1.0)).toInt().coerceAtLeast(1))
                    val height = if (i == 0) lineHeight * 1.25f else lineHeight
                    remaining -= take
                    midWord = remaining > 0 && rnd.nextDouble() < if (midWord) 0.7 else hyphenRate
                    lines += LineMetrics(offset, y, y + height, endsAtBreak = !midWord, heading = i == 0)
                    offset += take
                    y += height
                } while (remaining > 0)
                offset += 1 // the '\n'
            }
            return lines
        }
    }

    /** Line indices [first, last] of each Page, recovered from its text range. */
    private fun lineRanges(lines: List<LineMetrics>, pages: List<Page>): List<IntRange> = pages.map { page ->
        val first = lines.indexOfFirst { it.start == page.start }
        val next = lines.indexOfFirst { it.start >= page.end }
        first..(if (next < 0) lines.lastIndex else next - 1)
    }

    private fun greedyLast(lines: List<LineMetrics>, first: Int, height: Float): Int {
        var last = first
        while (last < lines.lastIndex && lines[last + 1].bottom - lines[first].top <= height) last++
        return last
    }

    private fun LineMetrics.legal() = endsAtBreak && !heading

    private fun LineMetrics.filled(top: Float, height: Float) = bottom - top >= MIN_PAGE_FILL * height

    private fun randomChapter(rnd: Random) = FakeChapter(List(rnd.nextInt(1, 60)) { rnd.nextInt(0, 1_500) })
    private fun randomFont(rnd: Random) = rnd.nextDouble(16.0, 48.0).toFloat()
    private fun randomPageHeight(rnd: Random) = rnd.nextDouble(200.0, 1_200.0).toFloat()

    private fun forAll(body: (Random) -> Unit) = repeat(runs) { seed -> body(Random(seed)) }

    @Test
    fun `pages tile the chapter exactly with no gaps or overlaps`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val pages = paginate(chapter.layout(randomFont(rnd), rnd), chapter.length, randomPageHeight(rnd))
        assertEquals(0, pages.first().start)
        assertEquals(chapter.length, pages.last().end)
        pages.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        pages.forEach { assertTrue(it.start < it.end || chapter.length == 0) }
    }

    @Test
    fun `no page is taller than the screen unless it holds a single oversized line`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val lines = chapter.layout(randomFont(rnd), rnd)
        val height = randomPageHeight(rnd)
        paginate(lines, chapter.length, height).forEach { page ->
            val pageLines = lines.filter { it.start >= page.start && it.start < page.end }
            assertTrue(page.bottom - page.top <= height || pageLines.size == 1, "page $page overflows $height")
        }
    }

    @Test
    fun `every page begins on a line boundary`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val lines = chapter.layout(randomFont(rnd), rnd)
        val lineStarts = lines.map { it.start }.toSet()
        paginate(lines, chapter.length, randomPageHeight(rnd)).forEach { assertTrue(it.start in lineStarts) }
    }

    @Test
    fun `every offset maps to the page that contains it`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val pages = paginate(chapter.layout(randomFont(rnd), rnd), chapter.length, randomPageHeight(rnd))
        repeat(20) {
            val offset = rnd.nextInt(0, chapter.length.coerceAtLeast(1))
            val page = pages[pageIndexFor(pages, offset)]
            assertTrue(offset >= page.start && offset < page.end.coerceAtLeast(1), "offset $offset not in $page")
        }
        assertEquals(pages.lastIndex, pageIndexFor(pages, chapter.length)) // "end of chapter" → last page
    }

    @Test
    fun `changing font size keeps the reader's first word on screen`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val height = randomPageHeight(rnd)
        val before = paginate(chapter.layout(randomFont(rnd), rnd), chapter.length, height)
        val anchor = before[rnd.nextInt(before.size)].start // top of the page being read
        val after = paginate(chapter.layout(randomFont(rnd), rnd), chapter.length, height)
        val page = after[pageIndexFor(after, anchor)]
        assertTrue(anchor >= page.start && (anchor < page.end || anchor == chapter.length))
    }

    @Test
    fun `switching font size and back returns to the exact same page`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val height = randomPageHeight(rnd)
        val font = randomFont(rnd)
        val layoutA = chapter.layout(font, Random(rnd.nextInt())) // same seed → same layout at the same font
        val pagesA = paginate(layoutA, chapter.length, height)
        val index = rnd.nextInt(pagesA.size)
        val anchor = pagesA[index].start
        // Detour through several other sizes; the anchor is never rewritten by a relayout.
        repeat(3) { pageIndexFor(paginate(chapter.layout(randomFont(rnd), rnd), chapter.length, height), anchor) }
        assertEquals(index, pageIndexFor(pagesA, anchor))
    }

    @Test
    fun `a page ends on a legal break unless none leaves it 70 percent full`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val lines = chapter.layout(randomFont(rnd), rnd)
        val height = randomPageHeight(rnd)
        lineRanges(lines, paginate(lines, chapter.length, height)).dropLast(1).forEach { range ->
            val top = lines[range.first].top
            val guardFired = (range.first..greedyLast(lines, range.first, height))
                .none { lines[it].legal() && lines[it].filled(top, height) }
            assertTrue(lines[range.last].legal() || guardFired, "page $range ends on an illegal line")
            if (guardFired) assertEquals(greedyLast(lines, range.first, height), range.last)
        }
    }

    @Test
    fun `a page ending on a legal break could not have ended on a later one`() = forAll { rnd ->
        val chapter = randomChapter(rnd)
        val lines = chapter.layout(randomFont(rnd), rnd)
        val height = randomPageHeight(rnd)
        lineRanges(lines, paginate(lines, chapter.length, height)).dropLast(1).forEach { range ->
            if (lines[range.last].legal()) {
                val later = (range.last + 1..greedyLast(lines, range.first, height)).filter { lines[it].legal() }
                assertTrue(later.isEmpty(), "page $range could have ended at $later")
            }
        }
    }

    /** Ten 10px lines fit a 100px page; lines past [count] keep the chapter going so no page is final. */
    private fun lines(count: Int = 15, midWord: Set<Int> = emptySet(), headings: Set<Int> = emptySet()) =
        List(count) { LineMetrics(it * 10, it * 10f, it * 10f + 10f, endsAtBreak = it !in midWord, heading = it in headings) }

    private fun firstPageLines(lines: List<LineMetrics>) = lineRanges(lines, paginate(lines, 150, 100f)).first()

    @Test
    fun `a cascade above 70 percent ends the page on the legal line before it`() {
        assertEquals(0..6, firstPageLines(lines(midWord = setOf(7, 8, 9))))
    }

    @Test
    fun `a cascade reaching below 70 percent ends the page greedily mid-word`() {
        assertEquals(0..9, firstPageLines(lines(midWord = setOf(3, 4, 5, 6, 7, 8, 9))))
    }

    @Test
    fun `a heading as the last fitting line moves to the next page`() {
        val lines = lines(headings = setOf(9))
        assertEquals(0..8, firstPageLines(lines))
        assertEquals(9, lineRanges(lines, paginate(lines, 150, 100f))[1].first)
    }

    @Test
    fun `endsAtBreak accepts whitespace and paragraph ends, rejects mid-word breaks`() {
        assertTrue(endsAtBreak("the quick", 4))
        assertTrue(endsAtBreak("one\ntwo", 4))
        assertTrue(endsAtBreak("the end", 7))
        assertFalse(endsAtBreak("trouble", 4))
        assertFalse(endsAtBreak("well-known", 5))
    }

    @Test
    fun `kindAt finds the block holding an offset`() {
        val chapter = Chapter("", listOf(Block(BlockKind.Heading, "Title"), Block(BlockKind.Paragraph, "Body")))
        assertEquals(BlockKind.Heading, chapter.kindAt(0))
        assertEquals(BlockKind.Heading, chapter.kindAt(4))
        assertEquals(BlockKind.Heading, chapter.kindAt(5))
        assertEquals(BlockKind.Paragraph, chapter.kindAt(6))
        assertEquals(BlockKind.Paragraph, chapter.kindAt(9))
    }
}
