package com.yarosz.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parses the Standard Ebooks "Alice" (images stripped) checked in under src/test/fixtures. */
class EpubTest {

    private val book = parseEpub(File("src/test/fixtures/alice.epub"))

    @Test
    fun `keeps only the twelve Spine items of body matter`() {
        assertEquals("Alice’s Adventures in Wonderland", book.title)
        assertEquals(12, book.spineItems.size)
        assertEquals("I: Down the Rabbit-Hole", book.chapters[0].title)
        assertEquals("XII: Alice’s Evidence", book.chapters[11].title)
    }

    @Test
    fun `the Book is identified by the package's unique identifier`() {
        assertEquals("https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/john-tenniel", book.identifier)
    }

    @Test
    fun `the author is every dc creator, joined, or none`() {
        assertEquals("Lewis Carroll", book.author)
        val dir = createTempDirectory("author").toFile()
        try {
            fun authorOf(creators: String): String? {
                val files = epubFiles().let { it + ("OEBPS/content.opf" to it.getValue("OEBPS/content.opf").replace("</metadata>", "$creators</metadata>")) }
                return ZipFile(File(dir, "b.epub").writeEpub(files)).use { readPackage(it, "t") }.author
            }
            assertEquals("Jane Austen, Charlotte Brontë", authorOf("<dc:creator>Jane Austen</dc:creator><dc:creator> Charlotte\n  Brontë </dc:creator>"))
            assertEquals(null, authorOf(""))
            assertEquals(null, authorOf("<dc:creator> </dc:creator>"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `each Spine item keeps its idref from the Spine`() {
        assertEquals((1..12).map { "chapter-$it.xhtml" }, book.spineItems.map { it.spineId })
    }

    @Test
    fun `the unique identifier wins, else the first, else a hash of the Spine's content`() {
        val spine = listOf(document(0x1a2b, 100), document(0x3c4d, 200))
        val ids = listOf(null to "urn:isbn:1", "blank" to "", "uid" to "https://example.org/book")
        assertEquals("https://example.org/book", bookIdentifier(ids, "uid", spine))
        assertEquals("urn:isbn:1", bookIdentifier(ids, "missing", spine))
        assertEquals("urn:isbn:1", bookIdentifier(ids, null, spine))

        val hashed = bookIdentifier(emptyList(), null, spine)
        // Pinned: a stored Book's Place is keyed by this, so changing the formula orphans it.
        assertEquals("sha256:16e0b346e8479706c82c2de88b5bbac7459e0aaaca533c080770f121e9cc5e6a", hashed)
        assertEquals(hashed, bookIdentifier(listOf("blank" to ""), "blank", listOf(document(0x1a2b, 100, "other/name.xhtml"), document(0x3c4d, 200))))
        assertFalse(hashed == bookIdentifier(emptyList(), null, spine.reversed()))
        assertFalse(hashed == bookIdentifier(emptyList(), null, listOf(document(0x1a2b, 100), document(0x3c4e, 200))))
        assertFalse(hashed == bookIdentifier(emptyList(), null, listOf(document(0x1a2b, 100), document(0x3c4d, 201))))
    }

    @Test
    fun `a Book with no identifier keeps its hash across re-downloads, and changes with its text, not its title`() {
        val dir = createTempDirectory("epub").toFile()
        fun identify(name: String, title: String, spineItems: List<String>) =
            parseEpub(File(dir, name).writeEpub(epubFiles(identifier = null, title = title, spineItems = spineItems))).identifier
        try {
            val first = identify("a.epub", "Stories", listOf("Once upon a time.", "The end."))
            assertEquals(first, identify("b.epub", "Stories", listOf("Once upon a time.", "The end.")))
            assertEquals(first, identify("c.epub", "Stories, retitled", listOf("Once upon a time.", "The end.")))
            assertFalse(first == identify("d.epub", "Stories", listOf("Once upon a time.", "The End.")))
            assertFalse(first == identify("e.epub", "Stories", listOf("Twice upon a time.", "The end.")))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun document(crc: Long, size: Long, name: String = "text/$crc.xhtml") = ZipEntry(name).also {
        it.crc = crc
        it.size = size
    }

    @Test
    fun `the package names the Book and its Spine documents without parsing the text`() {
        val pkg = ZipFile(File("src/test/fixtures/alice.epub")).use { readPackage(it, "ignored") }
        assertEquals(book.identifier, pkg.identifier)
        assertEquals(book.title, pkg.title)
        assertEquals(SpineRef("chapter-1.xhtml", "epub/text/chapter-1.xhtml"), pkg.spine.first { it.idref == "chapter-1.xhtml" })
    }

    @Test
    fun `manifest hrefs are URI paths, resolved against the package's directory`() {
        assertEquals("OEBPS/text/a+b c.xhtml", zipPath("OEBPS/", "text/a+b%20c.xhtml"))
        assertEquals("OEBPS/100%.xhtml", zipPath("OEBPS/", "100%.xhtml"))
        assertEquals("OEBPS/%zz%2.xhtml", zipPath("OEBPS/", "%zz%2.xhtml"))
        assertEquals("OEBPS/a%+1b%-1c% 1d%41.xhtml", zipPath("OEBPS/", "a%+1b%-1c% 1d%2541.xhtml"))
        assertEquals("OEBPS/caf\u00e9.xhtml", zipPath("OEBPS/", "caf%C3%A9.xhtml"))
        assertEquals("Text/c.xhtml", zipPath("OEBPS/", "../Text/c.xhtml"))
        assertEquals("c.xhtml", zipPath("OEBPS/", "../../c.xhtml"))
        assertEquals("OEBPS/c.xhtml", zipPath("OEBPS/", "./c.xhtml#part"))
        assertEquals("OEBPS/fonts/a.otf", zipPath("", "/OEBPS/fonts/a.otf"))

        val dir = createTempDirectory("epub").toFile()
        try {
            val files = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to """<package><metadata/><manifest>
                    <item id="a" href="a+b.xhtml" media-type="application/xhtml+xml"/>
                    <item id="b" href="100%.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c" href="../Text/c.xhtml" media-type="application/xhtml+xml"/>
                    </manifest><spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>""",
                "OEBPS/a+b.xhtml" to "<html><body><p>A</p></body></html>",
                "OEBPS/100%.xhtml" to "<html><body><p>B</p></body></html>",
                "Text/c.xhtml" to "<html><body><p>C</p></body></html>",
            )
            assertEquals(listOf("A", "B", "C"), parseEpub(File(dir, "paths.epub").writeEpub(files)).spineItems.map { it.text })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a package with no title takes the fallback, and one with no Spine document fails`() {
        val dir = createTempDirectory("epub").toFile()
        try {
            val untitled = File(dir, "untitled.epub").writeEpub(epubFiles(title = null))
            assertEquals("untitled", parseEpub(untitled).title)
            val blank = File(dir, "blank.epub").writeEpub(epubFiles(title = ""))
            assertEquals("blank", parseEpub(blank).title)
            val missing = File(dir, "missing.epub").writeEpub(epubFiles().filterKeys { it != "OEBPS/c0.xhtml" })
            assertFailsWith<IllegalStateException> { ZipFile(missing).use { readPackage(it, "missing") } }
            val empty = File(dir, "empty.epub").writeEpub(epubFiles(spineItems = emptyList()))
            assertFailsWith<IllegalStateException> { ZipFile(empty).use { readPackage(it, "empty") } }
            val bare = File(dir, "bare.epub").writeEpub(mapOf("mimetype" to "application/epub+zip"))
            assertFailsWith<IllegalStateException> { ZipFile(bare).use { readPackage(it, "bare") } }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a Spine item opens with its heading then the first paragraph`() {
        val blocks = book.spineItems[0].blocks
        assertEquals(Block(BlockKind.Heading, "I: Down the Rabbit-Hole"), blocks[0])
        assertEquals(BlockKind.Paragraph, blocks[1].kind)
        assertTrue(blocks[1].text.startsWith("Alice was beginning to get very tired"))
    }

    @Test
    fun `emphasis spans point at the emphasised words`() {
        val para = book.spineItems[0].blocks.first { it.text.startsWith("There was nothing so") }
        val span = para.spans.first()
        assertEquals(Emphasis.Italic, span.emphasis)
        assertEquals("very", para.text.substring(span.start, span.end))
    }

    @Test
    fun `poems keep their line breaks, one block per stanza`() {
        val blocks = book.spineItems[1].blocks
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
        assertTrue(book.spineItems[0].blocks.any {
            it.kind == BlockKind.Caption && it.text.startsWith("A white rabbit wearing a waistcoat")
        })
    }

    @Test
    fun `text is clean of invisible characters and doubled whitespace`() {
        book.spineItems.flatMap { it.blocks }.forEach { block ->
            assertFalse('\uFEFF' in block.text, "zero-width space in ${block.text.take(40)}")
            assertFalse("  " in block.text, "double space in ${block.text.take(40)}")
            assertEquals(block.text.trim(), block.text)
        }
    }

    @Test
    fun `block offsets index into the joined Spine item text`() {
        book.spineItems.forEach { spineItem ->
            spineItem.blocks.forEachIndexed { i, block ->
                val start = spineItem.blockStarts[i]
                assertEquals(block.text, spineItem.text.substring(start, start + block.text.length))
            }
        }
    }
}
