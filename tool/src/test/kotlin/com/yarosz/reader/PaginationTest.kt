package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Examples of the page-break rules (DESIGN.md) on a whole Spine item, packed from its start and packed
 * backward from an anchor. [PackTest] holds the property tests.
 */
class PaginationTest {

    /**
     * Ten [height] px lines (10 by default) fit a page of ten times that; lines past the tenth continue the
     * Spine item, so the first page is never its last.
     */
    private fun lines(count: Int = 15, midWord: Set<Int> = emptySet(), headings: Set<Int> = emptySet(), height: Float = 10f) =
        List(count) { LineMetrics(it * 10, it * height, it * height + height, endsAtBreak = it !in midWord, heading = it in headings) }

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

    /** Packed from [anchor]: the line range of the Page above the anchor's Page, and the anchor's Page's first line. */
    private fun around(lines: List<LineMetrics>, anchor: Int, pageHeight: Float = 100f, floor: Int = 0): Pair<IntRange?, Int> {
        val packed = pack(listOf(wholeWindow(150)), listOf(lines), anchor, pageHeight, floor = floor)
        return lineRanges(lines, packed.before).lastOrNull() to lineRanges(lines, packed.fromAnchor).first().first
    }

    @Test
    fun `a Place right after a soft-hyphen break starts its Page on the line with the word's first half`() {
        assertEquals(1..10 to 11, around(lines(midWord = setOf(11)), 120))
    }

    @Test
    fun `a Place inside the second half of a hyphenated word starts its Page on the word's first half`() {
        assertEquals(1..10 to 11, around(lines(midWord = setOf(11)), 123))
    }

    @Test
    fun `a Place under a cascade starts its Page above the whole cascade while that is within 30 percent of a Page`() {
        assertEquals(0..9 to 10, around(lines(midWord = setOf(10, 11)), 125))
    }

    @Test
    fun `a Place under a cascade reaching further than 30 percent of a Page keeps its own line`() {
        assertEquals(2..11 to 12, around(lines(midWord = setOf(8, 9, 10, 11)), 120))
    }

    /** 12 px lines on a 120 px Page, so 30% of the Page is exactly three lines in float arithmetic. */
    @Test
    fun `a Place under a cascade reaching exactly 30 percent of a Page still starts its Page above it`() {
        assertEquals(0..8 to 9, around(lines(midWord = setOf(9, 10, 11), height = 12f), 120, pageHeight = 120f))
    }

    /** Line 12 is [height] px tall rather than 10, pushing every line below it down. */
    private fun linesWithTallLine12(height: Float, midWord: Set<Int>) = lines(midWord = midWord).map {
        if (it.start < 120) it
        else if (it.start == 120) it.copy(bottom = it.top + height)
        else it.copy(top = it.top + height - 10f, bottom = it.bottom + height - 10f)
    }

    @Test
    fun `a Place on a line too tall to share a Page with the cascade above it keeps its own line`() {
        assertEquals(2..11 to 12, around(linesWithTallLine12(75f, midWord = setOf(9, 10, 11)), 120))
    }

    @Test
    fun `a Place under a heading and a hyphenated line starts its Page on the heading, keeping it with its text`() {
        assertEquals(0..9 to 10, around(lines(midWord = setOf(11), headings = setOf(10)), 120))
    }

    /** A Chapter starting on the tail's own line (line 12), at its start or inside it: the Page stays on that line. */
    @Test
    fun `a Place on the line holding its Chapter's start keeps that line though it starts mid-word`() {
        assertEquals(2..11 to 12, around(lines(midWord = setOf(11)), 120, floor = 120))
        assertEquals(2..11 to 12, around(lines(midWord = setOf(11)), 123, floor = 121))
    }

    @Test
    fun `a Place under a cascade reaches up to the line holding its Chapter's start and no further`() {
        assertEquals(0..9 to 10, around(lines(midWord = setOf(10, 11)), 125, floor = 100))
        assertEquals(0..9 to 10, around(lines(midWord = setOf(10, 11)), 125, floor = 105))
        assertEquals(1..10 to 11, around(lines(midWord = setOf(10, 11)), 125, floor = 110))
    }

    /** A Chapter starting mid-line on line 11, which ends "hor-": a Place on the tail line below still gets the whole word. */
    @Test
    fun `a Place under the line holding its Chapter's start mid-line starts its Page on that line`() {
        assertEquals(1..10 to 11, around(lines(midWord = setOf(11)), 123, floor = 113))
    }

    @Test
    fun `a Place under a heading its Chapter starts at still starts its Page on the heading`() {
        assertEquals(0..9 to 10, around(lines(midWord = setOf(11), headings = setOf(10)), 120, floor = 100))
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
        val spineItem = SpineItem("spine", listOf(Block(BlockKind.Heading, "Title"), Block(BlockKind.Paragraph, "Body")))
        assertEquals(BlockKind.Heading, spineItem.kindAt(0))
        assertEquals(BlockKind.Heading, spineItem.kindAt(4))
        assertEquals(BlockKind.Heading, spineItem.kindAt(5))
        assertEquals(BlockKind.Paragraph, spineItem.kindAt(6))
        assertEquals(BlockKind.Paragraph, spineItem.kindAt(9))
    }
}
