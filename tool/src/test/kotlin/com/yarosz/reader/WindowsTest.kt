package com.yarosz.reader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Property tests of how a chapter is cut into layout windows (ADR 0007), over thousands of random chapters. */
class WindowsTest {

    private fun randomMaxChars(rnd: Random) = rnd.nextInt(1, 6_000)

    @Test
    fun `windows tile the chapter's blocks and text exactly`() = forAll { rnd ->
        val chapter = FakeChapter.random(rnd).chapter
        val windows = windows(chapter, randomMaxChars(rnd))
        assertEquals(0, windows.first().firstBlock)
        assertEquals(chapter.blocks.lastIndex, windows.last().lastBlock)
        assertEquals(0, windows.first().start)
        assertEquals(chapter.text.length, windows.last().end)
        windows.zipWithNext { a, b ->
            assertEquals(a.lastBlock + 1, b.firstBlock)
            assertEquals(a.end, b.start)
        }
        windows.forEach {
            assertTrue(it.firstBlock <= it.lastBlock)
            assertEquals(chapter.blockStarts[it.firstBlock], it.start)
            assertEquals(chapter.blockStarts.getOrNull(it.lastBlock + 1) ?: chapter.text.length, it.end)
        }
    }

    @Test
    fun `a window stays within maxChars unless it is a single block, and is cut only where it had to be`() = forAll { rnd ->
        val chapter = FakeChapter.random(rnd).chapter
        val maxChars = randomMaxChars(rnd)
        val windows = windows(chapter, maxChars)
        fun isHeading(block: Int) = chapter.blocks[block].kind == BlockKind.Heading
        fun blockLength(block: Int) = (chapter.blockStarts.getOrNull(block + 1) ?: chapter.text.length) - chapter.blockStarts[block]
        windows.forEach {
            assertTrue(it.end - it.start <= maxChars || it.firstBlock == it.lastBlock, "$it exceeds $maxChars")
        }
        windows.zipWithNext { a, b ->
            val chars = a.end - a.start
            val wouldOverflow = chars + blockLength(b.firstBlock) > maxChars
            val headingAfterHalfFull = isHeading(b.firstBlock) && chars >= maxChars / 2
            assertTrue(wouldOverflow || headingAfterHalfFull, "$a could have taken block ${b.firstBlock}")
        }
    }

    @Test
    fun `a heading starts a window once the previous one is half full`() = forAll { rnd ->
        val chapter = FakeChapter.random(rnd).chapter
        val maxChars = randomMaxChars(rnd)
        windows(chapter, maxChars).forEach { window ->
            (window.firstBlock + 1..window.lastBlock)
                .filter { chapter.blocks[it].kind == BlockKind.Heading }
                .forEach { heading ->
                    assertTrue(chapter.blockStarts[heading] - window.start < maxChars / 2, "$window should have cut before heading $heading")
                }
        }
    }

    @Test
    fun `windowIndexFor finds the window holding an offset, and the last one past the end`() = forAll { rnd ->
        val chapter = FakeChapter.random(rnd).chapter
        val windows = windows(chapter, randomMaxChars(rnd))
        repeat(20) {
            val offset = rnd.nextInt(0, chapter.text.length)
            val window = windows[windowIndexFor(windows, offset)]
            assertTrue(offset >= window.start && offset < window.end, "offset $offset not in $window")
        }
        assertEquals(windows.lastIndex, windowIndexFor(windows, chapter.text.length))
    }

    @Test
    fun `an empty chapter has no windows`() {
        assertEquals(emptyList(), windows(Chapter("", emptyList())))
    }

    @Test
    fun `a heading cuts a window that is at least half full, but not one that is less`() {
        val paragraph = Block(BlockKind.Paragraph, "x".repeat(100))
        val heading = Block(BlockKind.Heading, "Title")
        val chapter = Chapter("", listOf(paragraph, paragraph, heading, paragraph))
        assertEquals(listOf(Window(0, 1, 0, 202), Window(2, 3, 202, 308)), windows(chapter, maxChars = 400))
        assertEquals(listOf(Window(0, 3, 0, 308)), windows(chapter, maxChars = 1_000))
    }

    @Test
    fun `an oversized block gets a window to itself`() {
        val long = Block(BlockKind.Paragraph, "x".repeat(500))
        val short = Block(BlockKind.Paragraph, "x".repeat(10))
        assertEquals(
            listOf(Window(0, 0, 0, 11), Window(1, 1, 11, 512), Window(2, 2, 512, 522)),
            windows(Chapter("", listOf(short, long, short)), maxChars = 100),
        )
    }
}
