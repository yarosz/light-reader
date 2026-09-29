package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a Book's text ends and its Back matter starts ([OpenBook.textEnd]), and the Pages on either side. */
class BackMatterTest {

    private val dir: File = createTempDirectory("back-matter").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun open(bodies: List<String>, ncx: String? = null, bodyAttributes: Map<Int, String> = emptyMap()) =
        parseEpub(File(dir, "book.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx, bodyAttributes = bodyAttributes)))

    private fun OpenBook.startOf(text: String): SpinePoint {
        val item = spineItems.indexOfFirst { text in it.text }
        return SpinePoint(item, spineItems[item].text.indexOf(text))
    }

    private fun OpenBook.endOf(item: Int) = SpinePoint(item, spineItems[item].text.length)

    private fun OpenBook.bookEnd() = endOf(spineItems.lastIndex)

    private fun OpenBook.isBackMatter(chapter: Int) = chapters[chapter].start >= textEnd

    @Test
    fun `a Gutenberg EPUB 2's license, mid-way through its last Spine item, starts Back matter, and its Chapter is Back matter`() {
        val book = open(gutenbergBodies(), gutenbergNcx())
        assertEquals(book.startOf(LICENSE_HEADING), book.textEnd)
        assertEquals(2, book.textEnd.item)
        assertEquals(listOf("Title", "Chapter I.", "Chapter II.", LICENSE_HEADING), book.chapters.map { it.title })
        assertEquals(listOf(false, false, false, true), book.chapters.indices.map { book.isBackMatter(it) })
        assertEquals(book.textEnd, book.chapters.last().start)
    }

    @Test
    fun `with no table of contents, a pg-footer mid-way through a Spine item starts a Chapter of its own, titled by its heading`() {
        val book = open(gutenbergBodies())
        assertEquals(book.startOf(LICENSE_HEADING), book.textEnd)
        assertEquals(List(3) { SpinePoint(it, 0) } + book.textEnd, book.chapters.map { it.start })
        assertEquals(listOf("PRIDE AND PREJUDICE", "Chapter I.", "Chapter II.", LICENSE_HEADING), book.chapters.map { it.title })
    }

    @Test
    fun `a table of contents that doesn't list the license still gets a Back matter Chapter there, titled by its heading`() {
        val book = open(gutenbergBodies(), ncx(navPoint("Title", "text/c0.xhtml"), navPoint("Chapter I.", "text/c1.xhtml#ch1"), navPoint("Chapter II.", "text/c2.xhtml#ch2")))
        assertEquals(listOf("Title", "Chapter I.", "Chapter II.", LICENSE_HEADING), book.chapters.map { it.title })
        assertEquals(book.textEnd, book.chapters.last().start)
        assertTrue(book.isBackMatter(3))
    }

    @Test
    fun `a Back matter Chapter with no heading is Chapter N`() {
        val book = open(listOf("<p>One</p>", """<p>Two</p><div id="pg-footer"><p>License terms.</p></div>"""))
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), book.chapters.map { it.title })
        assertEquals(book.startOf("License terms."), book.chapters.last().start)
    }

    @Test
    fun `a listed license at the start of its own Spine item is the Back matter Chapter, with no second one at the text's end`() {
        val bodies = listOf(
            "<h1>Title</h1>",
            "<h2>Chapter I.</h2><p>It is a truth universally acknowledged.</p>",
            """<footer id="pg-footer"><h2 id="pg-footer-heading">$LICENSE_HEADING</h2><p>Terms.</p></footer>""",
        )
        val starts = listOf(SpinePoint(0, 0), SpinePoint(1, 0), SpinePoint(2, 0))
        for (license in listOf("text/c2.xhtml#pg-footer-heading", "text/c2.xhtml")) {
            val book = open(bodies, ncx(navPoint("Title", "text/c0.xhtml"), navPoint("Chapter I.", "text/c1.xhtml"), navPoint(LICENSE_HEADING, license)))
            assertEquals(book.endOf(1), book.textEnd)
            assertEquals(starts, book.chapters.map { it.start })
        }
        val unlisted = open(bodies, ncx(navPoint("Title", "text/c0.xhtml"), navPoint("Chapter I.", "text/c1.xhtml")))
        assertEquals(starts, unlisted.chapters.map { it.start })
        assertEquals(LICENSE_HEADING, unlisted.chapters.last().title)
    }

