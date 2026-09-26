package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property tests of packing pages outward from an anchor across measured windows (ADR 0007). Each runs
 * against thousands of random [FakeChapter]s cut into windows of a random size, so pages span one, two,
 * or many windows. The examples at the end pin the seam, stop, and withholding behaviour on small fixtures.
 */
class PackTest {

    private class Case(val chapter: FakeChapter, val lines: List<LineMetrics>, val windows: List<Window>, val cut: List<List<LineMetrics>>, val height: Float) {
        val length get() = chapter.length
    }

    private fun randomCase(rnd: Random, wholePixels: Boolean = true): Case {
        val chapter = FakeChapter.random(rnd)
        val lines = chapter.layout(randomFont(rnd), rnd, wholePixels)
        val windows = windows(chapter.chapter, rnd.nextInt(1, 6_000))
        return Case(chapter, lines, windows, chapter.cut(lines, windows, rnd), randomPageHeight(rnd))
    }

    /** The chapter's ends, past its end, a line start, or any offset, since a Place from another layout can fall mid-line. */
    private fun randomAnchor(rnd: Random, case: Case): Int = when (rnd.nextInt(5)) {
        0 -> 0
        1 -> case.length
        2 -> case.length + 1 + rnd.nextInt(0, 1_000)
        3 -> case.lines[rnd.nextInt(case.lines.size)].start
        else -> rnd.nextInt(0, case.length + 1)
    }

    private fun anchorLine(case: Case, anchor: Int) = case.lines.indexContaining(anchor) { it.start }

    private fun textRanges(pages: List<Page>) = pages.map { it.start to it.end }

    private fun lineCount(case: Case, page: Page) = case.lines.count { it.start >= page.start && it.start < page.end }

    @Test
    fun `with every window measured, pages tile the chapter on line starts and the anchor's page starts at its line`() = forAll { rnd ->
        val case = randomCase(rnd)
        val anchor = randomAnchor(rnd, case)
        val packed = pack(case.windows, case.cut, anchor, case.height)
        assertNull(packed.needBefore)
        assertNull(packed.needAfter)
        assertEquals(0, packed.pages.first().start)
        assertEquals(case.length, packed.pages.last().end)
        packed.pages.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        val lineStarts = case.lines.map { it.start }.toSet()
        packed.pages.forEach { assertTrue(it.start in lineStarts, "page $it starts mid-line") }
        if (anchor < case.length) {
            assertEquals(case.lines[anchorLine(case, anchor)].start, packed.anchorPage?.start)
        } else {
            assertNull(packed.anchorPage)
            assertEquals(emptyList(), packed.fromAnchor)
        }
    }

    /**
     * The pre-windowing paginate as the oracle: its per-page loop unchanged, started at line [from] rather
     * than 0 and reporting each page's text range and height. It packed page by page without lookback, so
     * starting it at [from] is exactly the old algorithm on the lines from the anchor on.
     */
    private fun referencePaginate(lines: List<LineMetrics>, from: Int, textLength: Int, pageHeight: Float): List<Triple<Int, Int, Float>> {
        val pages = mutableListOf<Triple<Int, Int, Float>>()
        var first = from
        while (first < lines.size) {
            val top = lines[first].top
            var last = first
            while (last < lines.lastIndex && lines[last + 1].bottom - top <= pageHeight) last++
            val end = if (last == lines.lastIndex) last else (last downTo first).firstOrNull {
                val line = lines[it]
                line.endsAtBreak && !line.heading && line.bottom - top >= MIN_PAGE_FILL * pageHeight
            } ?: last
            val endOffset = if (end == lines.lastIndex) textLength else lines[end + 1].start
            pages += Triple(lines[first].start, endOffset, lines[end].bottom - top)
            first = end + 1
        }
        return pages
    }

    /**
     * Two checks: the split matches a single-window pack of the same lines, and the forward pages match
     * the oracle. The first is exact only because the fixture's pixels are whole numbers (the product never
     * lays out a whole chapter, so a one-ulp seam difference there would not be a product fault).
     */
    @Test
    fun `the window split changes no page, and forward pages match the whole-chapter pagination from the anchor line`() = forAll { rnd ->
        val case = randomCase(rnd)
        val anchor = randomAnchor(rnd, case)
        val split = pack(case.windows, case.cut, anchor, case.height)
        val whole = pack(listOf(wholeWindow(case.length)), listOf(case.lines), anchor, case.height)
        assertEquals(textRanges(whole.before), textRanges(split.before))
        assertEquals(textRanges(whole.fromAnchor), textRanges(split.fromAnchor))
        assertEquals(whole.pages.map { it.height }, split.pages.map { it.height })
        val from = if (anchor >= case.length) case.lines.size else anchorLine(case, anchor)
        val expected = referencePaginate(case.lines, from, case.length, case.height)
        assertEquals(expected, split.fromAnchor.map { Triple(it.start, it.end, it.height) })
    }

