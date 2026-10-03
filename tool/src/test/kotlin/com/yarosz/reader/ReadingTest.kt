package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Reading over fake Spine items: opening at a Place, turning pages within and across Spine items,
 * font changes, the cache rule, the backward-crossing decision, and what gets measured when.
 */
class ReadingTest {

    private val key = LayoutKey(fontStep = DEFAULT_FONT_STEP, widthPx = 1_000, pageHeightPx = 300)

    /** [count] fake Spine items measured by [FakeSpineItem]'s simulated layout, one window at a time, counting each measure the session asks for. */
    private class Fixture(seed: Int, count: Int, windowChars: Int, textEnd: ((List<SpineItem>) -> SpinePoint)? = null) {
        val fakes = Random(seed).let { rnd -> List(count) { FakeSpineItem.random(rnd) } }
        val spineItems = fakes.map { it.spineItem }
        var measures = 0
        private val cut = HashMap<Pair<Int, LayoutKey>, List<List<LineMetrics>>>()
        val end = textEnd?.invoke(spineItems)
        val reading = Reading<List<LineMetrics>>(spineItems, measure = { pass, window -> measures++; linesOf(pass)[window] }, linesOf = { it }, windowChars = windowChars, textEnd = end)

        fun linesOf(pass: Pass<List<LineMetrics>>) = cut.getOrPut(pass.item to pass.key) {
            val rnd = Random(pass.item * 31 + pass.key.fontStep)
            fakes[pass.item].cut(fakes[pass.item].layout(FONT_SIZES[pass.key.fontStep], rnd), pass.windows, rnd)
        }
    }

    private fun windowOf(shown: Shown<*>) = windowIndexFor(shown.pass.windows, shown.page.start)

    /**
     * A Reading of [items], each laid out by its [FakeSpineItem]'s simulated layout (by Spine item, so a pass laid out
     * before a [Reading.rebase] measures alike after it), counting measures in [measures].
     */
    private class Laid(val fakes: List<FakeSpineItem>, items: List<SpineItem> = fakes.map { it.spineItem }, textEnd: SpinePoint? = null, chapterStarts: List<SpinePoint> = emptyList()) {
        var measures = 0
        val reading = Reading<List<LineMetrics>>(items, measure = { pass, window -> measures++; linesOf(pass)[window] }, linesOf = { it }, windowChars = 3_000,
            textEnd = textEnd, chapterStarts = chapterStarts)

        private fun linesOf(pass: Pass<List<LineMetrics>>): List<List<LineMetrics>> {
            val fake = fakes.first { it.spineItem === pass.spineItem }
            val rnd = Random(fakes.indexOf(fake) * 31 + pass.key.fontStep)
            return fake.cut(fake.layout(FONT_SIZES[pass.key.fontStep], rnd), pass.windows, rnd)
        }
    }

    @Test
    fun `a rebase onto the whole Book moves the pass of the one Spine item to its index, the Page on screen standing, and turns go on across Spine items`() {
        repeat(100) { seed ->
            val rnd = Random(seed)
            val fakes = List(3) { FakeSpineItem.random(rnd) }
            val whole = fakes.map { it.spineItem }
            val offset = rnd.nextInt(0, whole[1].text.length)
            val placed = Laid(fakes, items = listOf(whole[1]))
            val shown = placed.reading.open(0, offset, key)
            assertTrue(placed.reading.rebase(whole, null, emptyList()) { if (it == 0) 1 else null }, "seed $seed")
            assertEquals(1, shown.pass.item)
            val measures = placed.measures
            assertEquals(Laid(fakes).reading.open(1, offset, key).page, shown.page, "seed $seed: the whole Book packs another Page")
            val forward = generateSequence(shown) { placed.reading.next() }.last()
            assertEquals(2, forward.pass.item)
            assertEquals(whole[2].text.length, forward.page.end)
            assertTrue(placed.measures > measures)
        }
    }