    @Test
    fun `a Chapter listed at the end of the Spine item before Back matter counts as starting it`() {
        val spineItems = listOf(SpineItem("a", listOf(Block(BlockKind.Paragraph, "One"))), SpineItem("b", listOf(Block(BlockKind.Heading, "Notes"))))
        val listed = listOf(Chapter("One", SpinePoint(0, 0)), Chapter("Notes", SpinePoint(0, 3)))
        assertEquals(listed, chaptersOf(listed, spineItems, textEnd = SpinePoint(0, 3)))
        val unlisted = listOf(Chapter("One", SpinePoint(0, 0)), Chapter("Later", SpinePoint(0, 1)))
        assertEquals(unlisted + Chapter("Notes", SpinePoint(1, 0)), chaptersOf(unlisted, spineItems, textEnd = SpinePoint(0, 3)))
    }

    @Test
    fun `a Book with no Back matter gets no added Chapter`() {
        val book = open(listOf("<h1>One</h1><p>Text</p>", "<p>Two</p>"))
        assertEquals(book.bookEnd(), book.textEnd)
        assertEquals(listOf(SpinePoint(0, 0), SpinePoint(1, 0)), book.chapters.map { it.start })
        val listed = open(gutenbergBodies().map { it.replace("id=\"pg-footer\"", "id=\"elsewhere\"") }, gutenbergNcx())
        assertEquals(listed.bookEnd(), listed.textEnd)
        assertEquals(listOf("Title", "Chapter I.", "Chapter II.", LICENSE_HEADING), listed.chapters.map { it.title })
    }

    @Test
    fun `an EPUB 3's pg-footer Spine item, followed by an empty one, starts Back matter at the end of the Spine item before`() {
        val bodies = listOf(
            "<h1>Title</h1>",
            "<h2>Chapter I.</h2><p>It is a truth universally acknowledged.</p>",
            """<footer class="pg-boilerplate pgheader" id="pg-footer"><div>*** END ***</div><h2 id="pg-footer-heading">$LICENSE_HEADING</h2><p>Terms.</p></footer>""",
            "<div></div>",
        )
        val book = open(bodies)
        assertEquals(3, book.spineItems.size)
        assertEquals(book.endOf(1), book.textEnd)
        assertEquals(SpinePoint(2, 0), book.startOf(LICENSE_HEADING))
    }

    @Test
    fun `a trailing run of Spine items marked backmatter, with no bodymatter, is Back matter, and one followed by text is not`() {
        val bodies = listOf("<p>Front</p>", "<p>One</p>", "<p>Notes</p>", "<p>Colophon</p>")
        val trailing = open(bodies, bodyAttributes = mapOf(2 to "epub:type=\"backmatter\"", 3 to "class=\"backmatter colophon\""))
        assertEquals(trailing.endOf(1), trailing.textEnd)
        val followed = open(bodies, bodyAttributes = mapOf(1 to "epub:type=\"backmatter\""))
        assertEquals(followed.bookEnd(), followed.textEnd)
        val mixed = open(bodies, bodyAttributes = mapOf(1 to "epub:type=\"backmatter\"", 3 to "epub:type=\"backmatter\""))
        assertEquals(mixed.endOf(2), mixed.textEnd)
    }

    @Test
    fun `the earlier of pg-footer and a backmatter run wins`() {
        val bodies = gutenbergBodies() + "<p>Colophon</p>"
        val book = open(bodies, bodyAttributes = mapOf(3 to "epub:type=\"backmatter\""))
        assertEquals(book.startOf(LICENSE_HEADING), book.textEnd)
        val footerLater = open(listOf("<p>One</p>", "<p>Notes</p>", """<div id="pg-footer"><p>License</p></div>"""), bodyAttributes = mapOf(1 to "epub:type=\"backmatter\"", 2 to "epub:type=\"backmatter\""))
        assertEquals(footerLater.endOf(0), footerLater.textEnd)
    }

    @Test
    fun `an unmarked Book's text runs to the end of the Book`() {
        val book = open(listOf("<p>One</p>", "<p>Two</p>"))
        assertEquals(book.bookEnd(), book.textEnd)
    }

    @Test
    fun `a Book whose only text is inside pg-footer ignores the rule`() {
        val only = open(listOf("""<div class="pg-boilerplate" id="pg-footer"><h2>License</h2><p>Only text.</p></div>"""))
        assertEquals(only.bookEnd(), only.textEnd)
        val first = open(listOf("""<footer id="pg-footer"><p>License</p></footer>""", "<p>After</p>"))
        assertEquals(first.bookEnd(), first.textEnd)
        val allBackMatter = open(listOf("<p>Notes</p>"), bodyAttributes = mapOf(0 to "epub:type=\"backmatter\""))
        assertEquals(allBackMatter.bookEnd(), allBackMatter.textEnd)
    }

    @Test
    fun `a pg-footer inside a block starts Back matter at the next block, so no Page is cut mid-block`() {
        val book = open(listOf("""<p>Before <span id="pg-footer">inside</span></p><p>After</p>"""))
        assertEquals(book.startOf("After"), book.textEnd)
        val atTheEnd = open(listOf("<p>One</p>", """<p>Two <span id="pg-footer">three</span></p>""", "<p>Four</p>"))
        assertEquals(atTheEnd.endOf(1), atTheEnd.textEnd)
    }

