package com.yarosz.reader

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A lazy open ([EpubOpening], ADR 0009): its first Book is the Place's Spine item alone, and its whole Book is [parseEpub]'s. */
class LazyParseTest {
    private val alice = File("src/test/fixtures/alice.epub")
    private val dir: File = createTempDirectory("lazy-parse").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun epub(name: String, files: Map<String, String>) = File(dir, "$name.epub").writeEpub(files)

    /** A Book that marks no body matter, opening on a document typed as a title page, which it therefore keeps. */
    private val unmarkedTitlePage by lazy {
        epub("titlepage", tocEpubFiles(
            listOf("""<section epub:type="titlepage"><h1>A Book</h1></section>""", "<h2>One</h2><p>The first.</p>", "<p>More of one.</p>", "<h2>Two</h2><p>The second.</p>"),
            ncx = ncx(navPoint("One", "text/c1.xhtml"), navPoint("Two", "text/c3.xhtml")),
        ))
    }

    /** A Gutenberg-like Book whose license starts inside its last Spine item. */
    private val licensed by lazy {
        epub("licensed", tocEpubFiles(
            listOf("<h2>I</h2><p>The first.</p>", """<h2>II</h2><p>The second.</p><div id="pg-footer"><h2>THE LICENSE</h2><p>Terms.</p></div>"""),
            ncx = ncx(navPoint("I", "text/c0.xhtml"), navPoint("II", "text/c1.xhtml")),
        ))
    }

    private val books get() = listOf(alice, unmarkedTitlePage, licensed)

    private fun spineIds(file: File) = ZipFile(file).use { zip -> readPackage(zip, "").spine.map { it.idref } }

    @Test
    fun `the whole Book after a placed one is exactly the eager parse, whichever Spine item was placed`() {
        for (file in books) {
            val book = parseEpub(file)
            for (spineId in spineIds(file) + null + "no-such-item") {
                EpubOpening(file, file.nameWithoutExtension).use { opening ->
                    opening.placed(spineId)
                    assertEquals(book, opening.whole(), "${file.name}, placed $spineId")
                }
            }
        }
    }

    @Test
    fun `a placed Spine item is parsed alone, and is the whole Book's, with the Chapters the whole Book starts in it and its text end there`() {
        for (file in books) {
            val book = parseEpub(file)
            for ((index, item) in book.spineItems.withIndex()) {
                EpubOpening(file, file.nameWithoutExtension).use { opening ->
                    val placed = opening.placed(item.spineId)!!
                    assertEquals(listOf(item), placed.spineItems)
                    assertEquals(item.blocks.sumOf { it.text.length.toLong() }, opening.parsedChars, "${file.name} ${item.spineId}: parsed beyond its Spine item")
                    val inItem = book.chapters.filter { it.start.item == index }
                    assertEquals(inItem.map { it.title to it.start.char }, placed.chapters.map { it.title to it.start.char }, "${file.name} ${item.spineId}")
                    if (book.textEnd.item == index) assertEquals(book.textEnd.char, placed.textEnd.char, "${file.name} ${item.spineId}")
                }
            }
        }
    }

    @Test
    fun `a placed Spine item its table of contents lists nothing in has no Chapter, as the one it is in starts earlier`() {
        val book = parseEpub(unmarkedTitlePage)
        assertEquals("One", book.chapters[book.chapterAt(SpinePoint(2, 0))!!].title)
        val placed = EpubOpening(unmarkedTitlePage, "").use { it.placed("c2")!! }
        assertEquals(emptyList(), placed.chapters)
        assertNull(placed.chapterAt(SpinePoint(0, 0)))
    }

    @Test
    fun `with no Place, the placed Book is the first Spine item that could start the Book, the whole Book's first unless an unmarked title page leads it`() {
        val book = parseEpub(alice)
        assertEquals(listOf(book.spineItems.first()), EpubOpening(alice, "").use { it.placed(null)!! }.spineItems)
        val unmarked = parseEpub(unmarkedTitlePage)
        assertEquals("c0", unmarked.spineItems.first().spineId)
        assertEquals("c1", EpubOpening(unmarkedTitlePage, "").use { it.placed(null)!! }.spineItems.single().spineId)
    }

    @Test
    fun `a Place's Spine item the whole Book drops is placed all the same, and a Spine id naming none places the Book's start`() {
        val book = parseEpub(alice)
        assertTrue(book.spineItems.none { it.spineId == "imprint.xhtml" })
        assertEquals("imprint.xhtml", EpubOpening(alice, "").use { it.placed("imprint.xhtml")!! }.spineItems.single().spineId)
        assertEquals(listOf(book.spineItems.first()), EpubOpening(alice, "").use { it.placed("no-such-item")!! }.spineItems)
    }

    @Test
    fun `a Book with no text places nothing, and its whole Book has no Spine item`() {
        val file = epub("empty", tocEpubFiles(listOf("", "<p> </p>")))
        EpubOpening(file, "").use { opening ->
            assertNull(opening.placed(null))
            assertEquals(parseEpub(file), opening.whole())
            assertEquals(emptyList(), opening.whole().spineItems)
        }
    }

    @Test
    fun `a Place's Spine item that keeps no text alone, marked body matter and as not reading matter, places the Book's start`() {
        val file = epub("marked", tocEpubFiles(
            listOf("""<section epub:type="titlepage"><h1>A Book</h1></section>""", "<h2>One</h2><p>The first.</p>"),
            bodyAttributes = mapOf(0 to """epub:type="bodymatter"""", 1 to """epub:type="bodymatter""""),
        ))
        assertEquals(listOf("c1"), parseEpub(file).spineItems.map { it.spineId })
        EpubOpening(file, "").use { assertEquals("c1", it.placed("c0")!!.spineItems.single().spineId) }
    }

    @Test
    fun `scripts ci_sh opens Alice lazily, naming its Spine item 2 by the id it has`() {
        val id = parseEpub(alice).spineItems[2].spineId
        assertTrue("echo 2 spine=$id > files/dev-start" in File("../scripts/ci.sh").readText(), "ci.sh's dev-start names $id")
    }
}