    private data class Step(val measured: List<List<LineMetrics>?>, val packed: PackedPages)

    /** Packs after measuring each window in a random order, asserting that no page produced earlier changes; returns every step. */
    private fun measureInRandomOrder(case: Case, anchor: Int, rnd: Random): List<Step> {
        val measured = MutableList<List<LineMetrics>?>(case.windows.size) { null }
        var seen = emptyMap<Int, Page>()
        return case.windows.indices.shuffled(rnd).map { w ->
            measured[w] = case.cut[w]
            val packed = pack(case.windows, measured, anchor, case.height)
            val now = packed.pages.associateBy { it.start }
            seen.forEach { (start, page) -> assertEquals(page, now[start], "page at $start changed after measuring window $w") }
            seen = now
            Step(measured.toList(), packed)
        }
    }

    @Test
    fun `a page once produced never changes as windows are measured in any order, and needBefore and needAfter name the next unmeasured window`() = forAll { rnd ->
        val case = randomCase(rnd)
        val anchor = randomAnchor(rnd, case)
        val at = windowIndexFor(case.windows, anchor)
        val steps = measureInRandomOrder(case, anchor, rnd)
        steps.forEach { (measured, packed) ->
            packed.pages.zipWithNext { a, b -> assertEquals(a.end, b.start) }
            packed.pages.firstOrNull()?.let { assertEquals(it.start != 0, packed.needBefore != null) }
            packed.pages.lastOrNull()?.let { assertEquals(it.end != case.length, packed.needAfter != null) }
            if (packed.pages.isEmpty()) assertTrue(packed.needBefore != null || packed.needAfter != null)
            packed.needBefore?.let { w ->
                assertNull(measured[w], "needBefore $w is already measured")
                assertTrue((w + 1..at).all { measured[it] != null }, "needBefore $w skips an unmeasured window")
            }
            packed.needAfter?.let { w ->
                assertNull(measured[w], "needAfter $w is already measured")
                assertTrue((at until w).all { measured[it] != null }, "needAfter $w skips an unmeasured window")
            }
        }
        assertEquals(pack(case.windows, case.cut, anchor, case.height), steps.last().packed)
    }

    @Test
    fun `with fractional line heights, pages still never change once produced and never overflow`() = forAll { rnd ->
        val case = randomCase(rnd, wholePixels = false)
        val anchor = randomAnchor(rnd, case)
        measureInRandomOrder(case, anchor, rnd).flatMap { it.packed.pages }.forEach { page ->
            assertTrue(page.height <= case.height || lineCount(case, page) == 1, "page $page overflows ${case.height}")
        }
    }

    /**
     * Per Page: a forward Page ends on a legal line and a backward Page starts below one, unless the guard
     * fired. That is also the boundary form: between consecutive forward Pages (a, b) the break is legal
     * unless the guard fired for a, and between consecutive backward Pages unless it fired for b. Exempt
     * are the last forward Page, which ends where the chapter does, and the break at the anchor, which
     * the anchor fixes.
     */
    @Test
    fun `pages obey the page-break rules in both directions, ending or starting as late or early as the rules allow`() = forAll { rnd ->
        val case = randomCase(rnd)
        val lines = case.lines
        val height = case.height
        val packed = pack(case.windows, case.cut, randomAnchor(rnd, case), height)
        lineRanges(lines, packed.fromAnchor).dropLast(1).forEach { range ->
            val top = lines[range.first].top
            val guardFired = (range.first..greedyLast(lines, range.first, height))
                .none { lines[it].legal() && lines[it].filled(top, height) }
            assertTrue(lines[range.last].legal() || guardFired, "page $range ends on an illegal line")
            if (guardFired) assertEquals(greedyLast(lines, range.first, height), range.last)
            else assertTrue((range.last + 1..greedyLast(lines, range.first, height)).none { lines[it].legal() }, "page $range could have ended later")
        }
        lineRanges(lines, packed.before).forEach { range ->
            val bottom = lines[range.last].bottom
            val greedy = greedyFirst(lines, range.last, height)
            assertTrue(bottom - lines[range.first].top <= height || range.first == range.last, "page $range overflows")
            val guardFired = (greedy..range.last).none { lines.legalStart(it) && lines[range.last].filled(lines[it].top, height) }
            assertTrue(lines.legalStart(range.first) || guardFired, "page $range starts below an illegal end")
            if (guardFired) assertEquals(greedy, range.first)
            else assertTrue((greedy until range.first).none { lines.legalStart(it) }, "page $range could have started earlier")
        }
    }

