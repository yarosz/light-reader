package com.yarosz.reader

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** What Contents lists and which row is current ([contentsAt]), and the top line's tap target ([contentsTargetHeight]). */
class ContentsTest {

    private val spineItems = listOf("front", "one", "two", "license").map { SpineItem(it, listOf(Block(BlockKind.Paragraph, "x".repeat(100)))) }

    /** Front matter in Spine item 0, Chapters "One" and "Two", then Back matter from Spine item 3, where "License" starts. */
    private val book = OpenBook(
        "id", "", spineItems,
        chapters = listOf(Chapter("One", SpinePoint(1, 0)), Chapter("Two", SpinePoint(2, 0)), Chapter("License", SpinePoint(3, 0))),
        textEnd = SpinePoint(2, 100),
    )

    @Test
    fun `Contents lists every Chapter's title in order, Back matter's last`() {
        assertEquals(listOf("One", "Two", "License"), book.contentsAt(SpinePoint(1, 0), atEnd = false).rows.map { it.title })
    }

    @Test
    fun `the current row is the Chapter holding the Place, in text and in Back matter`() {
        assertEquals(0, book.contentsAt(SpinePoint(1, 50), atEnd = false).current)
        assertEquals(1, book.contentsAt(SpinePoint(2, 0), atEnd = false).current)
        assertEquals(2, book.contentsAt(SpinePoint(3, 40), atEnd = false).current)
    }

    @Test
    fun `on the end page the current row is the last Chapter of the text, not Back matter's`() {
        assertEquals(1, book.contentsAt(SpinePoint(2, 60), atEnd = true).current)
    }

    @Test
    fun `in front matter no row is current`() {
        assertEquals(null, book.contentsAt(SpinePoint(0, 20), atEnd = false).current)
    }

    @Test
    fun `of two Chapters starting at one point, the later is current`() {
        val twin = book.copy(chapters = listOf(Chapter("Part One", SpinePoint(1, 0))) + book.chapters)
        assertEquals(listOf("Part One", "One", "Two", "License"), twin.contentsAt(SpinePoint(1, 0), atEnd = false).rows.map { it.title })
        assertEquals(1, twin.contentsAt(SpinePoint(1, 0), atEnd = false).current)
    }

    @Test
    fun `the tap target is 48 dp from the screen's edge, or the margin, top line and the 4 dp under it when taller`() {
        assertEquals(48.dp, contentsTargetHeight(margin = 14.dp, topLine = 20.dp))
        assertEquals(64.dp, contentsTargetHeight(margin = 14.dp, topLine = 46.dp))
    }

    private val partOne = Part("Part One", SpinePoint(1, 0))
    private val bookA = Part("Book A", SpinePoint(2, 0))

    /** "One" under Part One, "Two" under Part One and Book A, then "License", Back matter. */
    private val nested = book.copy(
        chapters = listOf(Chapter("One", SpinePoint(1, 0), listOf(partOne)), Chapter("Two", SpinePoint(2, 0), listOf(partOne, bookA)), Chapter("License", SpinePoint(3, 0))),
    )

    @Test
    fun `a Part's row, a heading going to its heading, comes before its first Chapter's, outermost first, once`() {
        val rows = nested.contentsAt(SpinePoint(1, 0), atEnd = false).rows
        assertEquals(
            listOf(
                ContentsRow("Part One", SpinePoint(1, 0), part = true), ContentsRow("One", SpinePoint(1, 0)),
                ContentsRow("Book A", SpinePoint(2, 0), part = true), ContentsRow("Two", SpinePoint(2, 0)), ContentsRow("License", SpinePoint(3, 0)),
            ),
            rows,
        )
    }

    @Test
    fun `a Part's row is never current, and the current Chapter's row counts the Part rows above it`() {
        assertEquals(1, nested.contentsAt(SpinePoint(1, 0), atEnd = false).current)
        assertEquals(3, nested.contentsAt(SpinePoint(2, 50), atEnd = false).current)
        assertEquals(3, nested.contentsAt(SpinePoint(2, 60), atEnd = true).current)
        assertEquals(4, nested.contentsAt(SpinePoint(3, 0), atEnd = false).current)
    }

    @Test
    fun `a Part listed beside its Chapters is one row, its Chapter's, current like any other`() {
        val bookOne = Part("BOOK ONE", SpinePoint(1, 0), isChapter = true)
        val flat = book.copy(chapters = listOf(Chapter("BOOK ONE", SpinePoint(1, 0)), Chapter("I", SpinePoint(2, 0), listOf(bookOne))))
        val contents = flat.contentsAt(SpinePoint(1, 0), atEnd = false)
        assertEquals(listOf(ContentsRow("BOOK ONE", SpinePoint(1, 0)), ContentsRow("I", SpinePoint(2, 0))), contents.rows)
        assertEquals(0, contents.current)
    }
}
