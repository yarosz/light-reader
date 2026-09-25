package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Examples of the page-break rules (DESIGN.md) on a whole chapter packed from its start. [PackTest] holds
 * the property tests.
 */
class PaginationTest {

    /** Ten 10px lines fit a 100px page; lines past [count] keep the chapter going so no page is final. */
    private fun lines(count: Int = 15, midWord: Set<Int> = emptySet(), headings: Set<Int> = emptySet()) =
        List(count) { LineMetrics(it * 10, it * 10f, it * 10f + 10f, endsAtBreak = it !in midWord, heading = it in headings) }

    private fun pagesFromStart(lines: List<LineMetrics>) = pack(listOf(wholeWindow(150)), listOf(lines), 0, 100f).pages

    private fun firstPageLines(lines: List<LineMetrics>) = lineRanges(lines, pagesFromStart(lines)).first()

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
        assertEquals(9, lineRanges(lines, pagesFromStart(lines))[1].first)
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
