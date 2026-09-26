package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Examples of the page-break rules (DESIGN.md) on a whole chapter, packed from its start and packed
 * backward from an anchor. [PackTest] holds the property tests.
 */
class PaginationTest {

    /** Ten 10px lines fit a 100px page; lines past the tenth continue the chapter, so the first page is never its last. */
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

    /** The Pages above an anchor on line 12, so the Page directly above it ends on line 11. */
    private fun pagesBefore(lines: List<LineMetrics>) = lineRanges(lines, pack(listOf(wholeWindow(150)), listOf(lines), 120, 100f).before)

    @Test
    fun `a backward cascade above 70 percent starts the page below the legal line`() {
        assertEquals(listOf(0..4, 5..11), pagesBefore(lines(midWord = setOf(1, 2, 3))))
    }

    @Test
    fun `a backward cascade reaching below 70 percent starts the page greedily mid-word`() {
        assertEquals(listOf(0..1, 2..11), pagesBefore(lines(midWord = setOf(1, 2, 3, 4, 5, 6, 7))))
    }

    @Test
    fun `a heading directly above a would-be start moves the start down, keeping the heading with its text`() {
        assertEquals(listOf(0..2, 3..11), pagesBefore(lines(headings = setOf(1))))
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
