package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The minutes line, the reading speed and its samples, and the stored progress. */
class ProgressTest {

    private fun words(count: Int) = List(count) { "w$it" }.joinToString(" ")

    private fun item(id: String, vararg blocks: String) = SpineItem(id, blocks.map { Block(BlockKind.Paragraph, it) })

    @Test
    fun `the copy rounds raw minutes up, to whole minutes under 15 and to 5-minute steps from 15`() {
        val expected = mapOf(
            0.0 to MINUTES_ALMOST_DONE,
            0.99 to MINUTES_ALMOST_DONE,
            1.0 to "about 1 min left in this chapter",
            1.01 to "about 2 min left in this chapter",
            14.01 to "about 15 min left in this chapter",
            14.99 to "about 15 min left in this chapter",
            15.0 to "about 15 min left in this chapter",
            15.01 to "about 20 min left in this chapter",
            20.0 to "about 20 min left in this chapter",
            61.0 to "about 65 min left in this chapter",
        )
        assertEquals(expected, expected.mapValues { (minutes, _) -> minutesLeftCopy(minutes) })
    }

    @Test
    fun `words are whitespace-separated runs, counted where they start, across Spine items`() {
        val index = WordIndex(listOf(item("a", "one two  three", " four"), item("b", ""), item("c", "five\tsix")))
        assertEquals(4, index.between(SpinePoint(0, 0), SpinePoint(1, 0)))
        assertEquals(6, index.between(SpinePoint(0, 0), SpinePoint(2, 8)))
        assertEquals(3, index.between(SpinePoint(0, 1), SpinePoint(1, 0)))
        assertEquals(2, index.between(SpinePoint(0, 4), SpinePoint(0, 14)))
        assertEquals(1, index.between(SpinePoint(2, 0), SpinePoint(2, 5)))
        assertEquals(0, index.between(SpinePoint(2, 5), SpinePoint(2, 0)))
    }

    @Test
    fun `progress is the share of characters before the point through the whole Book, to 4 decimals`() {
        val book = OpenBook("id", "", listOf(item("a", "x".repeat(300)), item("b", "x".repeat(700))))
        assertEquals(0.0, book.progressAt(SpinePoint(0, 0)))
        assertEquals(0.3, book.progressAt(SpinePoint(1, 0)))
        assertEquals(0.3333, OpenBook("id", "", listOf(item("a", "x"), item("b", "xx"))).progressAt(SpinePoint(1, 0)))
        assertEquals(1.0, book.progressAt(book.textEnd))
        assertEquals(0.65, book.placeAt(SpinePoint(1, 350), 7).progress)
        assertEquals(book.spineItems[1].placeOf(350, 7), book.placeAt(SpinePoint(1, 350), 7).copy(progress = null))
    }

    @Test
    fun `the minutes line counts the words from the Page to its Chapter's end, which is the next Chapter or the text's end`() {
        val spineItems = listOf(item("front", words(100)), item("one", words(460), words(230)), item("two", words(690)))
        val chapters = listOf(Chapter("One", SpinePoint(1, 0)), Chapter("Two", SpinePoint(1, words(460).length + 1)))
        val book = OpenBook("id", "", spineItems, chapters = chapters)
        val index = WordIndex(spineItems)
        assertNull(book.minutesLine(index, SpinePoint(0, 0), PRIOR_WPM), "front matter")
        assertEquals("about 2 min left in this chapter", book.minutesLine(index, SpinePoint(1, 0), PRIOR_WPM))
        assertEquals("about 4 min left in this chapter", book.minutesLine(index, SpinePoint(1, words(460).length + 1), PRIOR_WPM))
        assertEquals("about 3 min left in this chapter", book.minutesLine(index, SpinePoint(2, 0), PRIOR_WPM))
        assertEquals("about 1 min left in this chapter", book.minutesLine(index, SpinePoint(1, 0), 460.0))
        assertNull(book.minutesLine(index, book.textEnd, PRIOR_WPM), "from the text's end on")
        val endsEarly = book.copy(textEnd = SpinePoint(2, words(230).length))
        assertEquals("about 2 min left in this chapter", endsEarly.minutesLine(index, SpinePoint(1, words(460).length + 1), PRIOR_WPM))
    }

    @Test
    fun `a Chapter whose whole text reads in under a minute gets no line, even on its first Page`() {
        val spineItems = listOf(item("one", words(229)), item("two", words(230)))
        val book = OpenBook("id", "", spineItems)
        val index = WordIndex(spineItems)
        assertNull(book.minutesLine(index, SpinePoint(0, 0), PRIOR_WPM))
        assertEquals(MINUTES_ALMOST_DONE, book.minutesLine(index, SpinePoint(1, 4), PRIOR_WPM))
        assertEquals("about 1 min left in this chapter", book.minutesLine(index, SpinePoint(1, 0), PRIOR_WPM))
        assertNull(book.minutesLine(index, SpinePoint(1, 0), 231.0))
    }

    @Test
    fun `a sample needs 20 words and 2 s to 3 min on the Page`() {
        assertEquals(300.0, sampleWpm(20, 4_000))
        assertNull(sampleWpm(19, 4_000))
        assertEquals(600.0, sampleWpm(20, 2_000))
        assertNull(sampleWpm(20, 1_999))
        assertEquals(20 * 60_000.0 / 180_000, sampleWpm(20, 180_000))
        assertNull(sampleWpm(20, 180_001))
    }

    @Test
    fun `the speed is the prior until 5 samples, then the median of the newest 20`() {
        val speed = ReadingSpeed()
        repeat(4) { speed.record(100, 20_000) }
        assertEquals(PRIOR_WPM, speed.wpm)
        speed.record(100, 20_000)
        assertEquals(300.0, speed.wpm)
        speed.record(100, 10_000)
        assertEquals(300.0, speed.wpm)
        repeat(5) { speed.record(100, 10_000) }
        assertEquals(600.0, speed.wpm)
        val window = ReadingSpeed()
        repeat(20) { window.record(100, 60_000) }
        repeat(10) { window.record(100, 30_000) }
        assertEquals(150.0, window.wpm)
        window.record(100, 30_000)
        assertEquals(200.0, window.wpm)
        window.record(20, 1_000)
        assertEquals(200.0, window.wpm)
    }

    @Test
    fun `only a Page reached and left by forward turns gives a sample, and not one under 20 words`() {
        val speed = ReadingSpeed()
        val timer = PageTimer(speed)
        timer.finish(10_000)
        timer.start(0, 100)
        timer.discard()
        timer.finish(20_000)
        timer.start(0, 19)
        timer.finish(20_000)
        repeat(MEASURED_AFTER - 1) {
            timer.start(0, 100)
            timer.finish(20_000)
        }
        assertEquals(PRIOR_WPM, speed.wpm, "four samples so far: the back turn and the short Page gave none")
        timer.start(0, 100)
        timer.finish(20_000)
        assertEquals(300.0, speed.wpm)
    }
}