    @Test
    fun `bands lie on their window's lines, one per adjacent window, and sum to at most a page`() = forAll { rnd ->
        val case = randomCase(rnd)
        val measured = case.cut.map { if (rnd.nextBoolean()) it else null }
        pack(case.windows, measured, randomAnchor(rnd, case), case.height).pages.forEach { page ->
            assertTrue(page.bands.isNotEmpty())
            page.bands.zipWithNext { a, b -> assertEquals(a.window + 1, b.window) }
            page.bands.forEach { band ->
                val own = case.cut[band.window].filter { it.start >= page.start && it.start < page.end }
                assertEquals(own.first().top, band.top)
                assertEquals(own.last().bottom, band.bottom)
            }
            assertTrue(page.height <= case.height || lineCount(case, page) == 1, "page $page overflows ${case.height}")
        }
    }

    @Test
    fun `pageIndexFor maps every offset to the page holding it, and the chapter's end to the last page`() = forAll { rnd ->
        val case = randomCase(rnd)
        val pages = pack(case.windows, case.cut, randomAnchor(rnd, case), case.height).pages
        repeat(20) {
            val offset = rnd.nextInt(0, case.length)
            val page = pages[pageIndexFor(pages, offset)]
            assertTrue(offset >= page.start && offset < page.end, "offset $offset not in $page")
        }
        assertEquals(pages.lastIndex, pageIndexFor(pages, case.length))
    }

    @Test
    fun `a font change keeps the anchor on the shown page, which pageIndexFor finds`() = forAll { rnd ->
        val case = randomCase(rnd)
        val pagesBefore = pack(case.windows, case.cut, 0, case.height).pages
        val anchor = pagesBefore[rnd.nextInt(pagesBefore.size)].start
        val relaid = case.chapter.cut(case.chapter.layout(randomFont(rnd), rnd), case.windows, rnd)
        val after = pack(case.windows, relaid, anchor, case.height)
        val shown = after.pages[pageIndexFor(after.pages, anchor)]
        assertEquals(after.anchorPage, shown)
        assertTrue(anchor >= shown.start && anchor < shown.end, "anchor $anchor not on $shown")
    }

    /**
     * Windows of [counts] lines each, ten characters and [lineHeight] px per line; window w's pixels start
     * at 1000 × w so a seam is never a plain continuation of coordinates.
     */
    private fun windowed(vararg counts: Int, lineHeight: Float = 10f): Pair<List<Window>, List<List<LineMetrics>>> {
        var offset = 0
        val windows = mutableListOf<Window>()
        val lines = counts.mapIndexed { w, n ->
            val start = offset
            val own = List(n) { i ->
                val top = 1000f * w + i * lineHeight
                LineMetrics(offset + i * 10, top, top + lineHeight, endsAtBreak = true, heading = false)
            }
            offset += n * 10
            windows += Window(w, w, start, offset)
            own
        }
        return windows to lines
    }

    private fun result(before: List<Page>, fromAnchor: List<Page>, needBefore: Int? = null, needAfter: Int? = null) =
        PackedPages(before, fromAnchor, needBefore, needAfter)

    @Test
    fun `an empty chapter packs to nothing, with nothing to come`() {
        assertEquals(result(emptyList(), emptyList()), pack(emptyList(), emptyList(), 0, 100f))
    }

    @Test
    fun `a page spanning a seam adds the heights of both parts and gets a band per window`() {
        val (windows, lines) = windowed(8, 8)
        assertEquals(
            result(emptyList(), listOf(
                Page(0, 100, listOf(Band(0, 0f, 80f), Band(1, 1000f, 1020f))),
                Page(100, 160, listOf(Band(1, 1020f, 1080f))),
            )),
            pack(windows, lines, 0, 100f),
        )
    }

    @Test
    fun `backward pages fill upward from the anchor, leaving only the first page short`() {
        val (windows, lines) = windowed(8, 8)
        assertEquals(
            result(
                listOf(
                    Page(0, 20, listOf(Band(0, 0f, 20f))),
                    Page(20, 120, listOf(Band(0, 20f, 80f), Band(1, 1000f, 1040f))),
                ),
                listOf(Page(120, 160, listOf(Band(1, 1040f, 1080f)))),
            ),
            pack(windows, lines, 120, 100f),
        )
    }

    @Test
    fun `an anchor at or past the chapter's end packs everything backward, so the last page is full`() {
        val (windows, lines) = windowed(8, 8)
        val packed = pack(windows, lines, 160, 100f)
        assertEquals(
            result(listOf(Page(0, 60, listOf(Band(0, 0f, 60f))), Page(60, 160, listOf(Band(0, 60f, 80f), Band(1, 1000f, 1080f)))), emptyList()),
            packed,
        )
        assertNull(packed.anchorPage)
        assertEquals(packed, pack(windows, lines, 999, 100f))
    }

