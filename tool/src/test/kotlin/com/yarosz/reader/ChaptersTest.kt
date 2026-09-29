package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chapters from a Book's table of contents (ADR 0004), and which Chapter a SpinePoint is in. */
class ChaptersTest {

    private val dir: File = createTempDirectory("chapters").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun open(bodies: List<String>, nav: String? = null, ncx: String? = null, names: List<String> = bodies.indices.map { "c$it.xhtml" }) =
        parseEpub(epub(bodies, nav, ncx, names))

    private fun epub(bodies: List<String>, nav: String? = null, ncx: String? = null, names: List<String> = bodies.indices.map { "c$it.xhtml" }, navName: String = "nav.xhtml") =
        File(dir, "book.epub").writeEpub(tocEpubFiles(bodies, nav, ncx, names, navName))

    private fun nav(vararg items: String) =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>""" +
            """<nav epub:type="landmarks"><ol><li><a href="text/c0.xhtml">Landmark</a></li></ol></nav>""" +
            """<nav epub:type="toc"><h2>Table of Contents</h2><ol>${items.joinToString("")}</ol></nav></body></html>"""

    private fun li(label: String, href: String?, vararg children: String) =
        "<li>" + (if (href != null) "<a href=\"$href\">$label</a>" else "<span>$label</span>") +
            (if (children.isEmpty()) "" else "<ol>${children.joinToString("")}</ol>") + "</li>"

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
        assertEquals(listOf(Block(BlockKind.Caption, "X.", headingCaption = true), Block(BlockKind.Heading, "CHAPTER II.")), book.spineItems[1].blocks.take(2))
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
    fun `a label is titled by the heading only after a caption, and only when it has more before the heading`() {
        val caption = "<span class=\"caption\">A caption.</span><br/>"
        val book = open(
            listOf("<h2>One</h2>", "<h2>1</h2>", "<h2>Night</h2>", "<h2>${caption}Four</h2>", "<h2>${caption}Five</h2>", "<h2>${caption}Six</h2>"),
            nav = nav(
                li("The First", "text/c0.xhtml"),
                li("Chapter 1", "text/c1.xhtml"),
                li("Part Two: The Long Night", "text/c2.xhtml"),
                li("A caption. Four", "text/c3.xhtml"),
                li("Five", "text/c4.xhtml"),
                li("Chapter Six", "text/c5.xhtml"),
            ),
        )
        assertEquals(listOf("The First", "Chapter 1", "Part Two: The Long Night", "Four", "Five", "Six"), book.chapters.map { it.title })
    }

    @Test
    fun `Pride and Prejudice's CHAPTER III heading gives a caption, then the heading, with both ids at the caption`() {
        val bodies = listOf(
            "<p>One</p>",
            "<p>Front</p><h2 id=\"x\"><span id=\"y\"></span><br/><span class=\"caption\">He rode a black horse.</span><br/><br/>CHAPTER III.</h2><p>Not all.</p>",
        )
        for (id in listOf("x", "y")) {
            val book = open(bodies, nav = nav(li("One", "text/c0.xhtml"), li("He rode a black horse. CHAPTER III.", "text/c1.xhtml#$id")))
            assertEquals(listOf(Block(BlockKind.Caption, "He rode a black horse.", headingCaption = true), Block(BlockKind.Heading, "CHAPTER III.")), book.spineItems[1].blocks.subList(1, 3))
            assertEquals(Chapter("CHAPTER III.", book.startOf("He rode")), book.chapters[1])
        }
    }

    @Test
    fun `a caption after the heading's own text stays in the heading, and the Chapter starts at the heading`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2 id=\"q\">CHAPTER V. <span class=\"caption\">Cap</span></h2><p>Five</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("Five", "text/c1.xhtml#q")),
        )
        assertEquals(Block(BlockKind.Heading, "CHAPTER V. Cap"), book.spineItems[1].blocks[1])
        assertEquals(book.startOf("CHAPTER V."), book.chapters[1].start)
    }

    @Test
    fun `a caption keeps its emphasis, whether it is the emphasis element or inside one`() {
        val book = open(listOf(
            "<h2><i class=\"caption\">Cap one</i><br/>CHAPTER I.</h2>" +
                "<h2><em><span class=\"caption\">Cap two</span><br/>CHAPTER</em> II.</h2>",
        ))
        assertEquals(
            listOf(
                Block(BlockKind.Caption, "Cap one", listOf(Span(0, 7, Emphasis.Italic)), headingCaption = true),
                Block(BlockKind.Heading, "CHAPTER I."),
                Block(BlockKind.Caption, "Cap two", listOf(Span(0, 7, Emphasis.Italic)), headingCaption = true),
                Block(BlockKind.Heading, "CHAPTER II.", listOf(Span(0, 7, Emphasis.Italic))),
            ),
            book.spineItems[0].blocks,
        )
    }

    @Test
    fun `a caption inside an hgroup stays in the heading, and ids after it keep their offsets`() {
        val book = open(
            listOf("<p>One</p>", "<hgroup><h2><span class=\"caption\">Cap</span> One</h2><h3 id=\"z\">Two</h3></hgroup><p id=\"w\">Text</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("Z", "text/c1.xhtml#z"), li("W", "text/c1.xhtml#w")),
        )
        assertEquals(listOf(Block(BlockKind.Heading, "Cap One: Two"), Block(BlockKind.Paragraph, "Text")), book.spineItems[1].blocks)
        assertEquals(listOf(book.startOf("Two"), book.startOf("Text")), book.chapters.drop(1).map { it.start })
    }

    @Test
    fun `a Chapter starting inside a heading is titled by that heading`() {
        val book = open(
            listOf("<p>One</p>", "<hgroup><h2>Part</h2><h3 id=\"f\">Three</h3></hgroup><p>Text</p>", "<h2>CHAPTER <span id=\"s\">VI.</span></h2><p>Six</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("", "text/c1.xhtml#f"), li("", "text/c2.xhtml#s")),
        )
        assertEquals(listOf("One", "Part: Three", "CHAPTER VI."), book.chapters.map { it.title })
    }

    @Test
    fun `a whitespace-only element's id goes to the next text`() {
        val book = open(
            listOf("<p>One</p>", "<p>Hello<span id=\"x\">&#160;</span></p><p>World</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("X", "text/c1.xhtml#x")),
        )
        assertEquals(book.startOf("World"), book.chapters[1].start)
    }

    @Test
    fun `a no-break space doesn't keep a heading's leading line breaks`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2 id=\"j\">&#160;<br/><br/>CHAPTER IV.&#160;</h2>"),
            nav = nav(li("One", "text/c0.xhtml"), li("CHAPTER IV.", "text/c1.xhtml#j")),
        )
        assertEquals(Block(BlockKind.Heading, "CHAPTER IV."), book.spineItems[1].blocks[1])
        assertEquals(book.startOf("CHAPTER IV."), book.chapters[1].start)
    }

    @Test
    fun `an id on an element starting with no-break spaces points at the first of them`() {
        val book = open(
            listOf("<p>One</p>", "<p>Front</p><h2 id=\"c1\">&#160;CHAPTER I.</h2><p>Text</p><p id=\"p\">&#160;&#160;Indented</p>"),
            nav = nav(li("One", "text/c0.xhtml"), li("CHAPTER I.", "text/c1.xhtml#c1"), li("P", "text/c1.xhtml#p")),
        )
        val spineItem = book.spineItems[1]
        assertEquals(listOf("\u00A0CHAPTER I.", "\u00A0\u00A0Indented"), listOf(spineItem.blocks[1].text, spineItem.blocks[3].text))
        assertEquals(listOf(SpinePoint(1, spineItem.blockStarts[1]), SpinePoint(1, spineItem.blockStarts[3])), book.chapters.drop(1).map { it.start })
        assertEquals(1, book.chapterAt(SpinePoint(1, spineItem.blockStarts[1])))
    }

    @Test
    fun `a split caption keeps with its heading, and an image caption before a heading doesn't`() {
        val spineItem = open(listOf("<p>Text</p><img alt=\"A figure.\" src=\"f.png\"/><h2><span class=\"caption\">Cap.</span><br/>CHAPTER I.</h2><p>More</p>")).spineItems[0]
        assertEquals(
            listOf(Block(BlockKind.Caption, "A figure."), Block(BlockKind.Caption, "Cap.", headingCaption = true), Block(BlockKind.Heading, "CHAPTER I.")),
            spineItem.blocks.subList(1, 4),
        )
        assertEquals(listOf(false, false, true, true, false), spineItem.blockStarts.map { spineItem.keepsWithNext(it) })
    }

    @Test
    fun `a Chapter starting at the end of a Spine item takes its heading from what follows`() {
        val book = open(
            listOf("<p>One</p>", "<h2>Last</h2><div id=\"end\"></div>", "<p>More</p><h2>Next</h2>"),
            nav = nav(li("One", "text/c0.xhtml"), li("", "text/c1.xhtml#end")),
        )
        assertEquals(SpinePoint(1, book.spineItems[1].text.length), book.chapters[1].start)
        assertEquals("Next", book.chapters[1].title)
    }

    @Test
    fun `a nav link inside a span label gives the entry its href`() {
        val book = open(
            listOf("<p>One</p>", "<p>Two</p>"),
            nav = nav(
                "<li><span class=\"toc\"><a href=\"text/c0.xhtml\">Loomings</a></span></li>",
                "<li><span class=\"toc\"><a href=\"text/c1.xhtml\">The Carpet-Bag</a></span></li>",
            ),
        )
        assertEquals(listOf(Chapter("Loomings", SpinePoint(0, 0)), Chapter("The Carpet-Bag", SpinePoint(1, 0))), book.chapters)
    }

    @Test
    fun `a nav entry's link wins over a span before it`() {
        val numbered = "<li><span class=\"num\">1.</span> <a href=\"text/c0.xhtml\">Loomings</a></li>" +
            "<li><span class=\"num\">2.</span> <a href=\"text/c1.xhtml\">The Carpet-Bag</a></li>"
        val book = open(listOf("<p>One</p>", "<p>Two</p>"), nav = nav(numbered))
        assertEquals(listOf(Chapter("Loomings", SpinePoint(0, 0)), Chapter("The Carpet-Bag", SpinePoint(1, 0))), book.chapters)
    }

    private val ncxChapters = ncx(navPoint("NCX one", "text/c0.xhtml"), navPoint("NCX two", "text/c1.xhtml"))

    @Test
    fun `a nav that gives no entry, or none that resolves, or has no toc nav gives way to the NCX`() {
        val bodies = listOf("<p>One</p>", "<p>Two</p>")
        val landmarksOnly = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>""" +
            """<nav epub:type="landmarks"><ol><li><a href="text/c0.xhtml">Start</a></li></ol></nav></body></html>"""
        for (nav in listOf(nav(), nav(li("Gone", "text/gone.xhtml"), li("Also gone", "text/c9.xhtml")), landmarksOnly)) {
            assertEquals(listOf("NCX one", "NCX two"), open(bodies, nav = nav, ncx = ncxChapters).chapters.map { it.title })
        }
    }

    @Test
    fun `an NCX that doesn't parse counts as no table of contents`() {
        assertEquals(perSpineItem, open(unlisted, ncx = ncxChapters.substringBefore("</navMap>")).chapters)
    }

    /**
     * Opens [epub] once to warm up, then again, which must take under five seconds: each case takes well under
     * one, and over fifteen with the quadratic step it guards, so the bound holds on a machine busy with emulators.
     */
    private fun openQuickly(epub: File): OpenBook {
        parseEpub(epub)
        val started = System.nanoTime()
        val book = parseEpub(epub)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 5_000, "took $millis ms")
        return book
    }

    @Test
    fun `thousands of anchored blocks in one Spine item, each with an entry, open quickly`() {
        val n = 45_000
        val body = (0 until n).joinToString("") { "<div id=\"e$it\"></div><p>&#160;</p><h2 id=\"c$it\">$it</h2>" }
        val entries = (0 until n).flatMap { listOf(li("", "text/c1.xhtml#e$it"), li("", "text/c1.xhtml#c$it")) }
        val book = openQuickly(epub(listOf("<p>One</p>", body), nav = nav(li("One", "text/c0.xhtml"), *entries.toTypedArray())))
        assertEquals(2 * n + 1, book.chapters.size)
        assertEquals("${n - 1}", book.chapters.last().title)
    }

    @Test
    fun `an hgroup of many parts opens quickly`() {
        val n = 150_000
        val body = "<hgroup>" + (0 until n).joinToString("") { "<h2 id=\"h$it\">$it</h2>" } + "</hgroup>"
        val book = openQuickly(epub(listOf("<p>One</p>", body), nav = nav(li("One", "text/c0.xhtml"), li("Last", "text/c1.xhtml#h${n - 1}"))))
        assertEquals(book.spineItems[1].text.length - "${n - 1}".length, book.chapters.last().start.char)
    }

    @Test
    fun `a heading of many blank children opens quickly`() {
        val n = 150_000
        val body = "<h2 id=\"h\">" + "<span>&#160;</span>".repeat(n) + "CHAPTER I.</h2><p>Text</p>"
        val book = openQuickly(epub(listOf("<p>One</p>", body), nav = nav(li("One", "text/c0.xhtml"), li("I", "text/c1.xhtml#h"))))
        assertEquals(SpinePoint(1, 0), book.chapters.last().start)
    }

    @Test
    fun `a blank caption leaves the heading's ids with it, and a caption-only heading's caption doesn't keep with the next`() {
        val book = open(
            listOf(
                "<p>One</p>",
                "<p>Front</p><h2 id=\"c\">&#160;<span class=\"caption\"> </span>CHAPTER III.</h2><p>Text</p>" +
                    "<h2 id=\"d\"><span class=\"caption\">Fig.</span></h2><h2 id=\"e\">Two</h2><p>More</p>",
            ),
            nav = nav(li("One", "text/c0.xhtml"), li("III", "text/c1.xhtml#c"), li("Fig", "text/c1.xhtml#d"), li("Two", "text/c1.xhtml#e")),
        )
        val spineItem = book.spineItems[1]
        assertEquals("\u00A0CHAPTER III.", spineItem.blocks[1].text)
        assertEquals(SpinePoint(1, spineItem.blockStarts[1]), book.chapters[1].start)
        val fig = spineItem.blocks.indexOfFirst { it.text == "Fig." }
        assertEquals(BlockKind.Caption, spineItem.blocks[fig].kind)
        assertEquals(false, spineItem.isHeadingCaption(fig))
    }

    @Test
    fun `thousands of Spine items, each entry naming the last, open quickly`() {
        val items = 2_500
        val entries = 100_000
        val dir = "d".repeat(500)
        val names = List(items) { "$dir/c%05d.xhtml".format(it) }
        val nav = nav(*Array(entries) { li("x", names.last().substringAfter('/')) })
        val book = openQuickly(epub(List(items) { "<p>$it</p>" }, nav = nav, names = names, navName = "text/$dir/nav.xhtml"))
        assertEquals(entries, book.chapters.size)
    }

    @Test
    fun `ids waiting for text across half a million blank blocks open quickly`() {
        val ids = 5_000
        val body = (0 until ids).joinToString("") { "<div id=\"g$it\"></div>" } + "<p>&#160;</p>".repeat(500_000) + "<h2>End</h2>"
        val book = openQuickly(epub(listOf("<p>One</p>", body), nav = nav(li("One", "text/c0.xhtml"), *Array(ids) { li("G$it", "text/c1.xhtml#g$it") })))
        assertEquals(List(ids) { SpinePoint(1, 0) }, book.chapters.drop(1).map { it.start })
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
        assertEquals("II: The Pool of Tears", alice.chapterAt(SpinePoint(1, 0))?.let { alice.chapters[it].title }, "the top line scripts/ci.sh reads at the dev start")
    }
}