    @Test
    fun `a rebase drops a pass the whole Book would lay out otherwise, or that names a Spine item it drops, and says the shown one went`() {
        val fake = FakeSpineItem(SpineItem("c1", List(3) { Block(BlockKind.Paragraph, "x".repeat(2_000)) }))
        val other = FakeSpineItem(SpineItem("c0", listOf(Block(BlockKind.Paragraph, "y".repeat(500)))))
        val whole = listOf(other.spineItem, fake.spineItem)
        val chapter = SpinePoint(1, fake.spineItem.blockStarts[1])
        fun placed() = Laid(listOf(other, fake), items = listOf(fake.spineItem)).also { it.reading.open(0, 3_000, key) }
        assertTrue(placed().reading.rebase(whole, SpinePoint(1, fake.length), emptyList()) { 1 }, "a text end at the Spine item's end breaks no Page")
        assertTrue(placed().reading.rebase(whole, null, listOf(SpinePoint(0, 0))) { 1 }, "a Chapter in another Spine item sets no floor")
        assertFalse(placed().reading.rebase(whole, null, listOf(chapter)) { 1 }, "a Chapter starting above the Place sets a floor")
        assertFalse(placed().reading.rebase(whole, SpinePoint(1, fake.spineItem.blockStarts[2]), emptyList()) { 1 }, "a text end inside it breaks a Page")
        assertFalse(placed().reading.rebase(whole, null, emptyList()) { null }, "the whole Book drops it")
    }

