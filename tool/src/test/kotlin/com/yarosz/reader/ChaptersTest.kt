package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Chapters from a Book's table of contents (ADR 0004), and which Chapter a SpinePoint is in. */
class ChaptersTest {

    private val dir: File = createTempDirectory("chapters").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A Book of [bodies], one Spine item each, in OEBPS/text/ under [names]; [nav] and [ncx] sit in OEBPS/. */
    private fun open(
        bodies: List<String>,
        nav: String? = null,
        ncx: String? = null,
        names: List<String> = bodies.indices.map { "c$it.xhtml" },
    ): OpenBook {
        val manifest = bodies.indices.joinToString("") {
            """<item id="c$it" href="text/${names[it].replace(" ", "%20")}" media-type="application/xhtml+xml"/>"""
        } + (if (nav != null) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else "") +
            (if (ncx != null) """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" else "")
        val spine = bodies.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
        val files = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:chapters</dc:identifier><dc:title>Chapters</dc:title></metadata><manifest>$manifest</manifest><spine${if (ncx != null) " toc=\"ncx\"" else ""}>$spine</spine></package>""",
        ) + bodies.mapIndexed { i, body ->
            "OEBPS/text/${names[i]}" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c$i</title></head><body>$body</body></html>"""
        } + listOfNotNull(nav?.let { "OEBPS/nav.xhtml" to it }, ncx?.let { "OEBPS/toc.ncx" to it })
        return parseEpub(File(dir, "book.epub").writeEpub(files))
    }

    private fun nav(vararg items: String) =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>""" +
            """<nav epub:type="landmarks"><ol><li><a href="text/c0.xhtml">Landmark</a></li></ol></nav>""" +
            """<nav epub:type="toc"><h2>Table of Contents</h2><ol>${items.joinToString("")}</ol></nav></body></html>"""

    private fun li(label: String, href: String?, vararg children: String) =
        "<li>" + (if (href != null) "<a href=\"$href\">$label</a>" else "<span>$label</span>") +
            (if (children.isEmpty()) "" else "<ol>${children.joinToString("")}</ol>") + "</li>"

    private fun ncx(vararg points: String) =
        """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Chapters</text></docTitle><navMap>${points.joinToString("")}</navMap></ncx>"""

    private fun navPoint(label: String, src: String, vararg children: String) =
        "<navPoint><navLabel><text>$label</text></navLabel><content src=\"$src\"/>${children.joinToString("")}</navPoint>"

    private fun OpenBook.startOf(text: String): SpinePoint {
        val item = spineItems.indexOfFirst { text in it.text }
        return SpinePoint(item, spineItems[item].text.indexOf(text))
    }

    @Test
    fun `the nav document wins over the NCX`() {
        val book = open(
            listOf("<p>One</p>", "<p>Two</p>"),
            nav = nav(li("Nav one", "text/c0.xhtml"), li("Nav two", "text/c1.xhtml")),
            ncx = ncx(navPoint("NCX one", "text/c0.xhtml"), navPoint("NCX two", "text/c1.xhtml")),
        )
        assertEquals(listOf(Chapter("Nav one", SpinePoint(0, 0)), Chapter("Nav two", SpinePoint(1, 0))), book.chapters)
    }

    @Test
    fun `with no nav document, or one that doesn't parse, the NCX's leaf navPoints are the Chapters`() {
        val bodies = listOf("<h1>Part</h1><p>Opening</p>", "<p>Two</p>", "<p>Three</p>")
        val ncx = ncx(navPoint("Part One", "text/c0.xhtml", navPoint("Chapter 1", "text/c0.xhtml#x"), navPoint("Chapter 2", "text/c1.xhtml")), navPoint("  Chapter\n 3 ", "text/c2.xhtml"))
        val expected = listOf("Chapter 1" to SpinePoint(0, 0), "Chapter 2" to SpinePoint(1, 0), "Chapter 3" to SpinePoint(2, 0))
        assertEquals(expected, open(bodies, ncx = ncx).chapters.map { it.title to it.start })
        assertEquals(expected, open(bodies, nav = "<html><body><nav", ncx = ncx).chapters.map { it.title to it.start })
    }

    @Test
    fun `only leaf entries are Chapters, and a Part page continues the Chapter before it`() {
        val book = open(
            listOf(
                "<p>Title page</p>",
                "<h1>Part One</h1><h2 id=\"ch1\">Chapter 1</h2><p>First</p>",
                "<h2>Chapter 2</h2><p>Second</p>",
                "<h1>Part Two</h1>",
                "<h2>Chapter 3</h2><p>Third</p>",
            ),
            nav = nav(
                li("Part One", "text/c1.xhtml", li("Chapter 1", "text/c1.xhtml#ch1"), li("Chapter 2", "text/c2.xhtml")),
                li("Part Two", null, li("Chapter 3", "text/c4.xhtml")),
            ),
        )
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), book.chapters.map { it.title })
        assertEquals(book.startOf("Chapter 1"), book.chapters[0].start)
        assertNull(book.chapterAt(book.startOf("Part One")))
        assertEquals(1, book.chapterAt(book.startOf("Part Two")))
    }

