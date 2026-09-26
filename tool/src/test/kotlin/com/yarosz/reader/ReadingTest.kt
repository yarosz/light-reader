package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The reading session over fake chapters: opening at a Place, turning pages within and across chapters,
 * font changes, the cache rule, the backward-crossing decision, and what gets measured when.
 */
class ReadingTest {

    private val key = LayoutKey(fontStep = 1, widthPx = 1_000, pageHeightPx = 300)

    /** [count] fake chapters measured by [FakeChapter]'s simulated layout, one window at a time, counting each measure the session asks for. */
    private class Fixture(seed: Int, count: Int, windowChars: Int) {
        val fakes = Random(seed).let { rnd -> List(count) { FakeChapter.random(rnd) } }
        val chapters = fakes.map { it.chapter }
        var measures = 0
        private val cut = HashMap<Pair<Int, LayoutKey>, List<List<LineMetrics>>>()
        val reading = Reading<List<LineMetrics>>(chapters, measure = { pass, window -> measures++; linesOf(pass)[window] }, linesOf = { it }, windowChars = windowChars)

        fun linesOf(pass: Pass<List<LineMetrics>>) = cut.getOrPut(pass.chapterIndex to pass.key) {
            val rnd = Random(pass.chapterIndex * 31 + pass.key.fontStep)
            fakes[pass.chapterIndex].cut(fakes[pass.chapterIndex].layout(FONT_SIZES[pass.key.fontStep], rnd), pass.windows, rnd)
        }
    }

    private fun windowOf(shown: Shown<*>) = windowIndexFor(shown.pass.windows, shown.page.start)