    @Test
    fun `opening shows the Page holding the Place after measuring a contiguous run from its window, at most one window past the Page`() {
        repeat(300) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val rnd = Random(seed)
            val length = book.spineItems[0].text.length
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
                    assertEquals(a.pass.item + 1, b.pass.item)
                    assertEquals(a.pass.length, a.page.end)
                    assertEquals(0, b.page.start)
                }
            }
            assertEquals(0, forward.first().page.start)
            assertEquals(book.spineItems.last().text.length, forward.last().page.end)
            assertEquals(book.spineItems.indices.toList(), forward.map { it.pass.item }.distinct())

            val measuresBefore = book.measures
            val backward = generateSequence(book.reading.previous()) { book.reading.previous() }.toList()
            assertEquals(forward.dropLast(1).asReversed(), backward, "seed $seed")
            assertEquals(measuresBefore, book.measures, "seed $seed: turning back measured a window")
            assertNull(book.reading.previous())
        }
    }

    @Test
    fun `turning back into a Spine item never read lands on its last Page, packed backward from the end`() {
        val book = Fixture(seed = 7, count = 2, windowChars = 3_000)
        book.reading.open(1, 0, key)
        val shown = book.reading.previous()!!
        assertEquals(0, shown.pass.item)
        assertEquals(book.spineItems[0].text.length, shown.page.end)
        assertEquals(shown.page, shown.pass.pages.last())
        assertTrue(shown.page.start < shown.page.end)
        book.reading.next()!!
        assertEquals(shown, book.reading.previous())
    }

    @Test
    fun `a font change is a new pass holding the Place, and drops the cached passes so turning back packs afresh`() {
        val book = Fixture(seed = 3, count = 2, windowChars = 3_000)
        var shown = book.reading.open(0, 0, key)
        while (shown.pass.item == 0) shown = book.reading.next()!!
        val firstPass = shown.pass
        val place = shown.page.start
        val bigger = key.copy(fontStep = key.fontStep + 2)
        val relaid = book.reading.open(1, place, bigger)
        assertNotSame(firstPass, relaid.pass)
        assertEquals(bigger, relaid.pass.key)
        assertTrue(place >= relaid.page.start && place < relaid.page.end)

        val measures = book.measures
        val back = book.reading.previous()!!
        assertEquals(0, back.pass.item)
        assertEquals(bigger, back.pass.key)
        assertEquals(book.spineItems[0].text.length, back.page.end)
        assertTrue(book.measures > measures, "a fresh pass measures")
    }

    /**
     * A+ up to the largest size and A− down to the smallest, at a Place the font changes never move: every
     * Page shown holds the Place and starts on a word, except on the Place's own line when the word's first
     * half lies more than 30% of a Page above it (a long cascade of hyphenated lines).
     */
    @Test
    fun `font changes at a Place show a Page holding it that starts on a word whenever the guard allows`() {
        repeat(200) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val place = Random(seed).nextInt(0, book.spineItems[0].text.length)
            for (step in (DEFAULT_FONT_STEP..FONT_SIZES.lastIndex) + (FONT_SIZES.lastIndex - 1 downTo 0)) {
                val (pass, page) = book.reading.open(0, place, key.copy(fontStep = step))
                assertTrue(place >= page.start && place < page.end, "seed $seed step $step: $place not on $page")
                val lines = book.linesOf(pass)[page.bands.first().window]
                val first = lines.indexOfFirst { it.start == page.start }
                if (first == 0 || lines[first - 1].legal()) continue
                var wordStart = first
                while (wordStart > 0 && !lines[wordStart - 1].legal()) wordStart--
                assertEquals(lines.indexContaining(place) { it.start }, first, "seed $seed step $step: $page starts mid-word above the Place's line")
                val reach = (1 - MIN_PAGE_FILL) * key.pageHeightPx
                assertTrue(
                    lines[first].top - lines[wordStart].top > reach || lines[first].bottom - lines[wordStart].top > key.pageHeightPx,
                    "seed $seed step $step: $page starts mid-word",
                )
            }
        }
    }

    @Test
    fun `background targets cover two windows past the Page first, then two before, and follow the reader`() {
        val book = (0..200).asSequence().map { Fixture(it, count = 1, windowChars = 1_500) }.first { windows(it.spineItems[0], 1_500).size >= 8 }
        val shown = book.reading.open(0, book.spineItems[0].text.length / 2, key)
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
    fun `backwardLanding reuses a cached pass only at the same key and only once it reached the Spine item's end`() {
        val spineItem = SpineItem("spine", listOf(Block(BlockKind.Paragraph, "x".repeat(500)), Block(BlockKind.Paragraph, "x".repeat(50))))
        val length = spineItem.text.length
        fun pass(key: LayoutKey, windowChars: Int) = Pass<List<LineMetrics>>(0, 0, spineItem, key, windows(spineItem, windowChars), 0) { it }
        fun tenCharLines(window: Window) = List((window.end - window.start) / 10) { i ->
            LineMetrics(window.start + i * 10, i * 10f, i * 10f + 10f, endsAtBreak = true, heading = false)
        }
        val whole = pass(key, windowChars = 1_000).also { p -> p.windows.forEachIndexed { w, window -> p.record(w, tenCharLines(window)) } }
        assertEquals(length, whole.pages.last().end)
        assertEquals(Landing.Cached(whole.pages.last()), backwardLanding(whole, key, length))
        assertEquals(Landing.Fresh(length), backwardLanding(whole, key.copy(fontStep = key.fontStep + 1), length))
        assertEquals(Landing.Fresh(length), backwardLanding(null, key, length))
        val partial = pass(key, windowChars = 510).also { p -> p.record(0, tenCharLines(p.windows[0])) }
        assertTrue(partial.pages.isNotEmpty() && partial.pages.last().end < length)
        assertIs<Landing.Fresh>(backwardLanding(partial, key, length))
    }

    @Test
    fun `record keeps a window's first layout, so a late background measure can't change packed Pages`() {
        val spineItem = SpineItem("spine", listOf(Block(BlockKind.Paragraph, "x".repeat(500))))
        val pass = Pass<List<LineMetrics>>(0, 0, spineItem, key, windows(spineItem, 1_000), 0) { it }
        fun lines(chars: Int) = List(500 / chars) { i -> LineMetrics(i * chars, i * 10f, i * 10f + 10f, endsAtBreak = true, heading = false) }
        val first = lines(10)
        pass.record(0, first)
        val pages = pass.pages
        pass.record(0, lines(50))
        assertSame(first, pass.measured(0))
        assertEquals(pages, pass.pages)
    }

    @Test
    fun `a jump mid-way through a Spine item shows a Page starting there, where open still shows the cached Page holding it`() {
        var buried = 0
        repeat(100) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val walked = generateSequence(book.reading.open(0, 0, key)) { book.reading.next() }.toList()
            val offset = book.spineItems[0].blockStarts.firstOrNull { start -> start > 0 && walked.none { it.page.start == start } } ?: return@repeat
            buried++
            val held = book.reading.open(0, offset, key)
            assertSame(walked.first().pass, held.pass)
            assertTrue(held.page.start < offset && offset < held.page.end, "seed $seed: open shows the Page holding $offset")
            val jumped = book.reading.jump(0, offset, key)
            assertNotSame(held.pass, jumped.pass)
            assertEquals(offset, jumped.page.start, "seed $seed")
            assertEquals(jumped, book.reading.jump(0, offset, key), "seed $seed: the new pass has that Page now")
        }
        assertTrue(buried > 50, "only $buried seeds buried a block start")
    }

    /**
     * A Chapter anchored mid-paragraph can start on a line that begins mid-word. The jump's Page starts on
     * that line, not on the word's first half above it as a relayout at the Place would: so its first line
     * holds the Chapter's start, and jumping there again reuses the pass.
     */
    @Test
    fun `a jump to a line starting mid-word shows a Page starting on that line`() {
        var tried = 0
        repeat(100) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val lines = book.linesOf(book.reading.open(0, 0, key).pass)
            val reach = (1 - MIN_PAGE_FILL) * key.pageHeightPx
            val tail = lines.flatMap { window ->
                window.indices.filter { i -> i > 0 && !window[i - 1].legal() && window[i].top - window[i - 1].top <= reach }.map { window[it] }
            }.randomOrNull(Random(seed)) ?: return@repeat
            tried++
            val jumped = book.reading.jump(0, tail.start, key)
            assertEquals(tail.start, jumped.page.start, "seed $seed")
            assertTrue(tail.start < jumped.pass.firstLineEnd(jumped.page))
            assertSame(jumped.pass, book.reading.jump(0, tail.start, key).pass, "seed $seed: the new pass has that Page now")
        }
        assertTrue(tried > 50, "only $tried seeds had a line starting mid-word")
    }

    @Test
    fun `a jump to a Spine item's start, or to the end of the one before, shows its first Page`() {
        val book = Fixture(seed = 11, count = 2, windowChars = 3_000)
        book.reading.open(0, book.spineItems[0].text.length / 2, key)
        val first = book.reading.jump(1, 0, key)
        assertEquals(1 to 0, first.pass.item to first.page.start)
        book.reading.open(0, book.spineItems[0].text.length / 2, key)
        assertEquals(first, book.reading.jump(0, book.spineItems[0].text.length, key))
        val start = book.reading.jump(0, 0, key)
        assertEquals(0 to 0, start.pass.item to start.page.start)
    }

    @Test
    fun `a jump to where a cached Page starts reuses it without measuring`() {
        repeat(30) { seed ->
            val book = Fixture(seed, count = 1, windowChars = 3_000)
            val walked = generateSequence(book.reading.open(0, 0, key)) { book.reading.next() }.toList()
            val target = walked[walked.size / 2]
            val measures = book.measures
            val jumped = book.reading.jump(0, target.page.start, key)
            assertSame(target.pass, jumped.pass)
            assertEquals(target.page, jumped.page)
            assertEquals(measures, book.measures, "seed $seed")
        }
    }

    @Test
    fun `a text end inside a Spine item ends a Page and starts the next, walking either way and opening on either side`() {
        repeat(100) { seed ->
            val rnd = Random(seed)
            val book = Fixture(seed, count = 3, windowChars = 3_000) { items ->
                val item = rnd.nextInt(items.size)
                SpinePoint(item, items[item].blockStarts.drop(1).randomOrNull(rnd) ?: items[item].text.length)
            }
            val end = book.end!!
            val forward = generateSequence(book.reading.open(0, 0, key)) { book.reading.next() }.toList()
            val ends = forward.map { SpinePoint(it.pass.item, it.page.end) }
            assertTrue(forward.none { it.pass.item == end.item && end.char in it.page.start + 1 until it.page.end }, "seed $seed: a Page spans $end")
            assertEquals(1, ends.count { it == end }, "seed $seed")
            if (end.char < book.spineItems[end.item].text.length) {
                book.reading.open(end.item, end.char, key.copy(fontStep = key.fontStep + 1))
                val back = book.reading.previous()!!
                assertEquals(end, SpinePoint(back.pass.item, back.page.end), "seed $seed")
            }
        }
    }
}
