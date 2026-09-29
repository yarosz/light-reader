package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `a table of inline text is one block, a line per row, its cells joined by a middle dot and empty ones skipped`() {
        val blocks = blocksOf(
            "<h2>Contents</h2><table summary=\"\"><tbody>" +
                "<tr><td> <a href=\"#c1\"> <b>BOOK ONE: 1805</b></a></td></tr>" +
                "<tr><td> <a href=\"#c1\"> CHAPTER I</a></td><td></td><td>5</td></tr>" +
                "<tr><td>CHAPTER II</td><td>&#160;</td><td>9</td><td> &#160; </td></tr>" +
                "<tr><th>A</th><th>B</th></tr>" +
                "<tr><td>One <i>Two</i></td><td><table><tr><td>inner</td><td>cells</td></tr></table></td></tr>" +
                "</tbody></table><p>After</p>",
        )
        assertEquals(
            listOf(
                Block(BlockKind.Heading, "Contents"),
                Block(
                    BlockKind.Paragraph,
                    "BOOK ONE: 1805\nCHAPTER I · 5\nCHAPTER II · 9\nA · B\nOne Two · inner cells",
                    listOf(Span(0, 14, Emphasis.Bold), Span(54, 57, Emphasis.Italic)),
                ),
                Block(BlockKind.Paragraph, "After"),
            ),
            blocks,
        )
    }

    @Test
    fun `a table whose cells hold blocks, headings or captioned images is read block by block`() {
        val blocks = blocksOf(
            "<table><tr><td><h3>Heading</h3><p>One</p><p>Two</p></td><td><img alt=\"A picture\"/>loose</td></tr>" +
                "<tr><td><pre>a\n  b</pre></td><td>Plain cell</td></tr></table><p>After</p>",
        )
        assertEquals(
            listOf(
                Block(BlockKind.Heading, "Heading"),
                Block(BlockKind.Paragraph, "One"),
                Block(BlockKind.Paragraph, "Two"),
                Block(BlockKind.Caption, "A picture"),
                Block(BlockKind.Paragraph, "loose"),
                Block(BlockKind.Paragraph, "a\nb"),
                Block(BlockKind.Paragraph, "Plain cell"),
                Block(BlockKind.Paragraph, "After"),
            ),
            blocks,
        )
    }

    @Test
    fun `a table longer than a window is cut into blocks at rows once a block passes the window size`() {
        val rows = (1..600).map { "Row $it of a long table · ${"x".repeat(60)}" }
        val blocks = blocksOf("<table>" + rows.joinToString("") { row -> "<tr>" + row.split(" · ").joinToString("") { "<td>$it</td>" } + "</tr>" } + "</table>")
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length < WINDOW_CHARS + rows.maxOf { row -> row.length } + 1 }, blocks.map { it.text.length }.toString())
        assertEquals(rows, blocks.flatMap { it.text.split('\n') })
    }

    @Test
    fun `emphasis around a long table's rows ends where it closes, in whichever of the table's blocks that is`() {
        fun rows(range: IntRange) = range.joinToString("") { "<tr><td>Row $it</td><td>${"x".repeat(60)}</td></tr>" }
        val whole = blocksOf("<table><tbody><i>${rows(1..300)}</i></tbody></table><p>After.</p>")
        assertTrue(whole.size > 2)
        assertTrue(whole.dropLast(1).all { it.spans == listOf(Span(0, it.text.length, Emphasis.Italic)) })
        assertEquals(Block(BlockKind.Paragraph, "After."), whole.last())

        val part = blocksOf("<table><tbody><i>${rows(1..200)}</i>${rows(201..300)}</tbody></table><p>After.</p>")
        val closing = part.indexOfFirst { "Row 200 ·" in it.text }
        assertTrue(closing > 0)
        assertEquals(listOf(Span(0, part[closing].text.indexOf("Row 201") - 1, Emphasis.Italic)), part[closing].spans)
        assertTrue(part.drop(closing + 1).all { it.spans.isEmpty() })
    }

    @Test
    fun `a table inside a known block adds a line per row and separates its cells`() {
        assertEquals(
            listOf(Block(BlockKind.Paragraph, "x\na · b\nc\ny"), Block(BlockKind.Paragraph, "a · b")),
            blocksOf("<p>x<table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>y</p><ul><li><table><tr><td>a</td><td>b</td></tr></table></li></ul>"),
        )
    }

    @Test
    fun `each block in a list item is a block of its own, as is a nested list's item`() {
        assertEquals(
            listOf(
                Block(BlockKind.Paragraph, "One."),
                Block(BlockKind.Paragraph, "Two."),
                Block(BlockKind.Paragraph, "Plain item"),
                Block(BlockKind.Paragraph, "Item"),
                Block(BlockKind.Paragraph, "Sub"),
                Block(BlockKind.Paragraph, "Term"),
                Block(BlockKind.Paragraph, "Said."),
                Block(BlockKind.Paragraph, "More."),
            ),
            blocksOf(
                "<ol><li id=\"note-1\"><p>One.</p><p>Two.</p></li><li>Plain item</li><li>Item<ul><li>Sub</li></ul></li></ol>" +
                    "<dl><dt>Term</dt><dd><p>Said.</p><p>More.</p></dd></dl>",
            ),
        )
    }

    @Test
    fun `emphasis around loose text survives the blocks and images that cut it into runs`() {
        assertEquals(
            listOf(
                Block(BlockKind.Paragraph, "loose1", listOf(Span(0, 6, Emphasis.Italic))),
                Block(BlockKind.Paragraph, "para", listOf(Span(0, 4, Emphasis.Italic))),
                Block(BlockKind.Paragraph, "loose2", listOf(Span(0, 6, Emphasis.Italic))),
            ),
            blocksOf("<div><i>loose1<p>para</p>loose2</i></div>"),
        )
        assertEquals(
            listOf(
                Block(BlockKind.Paragraph, "bold x", listOf(Span(0, 6, Emphasis.Bold), Span(5, 6, Emphasis.Italic))),
                Block(BlockKind.Caption, "p"),
                Block(BlockKind.Paragraph, "tail", listOf(Span(0, 4, Emphasis.Bold))),
            ),
            blocksOf("<div><b>bold <i>x<img alt=\"p\"/></i> tail</b></div>"),
        )
        assertEquals(
            listOf(Block(BlockKind.Paragraph, "Italic words after", listOf(Span(0, 18, Emphasis.Italic)))),
            blocksOf("<div><i>Italic words <img src=\"x.png\" alt=\"\"/> after</i></div>"),
        )
    }

    @Test
    fun `old inline elements stay in the run of text, and hidden, svg, math, noscript and template text shows nothing`() {
        assertEquals(
            listOf(Block(BlockKind.Paragraph, "A big NASA no break label blink tt s u font end")),
            blocksOf(
                "<div>A <big>big</big> <acronym>NASA</acronym> <nobr>no break</nobr> <label>label</label> <blink>blink</blink> " +
                    "<tt>tt</tt> <s>s</s> <u>u</u> <font>font</font><basefont size=\"3\"/> end</div>",
            ),
        )
        assertEquals(
            listOf(Block(BlockKind.Paragraph, "Shown after"), Block(BlockKind.Paragraph, "Para.")),
            blocksOf(
                "<div>Shown <svg xmlns=\"http://www.w3.org/2000/svg\"><text>svg text</text></svg><math><mi>x</mi></math>" +
                    "<noscript>no script</noscript><template>template</template><span hidden=\"hidden\">hidden</span>" +
                    "<span aria-hidden=\"true\">aria</span>after</div><p>Para<span hidden=\"\">hidden</span>.</p>" +
                    "<table hidden=\"hidden\"><tr><td>hidden table</td></tr></table>",
            ),
        )
    }

    @Test
    fun `a Place saved before loose text and tables reached a Page is found again by its text`() {
        val dialogue = listOf("Alice was beginning to get very tired.", "“Yes.”", "“No.”", "“Why not?”", "The end of the chapter, long enough to end it.")
        val before = dialogue.joinToString("") { "<p>$it</p>" }
        val after = dialogue.mapIndexed { i, line -> (if (i in 1..3) "<div class=\"illus\">[Illustration $i]</div>" else "") + "<p>$line</p>" }.joinToString("")
        assertPlacesFound(before, after, dialogue.flatMap { listOf(it to 0, it to 2) })

        val rest = "<p>“Yes.”</p><p>“No.”</p><p>“Why not?”</p><p>The end of the chapter, long enough to end it.</p>"
        val table = "<h2>Contents</h2><table><tr><td>CHAPTER I</td><td>5</td></tr><tr><td>CHAPTER II</td><td>9</td></tr></table>$rest"
        assertPlacesFound("<h2>Contents</h2>$rest", table, listOf("Contents", "“Yes.”", "“No.”", "“Why not?”").flatMap { listOf(it to 0, it to 3) })
    }

    @Test
    fun `a Place in a line whose end another line repeats is found at its own line when a block now follows it`() {
        val lines = listOf("He said yes.", "A long line of narration, long enough to push the next reply away.", "She said yes.", "Next paragraph goes on for a while.")
        val before = lines.joinToString("") { "<p>$it</p>" }
        val after = lines.mapIndexed { i, line -> (if (i % 2 == 1) "<div class=\"illus\">[Illustration $i, with a caption]</div>" else "") + "<p>$line</p>" }
            .joinToString("")
        assertPlacesFound(before, after, lines.flatMap { line -> line.indices.map { line to it } })
    }

    @Test
    fun `a Place saved when a list item's paragraphs, a definition's paragraphs or a nested list ran together is found at its text`() {
        val notes = listOf("Apples grow on trees." to "They ripen in autumn.", "Bees make honey." to "It keeps for years.", "Cats sleep a lot." to "Mostly by day.")
        assertOldPlacesFound(
            listOf("Notes.") + notes.map { (a, b) -> a + b } + "After the list.",
            "<p>Notes.</p><ol>" + notes.joinToString("") { (a, b) -> "<li><p>$a</p><p>$b</p></li>" } + "</ol><p>After the list.</p>",
        )
        assertOldPlacesFound(
            notes.flatMapIndexed { i, (a, b) -> listOf("Term ${i + 1}", a + b) } + "Closing prose.",
            "<dl>" + notes.withIndex().joinToString("") { (i, n) -> "<dt>Term ${i + 1}</dt><dd><p>${n.first}</p><p>${n.second}</p></dd>" } +
                "</dl><p>Closing prose.</p>",
        )
        assertOldPlacesFound(
            listOf("Before.", "Fruit growsapplepear", "Nuts fallwalnutpecan", "After."),
            "<p>Before.</p><ul><li>Fruit grows<ul><li>apple</li><li>pear</li></ul></li><li>Nuts fall<ul><li>walnut</li><li>pecan</li></ul></li></ul>" +
                "<p>After.</p>",
        )
    }

    /**
     * Saves a Place at every character of a Spine item of [oldBlocks], as an older parse read [after], and finds
     * each at the same character in [after] as it parses now, which only adds line breaks to the text.
     */
    private fun assertOldPlacesFound(oldBlocks: List<String>, after: String) {
        val old = SpineItem("c0", oldBlocks.map { Block(BlockKind.Paragraph, it) })
        val new = open(listOf(after))
        val text = new.spineItems.single().text
        var at = 0
        for (i in old.text.indices) {
            while (text[at] != old.text[i]) at++
            assertEquals(SpinePoint(0, at), new.resolve(old.placeOf(i, 1)), "old offset $i, \"${old.text.substring(i).take(20)}\"")
            at++
        }
    }

    /** Saves a Place [offset] into each block of text in [places] as [before] parses, and finds each at the same text in [after]. */
    private fun assertPlacesFound(before: String, after: String, places: List<Pair<String, Int>>) {
        val old = open(listOf(before)).spineItems.single()
        val new = open(listOf(after))
        val item = new.spineItems.single()
        for ((text, offset) in places) {
            val start = old.blockStarts[old.blocks.indexOfFirst { it.text == text }]
            val found = new.resolve(old.placeOf(start + offset, 1))
            assertEquals(SpinePoint(0, item.text.indexOf(text) + offset), found, "a Place $offset into \"$text\"")
        }
    }

    @Test
    fun `a pre keeps its line breaks, in a block of its own or inside another`() {
        assertEquals(listOf(Block(BlockKind.Paragraph, "line one\nline two")), blocksOf("<pre>\nline one\n   line two\n</pre>"))
        assertEquals(listOf(Block(BlockKind.Paragraph, "Lines:\none\ntwo then more")), blocksOf("<p>Lines:<br/><span><pre>one\ntwo</pre></span> then\nmore</p>"))
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