    @Test
    fun `an anchor at 0 has no pages before it, and a negative anchor is treated as 0`() {
        val (windows, lines) = windowed(3, 3, 3)
        val packed = pack(windows, lines, 0, 100f)
        assertEquals(emptyList(), packed.before)
        assertEquals(listOf(Page(0, 90, listOf(Band(0, 0f, 30f), Band(1, 1000f, 1030f), Band(2, 2000f, 2030f)))), packed.fromAnchor)
        assertEquals(packed, pack(windows, lines, -5, 100f))
    }

    @Test
    fun `an unmeasured neighbour stops packing before any page that might need its lines`() {
        val (windows, lines) = windowed(15, 15, 15)
        assertEquals(result(emptyList(), listOf(Page(0, 100, listOf(Band(0, 0f, 100f)))), needAfter = 1), pack(windows, listOf(lines[0], null, null), 0, 100f))
        assertEquals(
            result(listOf(Page(350, 450, listOf(Band(2, 2050f, 2150f)))), emptyList(), needBefore = 1),
            pack(windows, listOf(null, null, lines[2]), 450, 100f),
        )
        val (short, shortLines) = windowed(15, 15, 8)
        assertEquals(result(emptyList(), emptyList(), needBefore = 1), pack(short, listOf(null, null, shortLines[2]), 380, 100f))
    }

    @Test
    fun `an unmeasured anchor window is what both sides need, except that nothing precedes anchor 0`() {
        val (windows, lines) = windowed(15, 15, 15)
        assertEquals(result(emptyList(), emptyList(), needBefore = 1, needAfter = 1), pack(windows, listOf(lines[0], null, lines[2]), 200, 100f))
        assertEquals(result(emptyList(), emptyList(), needBefore = 1, needAfter = 1), pack(windows, listOf(null, null, null), 150, 100f))
        assertEquals(result(emptyList(), emptyList(), needBefore = null, needAfter = 0), pack(windows, listOf(null, lines[1], lines[2]), 0, 100f))
        assertEquals(result(emptyList(), emptyList(), needBefore = 0, needAfter = 0), pack(windows, listOf(null, lines[1], lines[2]), 10, 100f))
    }

    @Test
    fun `the anchor page is withheld while the next window might still add lines to it`() {
        val (windows, lines) = windowed(8, 8)
        val waiting = pack(windows, listOf(lines[0], null), 50, 100f)
        assertNull(waiting.anchorPage)
        assertEquals(emptyList(), waiting.fromAnchor)
        assertEquals(1, waiting.needAfter)
        assertEquals(listOf(Page(0, 50, listOf(Band(0, 0f, 50f)))), waiting.before)
        val shown = pack(windows, lines, 50, 100f)
        assertEquals(Page(50, 150, listOf(Band(0, 50f, 80f), Band(1, 1000f, 1070f))), shown.anchorPage)
        assertNull(shown.needAfter)
    }

    @Test
    fun `a line list per window is required`() {
        val (windows, lines) = windowed(8, 8)
        assertFailsWith<IllegalArgumentException> { pack(windows, lines.take(1), 0, 100f) }
    }

    @Test
    fun `an anchor inside the last window packs it alone until earlier windows arrive`() {
        val (windows, lines) = windowed(15, 15, 15)
        val alone = pack(windows, listOf(null, null, lines[2]), 380, 100f)
        assertEquals(1, alone.needBefore)
        assertNull(alone.needAfter)
        assertEquals(listOf(380 to 450), textRanges(alone.fromAnchor))
        assertEquals(emptyList(), alone.before) // lines 300..370 fit a page, but the window above might bring more
        val withNeighbour = pack(windows, listOf(null, lines[1], lines[2]), 380, 100f)
        assertEquals(0, withNeighbour.needBefore)
        assertEquals(listOf(180 to 280, 280 to 380), textRanges(withNeighbour.before))
        assertEquals(alone.fromAnchor, withNeighbour.fromAnchor)
        val all = pack(windows, lines, 380, 100f)
        assertEquals(listOf(0 to 80, 80 to 180, 180 to 280, 280 to 380), textRanges(all.before))
        assertEquals(withNeighbour.before, all.before.drop(2))
    }

    @Test
    fun `a line taller than the page gets a page to itself in both directions`() {
        val (windows, lines) = windowed(2, 2, lineHeight = 150f)
        val pages = listOf(
            Page(0, 10, listOf(Band(0, 0f, 150f))),
            Page(10, 20, listOf(Band(0, 150f, 300f))),
            Page(20, 30, listOf(Band(1, 1000f, 1150f))),
            Page(30, 40, listOf(Band(1, 1150f, 1300f))),
        )
        assertEquals(result(pages.take(2), pages.drop(2)), pack(windows, lines, 20, 100f))
        assertEquals(result(emptyList(), pages), pack(windows, lines, 0, 100f))
    }
}
