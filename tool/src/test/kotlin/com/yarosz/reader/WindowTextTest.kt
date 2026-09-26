package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A window's laid-out text keeps [Chapter.text]'s offsets, and the first-line indent follows block kinds (DESIGN.md). */
class WindowTextTest {

    @Test
    fun `a window's text is exactly as long as the window, with a separator wherever the chapter breaks a block`() = forAll(1_000) { rnd ->
        val chapter = FakeChapter.random(rnd).chapter
        windows(chapter, rnd.nextInt(1, 6_000)).forEach { window ->
            val text = chapter.windowText(window)
            assertEquals(window.end - window.start, text.length, "$window")
            for (i in window.firstBlock..window.lastBlock) {
                val start = chapter.blockStarts[i] - window.start
                assertEquals(chapter.blocks[i].text, text.substring(start, start + chapter.blocks[i].text.length))
                if (i < chapter.blocks.lastIndex) assertEquals(BLOCK_SEPARATOR, text[start + chapter.blocks[i].text.length])
            }
            assertEquals(chapter.text.substring(window.start, window.end).replace('\n', BLOCK_SEPARATOR), text)
        }
    }

    @Test
    fun `a window keeps the separator after its last block unless it ends the chapter`() {
        val chapter = Chapter("", listOf(Block(BlockKind.Paragraph, "one"), Block(BlockKind.Paragraph, "two"), Block(BlockKind.Paragraph, "three")))
        val (first, last) = windows(chapter, maxChars = 8)
        assertEquals("one${BLOCK_SEPARATOR}two$BLOCK_SEPARATOR", chapter.windowText(first))
        assertEquals("three", chapter.windowText(last))
        assertEquals(chapter.text.length, first.end - first.start + last.end - last.start)
    }

    @Test
    fun `verse keeps its own line breaks inside a window's text`() {
        val chapter = Chapter("", listOf(Block(BlockKind.Verse, "line one\nline two"), Block(BlockKind.Paragraph, "prose")))
        assertEquals("line one\nline two${BLOCK_SEPARATOR}prose", chapter.windowText(windows(chapter).single()))
    }

    @Test
    fun `only a paragraph after a paragraph indents`() {
        val blocks = listOf(
            Block(BlockKind.Heading, "I"),
            Block(BlockKind.Paragraph, "after a heading"),
            Block(BlockKind.Paragraph, "after a paragraph"),
            Block(BlockKind.Verse, "a\nb"),
            Block(BlockKind.Paragraph, "after verse"),
            Block(BlockKind.Caption, "an illustration"),
            Block(BlockKind.Paragraph, "after a caption"),
            Block(BlockKind.Paragraph, "after a paragraph again"),
        )
        assertEquals(listOf(false, false, true, false, false, false, false, true), blocks.indices.map { indentsFirstLine(blocks, it) })
    }

    @Test
    fun `a chapter's first paragraph never indents, whatever follows it`() {
        val blocks = listOf(Block(BlockKind.Paragraph, "first"), Block(BlockKind.Paragraph, "second"))
        assertFalse(indentsFirstLine(blocks, 0))
        assertTrue(indentsFirstLine(blocks, 1))
    }

    @Test
    fun `a paragraph opening a window still indents when the block before it in the chapter is a paragraph`() {
        val paragraph = Block(BlockKind.Paragraph, "x".repeat(100))
        val chapter = Chapter("", listOf(paragraph, paragraph, paragraph))
        val second = windows(chapter, maxChars = 150)[1]
        assertEquals(1, second.firstBlock)
        assertTrue(indentsFirstLine(chapter.blocks, second.firstBlock))
        val opening = Chapter("", listOf(Block(BlockKind.Heading, "x".repeat(100)), paragraph, paragraph))
        val afterHeading = windows(opening, maxChars = 150)[1]
        assertEquals(1, afterHeading.firstBlock)
        assertFalse(indentsFirstLine(opening.blocks, afterHeading.firstBlock))
    }

    private fun forAll(body: (Random) -> Unit) = repeat(1_000) { seed -> body(Random(seed)) }
}
