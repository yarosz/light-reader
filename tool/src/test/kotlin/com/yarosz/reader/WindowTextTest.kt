package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A window's laid-out text keeps [SpineItem.text]'s offsets, and the first-line indent follows block kinds (DESIGN.md). */
class WindowTextTest {

    @Test
    fun `a window's text is exactly as long as the window, with a separator wherever the Spine item breaks a block`() = forAll(1_000) { rnd ->
        val spineItem = FakeSpineItem.random(rnd).spineItem
        windows(spineItem, rnd.nextInt(1, 6_000)).forEach { window ->
            val text = spineItem.windowText(window)
            assertEquals(window.end - window.start, text.length, "$window")
            for (i in window.firstBlock..window.lastBlock) {
                val start = spineItem.blockStarts[i] - window.start
                assertEquals(spineItem.blocks[i].text, text.substring(start, start + spineItem.blocks[i].text.length))
                if (i < spineItem.blocks.lastIndex) assertEquals(BLOCK_SEPARATOR, text[start + spineItem.blocks[i].text.length])
            }
            assertEquals(spineItem.text.substring(window.start, window.end).replace('\n', BLOCK_SEPARATOR), text)
        }
    }

    @Test
    fun `a window keeps the separator after its last block unless it ends the Spine item`() {
        val spineItem = SpineItem("spine", "", listOf(Block(BlockKind.Paragraph, "one"), Block(BlockKind.Paragraph, "two"), Block(BlockKind.Paragraph, "three")))
        val (first, last) = windows(spineItem, maxChars = 8)
        assertEquals("one${BLOCK_SEPARATOR}two$BLOCK_SEPARATOR", spineItem.windowText(first))
        assertEquals("three", spineItem.windowText(last))
        assertEquals(spineItem.text.length, first.end - first.start + last.end - last.start)
    }

    @Test
    fun `verse keeps its own line breaks inside a window's text`() {
        val spineItem = SpineItem("spine", "", listOf(Block(BlockKind.Verse, "line one\nline two"), Block(BlockKind.Paragraph, "prose")))
        assertEquals("line one\nline two${BLOCK_SEPARATOR}prose", spineItem.windowText(windows(spineItem).single()))
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
    fun `a Spine item's first paragraph never indents, whatever follows it`() {
        val blocks = listOf(Block(BlockKind.Paragraph, "first"), Block(BlockKind.Paragraph, "second"))
        assertFalse(indentsFirstLine(blocks, 0))
        assertTrue(indentsFirstLine(blocks, 1))
    }

    @Test
    fun `a paragraph opening a window still indents when the block before it in the Spine item is a paragraph`() {
        val paragraph = Block(BlockKind.Paragraph, "x".repeat(100))
        val spineItem = SpineItem("spine", "", listOf(paragraph, paragraph, paragraph))
        val second = windows(spineItem, maxChars = 150)[1]
        assertEquals(1, second.firstBlock)
        assertTrue(indentsFirstLine(spineItem.blocks, second.firstBlock))
        val opening = SpineItem("spine", "", listOf(Block(BlockKind.Heading, "x".repeat(100)), paragraph, paragraph))
        val afterHeading = windows(opening, maxChars = 150)[1]
        assertEquals(1, afterHeading.firstBlock)
        assertFalse(indentsFirstLine(opening.blocks, afterHeading.firstBlock))
    }

    private fun forAll(body: (Random) -> Unit) = repeat(1_000) { seed -> body(Random(seed)) }
}