    @Test
    fun `fragments put several Chapters in one Spine item, an empty element's id at the next text`() {
        val book = open(
            listOf(
                "<h2 id=\"a\">One</h2><p>Alpha</p><h2 id=\"b\">Two</h2><p>Beta<em id=\"c\"> gamma</em><a id=\"d\"></a></p>" +
                    "<div><img alt=\"A picture\"/></div><div id=\"e\"></div><p>Delta</p><hgroup><h2>Part</h2><h3 id=\"f\">Three</h3></hgroup><p>End</p><a id=\"g\"/>",
            ),
            nav = nav(*"abcdefg".map { li(it.toString(), "text/c0.xhtml#$it") }.toTypedArray()),
        )
        val end = SpinePoint(0, book.spineItems[0].text.length)
        assertEquals(listOf("One", "Two", "gamma", "A picture", "Delta", "Three").map { book.startOf(it) } + end, book.chapters.map { it.start })
    }

    @Test
    fun `a Chapter runs across Spine items until the next Chapter`() {
        val book = open(
            listOf("<p>Front</p><h2 id=\"one\">One</h2><p>Alpha</p>", "<p>Still one</p>", "<h2 id=\"two\">Two</h2><p>Beta</p>"),
            nav = nav(li("One", "text/c0.xhtml#one"), li("Two", "text/c2.xhtml#two")),
        )
        assertEquals(0, book.chapterAt(book.startOf("Alpha")))
        assertEquals(0, book.chapterAt(book.startOf("Still one")))
        assertEquals(0, book.chapterAt(SpinePoint(1, book.spineItems[1].text.length)))
        assertEquals(1, book.chapterAt(book.startOf("Two")))
    }

