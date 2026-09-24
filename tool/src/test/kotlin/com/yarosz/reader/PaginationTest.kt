package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property tests: each runs against thousands of random chapters, font sizes, and page heights.
 * The layout is simulated (paragraphs broken into lines by a font-dependent width), which is all
 * [paginate] consumes; the real Compose layout feeds it the same shape of data.
 */
class PaginationTest {

    private val runs = 3_000

    /** A chapter as paragraph lengths; laid out at a font size into [LineMetrics]. */
    private class FakeChapter(val paragraphs: List<Int>) {
        val length = paragraphs.sum() + paragraphs.size - 1 // '\n' between paragraphs

        fun layout(fontSize: Float, rnd: Random): List<LineMetrics> {
            val charsPerLine = (1000f / (fontSize * 0.55f)).toInt().coerceAtLeast(1)
            val lineHeight = fontSize * 1.35f
            val lines = mutableListOf<LineMetrics>()
            var offset = 0
            var y = 0f
            paragraphs.forEachIndexed { i, len ->
                var remaining = len
                do {
                    // Real line breaking wraps at word boundaries, so lines run a bit short.
                    val take = minOf(remaining, (charsPerLine * rnd.nextDouble(0.75, 1.0)).toInt().coerceAtLeast(1))
                    val height = if (i == 0) lineHeight * 1.25f else lineHeight // heading line
                    lines += LineMetrics(offset, y, y + height)
                    offset += take
                    remaining -= take
                    y += height
                } while (remaining > 0)
                offset += 1 // the '\n'
            }
            return lines
        }
    }

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
}
