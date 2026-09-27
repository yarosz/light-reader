package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parses the Standard Ebooks "Alice" (images stripped) checked in under src/test/fixtures. */
class EpubTest {

    private val book = parseEpub(File("src/test/fixtures/alice.epub"))

    @Test
    fun `keeps only the twelve chapters of body matter`() {
        assertEquals("Alice’s Adventures in Wonderland", book.title)
        assertEquals(12, book.chapters.size)
        assertEquals("I: Down the Rabbit-Hole", book.chapters[0].title)
        assertEquals("XII: Alice’s Evidence", book.chapters[11].title)
    }

    @Test
    fun `the Book is identified by the package's unique identifier`() {
        assertEquals("https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/john-tenniel", book.identifier)
    }

    @Test
    fun `each chapter names the Spine item it came from`() {
        assertEquals((1..12).map { "chapter-$it.xhtml" }, book.chapters.map { it.spineId })
    }

    @Test
    fun `the unique identifier wins, else the first, else a hash of title and spine`() {
        val ids = listOf(null to "urn:isbn:1", "blank" to "", "uid" to "https://example.org/book")
        assertEquals("https://example.org/book", bookIdentifier(ids, "uid", "T", listOf("a")))
        assertEquals("urn:isbn:1", bookIdentifier(ids, "missing", "T", listOf("a")))
        assertEquals("urn:isbn:1", bookIdentifier(ids, null, "T", listOf("a")))

        val hashed = bookIdentifier(emptyList(), null, "T", listOf("a", "b"))
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(hashed), hashed)
        assertEquals(hashed, bookIdentifier(listOf("blank" to ""), "blank", "T", listOf("a", "b")))
        assertFalse(hashed == bookIdentifier(emptyList(), null, "T", listOf("a", "c")))
        assertFalse(hashed == bookIdentifier(emptyList(), null, "U", listOf("a", "b")))
    }

    @Test
    fun `chapter opens with its heading then the first paragraph`() {
        val blocks = book.chapters[0].blocks
        assertEquals(Block(BlockKind.Heading, "I: Down the Rabbit-Hole"), blocks[0])
        assertEquals(BlockKind.Paragraph, blocks[1].kind)
        assertTrue(blocks[1].text.startsWith("Alice was beginning to get very tired"))
    }

    @Test
    fun `emphasis spans point at the emphasised words`() {
        val para = book.chapters[0].blocks.first { it.text.startsWith("There was nothing so") }
        val span = para.spans.first()
        assertEquals(Emphasis.Italic, span.emphasis)
        assertEquals("very", para.text.substring(span.start, span.end))
    }

    @Test
    fun `poems keep their line breaks, one block per stanza`() {
        val blocks = book.chapters[1].blocks
        val first = blocks.indexOfFirst { it.kind == BlockKind.Verse && "crocodile" in it.text }
        assertEquals(
            listOf("“How doth the little crocodile", "Improve his shining tail,", "And pour the waters of the Nile", "On every golden scale!"),
            blocks[first].text.lines(),
        )
        assertEquals(BlockKind.Verse, blocks[first + 1].kind)
        assertEquals(4, blocks[first + 1].text.lines().size)
        assertEquals(BlockKind.Paragraph, blocks[first + 2].kind) // prose resumes after the poem
    }

    @Test
    fun `illustrations become captions from their alt text`() {
        assertTrue(book.chapters[0].blocks.any {
            it.kind == BlockKind.Caption && it.text.startsWith("A white rabbit wearing a waistcoat")
        })
    }

    @Test
    fun `text is clean of invisible characters and doubled whitespace`() {
        book.chapters.flatMap { it.blocks }.forEach { block ->
            assertFalse('\uFEFF' in block.text, "zero-width space in ${block.text.take(40)}")
            assertFalse("  " in block.text, "double space in ${block.text.take(40)}")
            assertEquals(block.text.trim(), block.text)
        }
    }

    @Test
    fun `block offsets index into the joined chapter text`() {
        book.chapters.forEach { chapter ->
            chapter.blocks.forEachIndexed { i, block ->
                val start = chapter.blockStarts[i]
                assertEquals(block.text, chapter.text.substring(start, start + block.text.length))
            }
        }
    }
}