    @Test
    fun `an unlisted Spine item continues the Chapter before it`() {
        val book = open(
            listOf("<p>One</p>", "<p>Two</p>", "<p>Illustration</p>", "<p>Three</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("Two", "text/c1.xhtml"), li("Three", "text/c3.xhtml")),
        )
        assertEquals(1, book.chapterAt(SpinePoint(2, 0)))
        assertEquals(2, book.chapterAt(SpinePoint(3, 0)))
    }

    @Test
    fun `text before the first Chapter is front matter`() {
        val book = open(
            listOf("<p>Title page</p>", "<p>Copyright</p>", "<p>Preface</p>", "<p>One</p>"),
            nav = nav(li("Preface", "text/c2.xhtml"), li("One", "text/c3.xhtml")),
        )
        assertNull(book.chapterAt(SpinePoint(0, 0)))
        assertNull(book.chapterAt(SpinePoint(1, 4)))
        assertEquals(0, book.chapterAt(SpinePoint(2, 0)))
    }

    private val unlisted = listOf("<h1>The <br/>Beginning</h1><p>One</p>", "<p>No heading</p>", "<p>Before</p><h2>Later</h2>")
    private val perSpineItem = listOf(Chapter("The Beginning", SpinePoint(0, 0)), Chapter("Chapter 2", SpinePoint(1, 0)), Chapter("Later", SpinePoint(2, 0)))

    @Test
    fun `with no table of contents, each Spine item is a Chapter titled by its first heading, else Chapter N`() {
        val book = open(unlisted)
        assertEquals(perSpineItem, book.chapters)
        assertEquals(0, book.chapterAt(SpinePoint(0, 0)))
    }

    @Test
    fun `a table of contents with one entry counts as none`() {
        assertEquals(perSpineItem, open(unlisted, nav = nav(li("Everything", "text/c0.xhtml"))).chapters)
    }

    @Test
    fun `a table of contents out of Spine order counts as none`() {
        val nav = nav(li("One", "text/c0.xhtml"), li("Three", "text/c2.xhtml"), li("Two", "text/c1.xhtml"))
        assertEquals(perSpineItem, open(unlisted, nav = nav).chapters)
    }

    @Test
    fun `entries that don't resolve are dropped first, so with none left the table counts as none`() {
        val missing = listOf(li("Gone", "text/gone.xhtml"), li("Outside", "http://example.org/c0.xhtml"), li("Package", "content.opf"), li("Unlinked", null))
        assertEquals(perSpineItem, open(unlisted, nav = nav(*missing.toTypedArray())).chapters)
        val two = nav(*(missing + li("One", "text/c0.xhtml") + li("Three", "text/c2.xhtml")).toTypedArray())
        assertEquals(listOf("One", "Three"), open(unlisted, nav = two).chapters.map { it.title })
    }

    @Test
    fun `an empty label takes the Chapter's first heading, else Chapter N`() {
        val book = open(unlisted, nav = nav(li("", "text/c0.xhtml"), li(" ", "text/c1.xhtml"), li("<b> </b>", "text/c2.xhtml")))
        assertEquals(perSpineItem, book.chapters)
    }

    @Test
    fun `a fragment the Spine item doesn't have starts the Chapter at the Spine item's start`() {
        val book = open(listOf("<p>One</p>", "<p>Two</p>"), nav = nav(li("One", "text/c0.xhtml"), li("Two", "text/c1.xhtml#nowhere")))
        assertEquals(SpinePoint(1, 0), book.chapters[1].start)
    }

    @Test
    fun `an unknown fragment after a resolved one in its Spine item starts at that one's start`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2 id=\"two\">Two</h2><p>More</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("Two", "text/c1.xhtml#two"), li("Three", "text/c1.xhtml#nowhere")),
        )
        assertEquals(listOf("One", "Two", "Three"), book.chapters.map { it.title })
        assertEquals(book.startOf("Two"), book.chapters[2].start)
    }

    @Test
    fun `a caption inside a heading is its own block, and the label ending with the heading is titled by it`() {
        val book = open(
            listOf(
                "<p>One</p>",
                "<h2 id=\"c2\"><span class=\"caption\">X.</span><br/><br/>CHAPTER II.</h2><p>Two</p>",
            ),
            nav = nav(li("One", "text/c0.xhtml"), li("X. CHAPTER II.", "text/c1.xhtml#c2")),
        )
        assertEquals(listOf(Block(BlockKind.Caption, "X."), Block(BlockKind.Heading, "CHAPTER II.")), book.spineItems[1].blocks.take(2))
        assertEquals(Chapter("CHAPTER II.", SpinePoint(1, 0)), book.chapters[1])
    }

    @Test
    fun `a heading's leading line breaks are dropped, and an id before them points at its first character`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2><span id=\"c4\"></span><br/><br/>CHAPTER IV.<br/></h2>"),
            nav = nav(li("One", "text/c0.xhtml"), li("CHAPTER IV.", "text/c1.xhtml#c4")),
        )
        assertEquals(Block(BlockKind.Heading, "CHAPTER IV."), book.spineItems[1].blocks[1])
        assertEquals(book.startOf("CHAPTER IV."), book.chapters[1].start)
    }

    @Test
    fun `a label is titled by the heading only when it has more before it`() {
        val book = open(
            listOf("<h2>One</h2>", "<h2>Chapter Two</h2>", "<h2>Three</h2>"),
            nav = nav(li("The First", "text/c0.xhtml"), li("Chapter Two", "text/c1.xhtml"), li("Part Three", "text/c2.xhtml")),
        )
        assertEquals(listOf("The First", "Chapter Two", "Three"), book.chapters.map { it.title })
    }

    @Test
    fun `hrefs are percent-decoded, fragments too`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2 id=\"café\">Two</h2>"),
            nav = nav(li("One", "text/c%200.xhtml"), li("Two", "text/c%201.xhtml#caf%C3%A9")),
            names = listOf("c 0.xhtml", "c 1.xhtml"),
        )
        assertEquals(listOf(SpinePoint(0, 0), book.startOf("Two")), book.chapters.map { it.start })
    }

    @Test
    fun `chapterAt finds the Chapter at its first character, not the one before, up to the end of the Book`() {
        val book = open(
            listOf("<p>Front</p><h2 id=\"a\">One</h2>", "<p>Alpha</p><h2 id=\"b\">Two</h2><p>Beta</p>"),
            nav = nav(li("One", "text/c0.xhtml#a"), li("Two", "text/c1.xhtml#b")),
        )
        val two = book.startOf("Two")
        assertNull(book.chapterAt(SpinePoint(0, 0)))
        assertNull(book.chapterAt(SpinePoint(0, book.startOf("One").char - 1)))
        assertEquals(0, book.chapterAt(book.startOf("One")))
        assertEquals(0, book.chapterAt(two.copy(char = two.char - 1)))
        assertEquals(1, book.chapterAt(two))
        assertEquals(1, book.chapterAt(SpinePoint(1, book.spineItems[1].text.length)))
    }

    @Test
    fun `of two Chapters starting at the same point, the later holds it`() {
        val book = OpenBook("id", "", emptyList(), chapters = listOf(Chapter("A", SpinePoint(0, 0)), Chapter("B", SpinePoint(0, 0)), Chapter("C", SpinePoint(0, 5))))
        assertEquals(1, book.chapterAt(SpinePoint(0, 4)))
        assertEquals(2, book.chapterAt(SpinePoint(0, 5)))
    }

    @Test
    fun `Alice's twelve Chapters come from its nav, one per Spine item`() {
        val alice = parseEpub(File("src/test/fixtures/alice.epub"))
        assertEquals(12, alice.chapters.size)
        assertEquals(alice.spineItems.indices.map { SpinePoint(it, 0) }, alice.chapters.map { it.start })
        assertEquals("VII: A Mad Tea-Party", alice.chapters[6].title)
    }
}
