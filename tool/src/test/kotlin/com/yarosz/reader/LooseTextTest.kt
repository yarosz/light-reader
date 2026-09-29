package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Text outside the parser's known blocks: loose text in a `<div>`, tables, and `<pre>`. */
class LooseTextTest {

    private val dir: File = createTempDirectory("loose-text").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun open(bodies: List<String>, ncx: String? = null) =
        parseEpub(File(dir, "book.epub").writeEpub(tocEpubFiles(bodies, ncx = ncx)))

    private fun blocksOf(body: String) = open(listOf(body)).spineItems.single().blocks

    @Test
    fun `text directly in a div is a paragraph of its own, and a block nested in a div is read once`() {
        val blocks = blocksOf(
            "<div>This eBook is for the use of anyone anywhere.</div>" +
                "<div><p>Inside a paragraph.</p>Then loose <b>text</b> after it.<h2>Heading</h2></div>" +
                "<div>Before<div>nested</div>after</div>",
        )
        assertEquals(
            listOf(
                Block(BlockKind.Paragraph, "This eBook is for the use of anyone anywhere."),
                Block(BlockKind.Paragraph, "Inside a paragraph."),
                Block(BlockKind.Paragraph, "Then loose text after it.", listOf(Span(11, 15, Emphasis.Bold))),
                Block(BlockKind.Heading, "Heading"),
                Block(BlockKind.Paragraph, "Before"),
                Block(BlockKind.Paragraph, "nested"),
                Block(BlockKind.Paragraph, "after"),
            ),
            blocks,
        )
    }

    @Test
    fun `loose text keeps its line breaks, the emphasis around it, and its image captions in order`() {
        val blocks = blocksOf(
            "<div class=\"pgmonospaced\">  a   b   c\n<br/>  1   2   3\n<br/></div>" +
                "<div><i>All of it <span>italic</span></i></div>" +
                "<div>Before the picture<img alt=\"A picture\"/>after it</div>" +
                "<blockquote>Quoted <em>loosely</em>.</blockquote>",
        )
        assertEquals(
            listOf(
                Block(BlockKind.Paragraph, "a b c\n1 2 3"),
                Block(BlockKind.Paragraph, "All of it italic", listOf(Span(0, 16, Emphasis.Italic))),
                Block(BlockKind.Paragraph, "Before the picture"),
                Block(BlockKind.Caption, "A picture"),
                Block(BlockKind.Paragraph, "after it"),
                Block(BlockKind.Paragraph, "Quoted loosely.", listOf(Span(7, 14, Emphasis.Italic))),
            ),
            blocks,
        )
    }

    @Test
    fun `loose text in verse is verse`() {
        assertEquals(listOf(Block(BlockKind.Verse, "Line one\nLine two")), blocksOf("<div class=\"poem\">Line one<br/>Line two</div>"))
    }

    @Test
    fun `a table is one block, a line per row, its cells joined by a middle dot and empty ones skipped`() {
        val blocks = blocksOf(
            "<h2>Contents</h2><table summary=\"\"><tbody>" +
                "<tr><td> <a href=\"#c1\"> <b>BOOK ONE: 1805</b></a></td></tr>" +
                "<tr><td> <a href=\"#c1\"> CHAPTER I</a></td><td></td><td>5</td></tr>" +
                "<tr><th>A</th><th>B</th></tr>" +
                "<tr><td><p>One</p><p>Two</p></td><td><table><tr><td>inner</td></tr></table></td></tr>" +
                "</tbody></table><p>After</p>",
        )
        assertEquals(
            listOf(
                Block(BlockKind.Heading, "Contents"),
                Block(BlockKind.Paragraph, "BOOK ONE: 1805\nCHAPTER I · 5\nA · B\nOne Two · inner", listOf(Span(0, 14, Emphasis.Bold))),
                Block(BlockKind.Paragraph, "After"),
            ),
            blocks,
        )
    }

    @Test
    fun `a pre keeps its line breaks`() {
        assertEquals(listOf(Block(BlockKind.Paragraph, "line one\nline two")), blocksOf("<pre>\nline one\n   line two\n</pre>"))
    }

    @Test
    fun `a Chapter listed at an id on loose text or a table starts at that text`() {
        val book = open(
            listOf("<p>Title</p><div id=\"toc\"><h2>Contents</h2><table><tr><td>One</td></tr></table></div>", "<div id=\"one\">Loose first line.</div><p>More</p>"),
            ncx(navPoint("Title", "text/c0.xhtml"), navPoint("Contents", "text/c0.xhtml#toc"), navPoint("One", "text/c1.xhtml#one")),
        )
        assertEquals(listOf(SpinePoint(0, 0), SpinePoint(0, 6), SpinePoint(1, 0)), book.chapters.map { it.start })
        assertEquals("One", book.spineItems[0].text.substring(book.spineItems[0].blockStarts[2]))
    }
}