    @Test
    fun `detecting pg-footer changes no block or Spine item, so a Place saved before it resolves where it did, inside the license too`() {
        val book = open(gutenbergBodies(), gutenbergNcx())
        val unseen = open(gutenbergBodies().map { it.replace("id=\"pg-footer\"", "id=\"pg-footer-unseen\"") }, gutenbergNcx())
        assertEquals(unseen.bookEnd(), unseen.textEnd)
        assertEquals(unseen.spineItems, book.spineItems)
        val license = unseen.startOf(LICENSE_TERMS)
        val lastText = unseen.startOf(LAST_TEXT)
        for (point in listOf(license, SpinePoint(license.item, license.char + 7), lastText)) {
            val saved = unseen.spineItems[point.item].placeOf(point.char, 1)
            assertEquals(point, book.resolve(saved))
        }
    }

    @Test
    fun `the last Page of the text ends at the text's end and Back matter starts a Page, at any column and window size`() {
        val book = open(gutenbergBodies(paragraphs = 40), gutenbergNcx())
        val end = book.textEnd
        for (charsPerLine in listOf(7, 23, 61)) for (height in listOf(30, 90, 300)) for (windowChars in listOf(150, 1_000, WINDOW_CHARS)) {
            val reading = blockLineReading(book, charsPerLine, height, windowChars)
            val forward = generateSequence(reading.open(0, 0, LayoutKey(0, 1, height))) { reading.next() }.toList()
            val points = forward.map { SpinePoint(it.pass.item, it.page.start) to SpinePoint(it.pass.item, it.page.end) }
            assertTrue(points.none { (start, stop) -> start < end && stop > end }, "a Page spans the text's end at $charsPerLine/$height/$windowChars")
            assertEquals(1, points.count { it.second == end })
            val backward = generateSequence(reading.open(end.item, end.char + 1, LayoutKey(0, 1, height))) { reading.previous() }.toList()
            assertTrue(backward.any { SpinePoint(it.pass.item, it.page.end) == end })
        }
    }

    /** A [Reading] over [book] whose lines restart at each block, [charsPerLine] characters and 10 px each, on Pages [height] px tall. */
    private fun blockLineReading(book: OpenBook, charsPerLine: Int, height: Int, windowChars: Int): Reading<List<LineMetrics>> =
        Reading(book.spineItems, measure = { pass, window -> blockLines(pass.spineItem, pass.windows[window], charsPerLine) }, linesOf = { it }, windowChars = windowChars, textEnd = book.textEnd)

    private fun blockLines(spineItem: SpineItem, window: Window, charsPerLine: Int): List<LineMetrics> =
        (window.firstBlock..window.lastBlock).flatMap { block ->
            val start = spineItem.blockStarts[block]
            (start until start + maxOf(spineItem.blocks[block].text.length, 1) step charsPerLine).toList()
        }.mapIndexed { i, start -> LineMetrics(start, i * 10f, (i + 1) * 10f, endsAtBreak = true, heading = false) }

    companion object {
        const val LICENSE_HEADING = "THE FULL PROJECT GUTENBERG LICENSE"
        const val LICENSE_TERMS = "To protect the Project Gutenberg mission of promoting the free distribution."
        const val LAST_TEXT = "They were always on the most intimate terms."

        /** A Gutenberg-shaped no-images Book: title page, two Chapters, and the license mid-way through the last Spine item. */
        fun gutenbergBodies(paragraphs: Int = 1): List<String> {
            val words = List(paragraphs) { "<p>It is a truth universally acknowledged, that a single man in possession of a good fortune must be in want of a wife. $it</p>" }.joinToString("")
            return listOf(
                "<h1>PRIDE AND PREJUDICE</h1><p>By Jane Austen</p>",
                """<h2 id="ch1">Chapter I.</h2>$words""",
                """<h2 id="ch2">Chapter II.</h2>$words<p>$LAST_TEXT</p>""" +
                    """<div class="pg-boilerplate pgheader footer" id="pg-footer"><h2 id="pg-footer-heading">$LICENSE_HEADING</h2>""" +
                    """<div>Text sitting directly in a div, which the parser drops.</div>$words<p>$LICENSE_TERMS</p></div>""",
            )
        }

        fun gutenbergNcx() = ncx(
            navPoint("Title", "text/c0.xhtml"),
            navPoint("Chapter I.", "text/c1.xhtml#ch1"),
            navPoint("Chapter II.", "text/c2.xhtml#ch2"),
            navPoint(LICENSE_HEADING, "text/c2.xhtml#pg-footer-heading"),
        )
    }
}
