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
        assertEquals(listOf("One", "Two", "License"), book.contentsAt(SpinePoint(1, 0), atEnd = false).titles)
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
        assertEquals(listOf("Part One", "One", "Two", "License"), twin.contentsAt(SpinePoint(1, 0), atEnd = false).titles)
        assertEquals(1, twin.contentsAt(SpinePoint(1, 0), atEnd = false).current)
    }

    @Test
    fun `the tap target is 48 dp from the screen's edge, or the margin, top line and the 4 dp under it when taller`() {
        assertEquals(48.dp, contentsTargetHeight(margin = 14.dp, topLine = 20.dp))
        assertEquals(64.dp, contentsTargetHeight(margin = 14.dp, topLine = 46.dp))
    }
}
