package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals

/** What Contents lists and which row is current ([contentsAt]). */
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
                ContentsRow("Part One", SpinePoint(1, 0), isPart = true), ContentsRow("One", SpinePoint(1, 0)),
                ContentsRow("Book A", SpinePoint(2, 0), isPart = true), ContentsRow("Two", SpinePoint(2, 0)), ContentsRow("License", SpinePoint(3, 0)),
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
    fun `the running head is the Chapter's Part, then the Chapter, or the Chapter alone`() {
        val part = Part("BOOK TWO: 1805", SpinePoint(1, 0))
        assertEquals("BOOK TWO: 1805 · CHAPTER I", runningHeadOf(Chapter("CHAPTER I", SpinePoint(1, 0), listOf(Part("VOLUME I", SpinePoint(0, 0)), part))))
        assertEquals("CHAPTER I", runningHeadOf(Chapter("CHAPTER I", SpinePoint(1, 0))))
    }
}