    @Test
    fun `opening shows the Page holding the Place after measuring a contiguous run from its window, at most one window past the Page`() {
        repeat(300) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val rnd = Random(seed)
            val length = book.chapters[0].text.length
            val offset = if (rnd.nextBoolean()) rnd.nextInt(0, length + 1) else length
            val shown = book.reading.open(0, offset, key)
            val page = shown.page
            if (offset < length) assertTrue(offset >= page.start && offset < page.end, "seed $seed: $offset not on $page")
            else assertEquals(length, page.end, "seed $seed: past the end shows the last Page")
            val measured = shown.pass.windows.indices.filter { shown.pass.measured(it) != null }
            assertEquals(book.measures, measured.size)
            assertEquals((measured.first()..measured.last()).toList(), measured, "seed $seed: measured run has a gap")
            assertTrue(windowIndexFor(shown.pass.windows, offset) in measured.first()..measured.last())
            assertTrue(book.measures <= page.bands.size + 1, "seed $seed: ${book.measures} measures for a ${page.bands.size}-band Page")
        }
    }

    @Test
    fun `next and previous walk every Page of the book, and the way back shows the identical Pages without measuring`() {
        repeat(30) { seed ->
            val book = Fixture(seed, count = 3, windowChars = 3_000)
            val forward = generateSequence(book.reading.open(0, 0, key)) { book.reading.next() }.toList()
            forward.zipWithNext { a, b ->
                if (a.pass === b.pass) assertEquals(a.page.end, b.page.start)
                else {
                    assertEquals(a.pass.chapterIndex + 1, b.pass.chapterIndex)
                    assertEquals(a.pass.length, a.page.end)
                    assertEquals(0, b.page.start)
                }
            }
            assertEquals(0, forward.first().page.start)
            assertEquals(book.chapters.last().text.length, forward.last().page.end)
            assertEquals(book.chapters.indices.toList(), forward.map { it.pass.chapterIndex }.distinct())

            val measuresBefore = book.measures
            val backward = generateSequence(book.reading.previous()) { book.reading.previous() }.toList()
            assertEquals(forward.dropLast(1).asReversed(), backward, "seed $seed")
            assertEquals(measuresBefore, book.measures, "seed $seed: turning back measured a window")
            assertNull(book.reading.previous())
        }
    }

    @Test
    fun `turning back into a chapter never read lands on its last Page, packed backward from the end`() {
        val book = Fixture(seed = 7, count = 2, windowChars = 3_000)
        book.reading.open(1, 0, key)
        val shown = book.reading.previous()!!
        assertEquals(0, shown.pass.chapterIndex)
        assertEquals(book.chapters[0].text.length, shown.page.end)
        assertEquals(shown.page, shown.pass.pages.last())
        assertTrue(shown.page.start < shown.page.end)
        book.reading.next()!!
        assertEquals(shown, book.reading.previous())
    }

    @Test
    fun `a font change is a new pass holding the Place, and drops the cached passes so turning back packs afresh`() {
        val book = Fixture(seed = 3, count = 2, windowChars = 3_000)
        var shown = book.reading.open(0, 0, key)
        while (shown.pass.chapterIndex == 0) shown = book.reading.next()!!
        val firstPass = shown.pass
        val place = shown.page.start
        val bigger = key.copy(fontStep = 3)
        val relaid = book.reading.open(1, place, bigger)
        assertNotSame(firstPass, relaid.pass)
        assertEquals(bigger, relaid.pass.key)
        assertTrue(place >= relaid.page.start && place < relaid.page.end)

        val measures = book.measures
        val back = book.reading.previous()!!
        assertEquals(0, back.pass.chapterIndex)
        assertEquals(bigger, back.pass.key)
        assertEquals(book.chapters[0].text.length, back.page.end)
        assertTrue(book.measures > measures, "a fresh pass measures")
    }

    @Test
    fun `background targets cover two windows past the Page first, then two before, and follow the reader`() {
        val book = (0..200).asSequence().map { Fixture(it, count = 1, windowChars = 1_500) }.first { windows(it.chapters[0], 1_500).size >= 8 }
        val shown = book.reading.open(0, book.chapters[0].text.length / 2, key)
        val pass = shown.pass
        val at = windowOf(shown)
        val targets = generateSequence {
            book.reading.prefetchTarget()?.let { (target, window) -> target.record(window, book.linesOf(target)[window]); window }
        }.toList()
        assertTrue(targets.all { it in at - 2..at + 2 }, "targets $targets beyond ±2 of $at")
        val later = targets.filter { it > at }
        val earlier = targets.filter { it < at }
        assertEquals(later + earlier, targets, "later side first")
        assertEquals(later.sorted(), later)
        assertEquals(earlier.sortedDescending(), earlier)
        (at - 2..at + 2).filter { it in pass.windows.indices }.forEach { assertTrue(pass.measured(it) != null, "window $it left unmeasured") }
        assertNull(book.reading.prefetchTarget())

        var turned = book.reading.next()!!
        while (windowOf(turned) < at + 2) turned = book.reading.next()!!
        val moved = windowOf(turned)
        val target = book.reading.prefetchTarget()?.second
        assertTrue(target != null && target > at + 2 && target <= moved + 2, "target $target after moving from window $at to $moved")
    }

    @Test
    fun `a Page turn measures synchronously when the background has not reached the next window`() {
        val book = Fixture(seed = 5, count = 1, windowChars = 1_500)
        var shown = book.reading.open(0, 0, key)
        val afterOpen = book.measures
        while (windowOf(shown) == 0) shown = book.reading.next()!!
        assertEquals(1, windowOf(shown))
        assertTrue(book.measures > afterOpen, "the turn into window 1 had to measure")
    }

    @Test
    fun `backwardLanding reuses a cached pass only at the same key and only once it reached the chapter's end`() {
        val chapter = Chapter("", listOf(Block(BlockKind.Paragraph, "x".repeat(500)), Block(BlockKind.Paragraph, "x".repeat(50))))
        val length = chapter.text.length
        fun pass(key: LayoutKey, windowChars: Int) = Pass<List<LineMetrics>>(0, 0, chapter, key, windows(chapter, windowChars), 0) { it }
        fun tenCharLines(window: Window) = List((window.end - window.start) / 10) { i ->
            LineMetrics(window.start + i * 10, i * 10f, i * 10f + 10f, endsAtBreak = true, heading = false)
        }
        val whole = pass(key, windowChars = 1_000).also { p -> p.windows.forEachIndexed { w, window -> p.record(w, tenCharLines(window)) } }
        assertEquals(length, whole.pages.last().end)
        assertEquals(Landing.Cached(whole.pages.last()), backwardLanding(whole, key, length))
        assertEquals(Landing.Fresh(length), backwardLanding(whole, key.copy(fontStep = 2), length))
        assertEquals(Landing.Fresh(length), backwardLanding(null, key, length))
        val partial = pass(key, windowChars = 510).also { p -> p.record(0, tenCharLines(p.windows[0])) }
        assertTrue(partial.pages.isNotEmpty() && partial.pages.last().end < length)
        assertIs<Landing.Fresh>(backwardLanding(partial, key, length))
    }

    @Test
    fun `record keeps a window's first layout, so a late background measure can't change packed Pages`() {
        val chapter = Chapter("", listOf(Block(BlockKind.Paragraph, "x".repeat(500))))
        val pass = Pass<List<LineMetrics>>(0, 0, chapter, key, windows(chapter, 1_000), 0) { it }
        fun lines(chars: Int) = List(500 / chars) { i -> LineMetrics(i * chars, i * 10f, i * 10f + 10f, endsAtBreak = true, heading = false) }
        val first = lines(10)
        pass.record(0, first)
        val pages = pass.pages
        pass.record(0, lines(50))
        assertSame(first, pass.measured(0))
        assertEquals(pages, pass.pages)
    }
}
