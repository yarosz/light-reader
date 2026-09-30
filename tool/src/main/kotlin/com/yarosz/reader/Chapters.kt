package com.yarosz.reader

import java.util.zip.ZipFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * A Chapter (ADR 0004): its title, where its text starts, and [parts], the Parts its table of contents nests
 * it in, outermost first, the last being its Part. It runs to the next Chapter's start, or the end of the Book.
 */
data class Chapter(val title: String, val start: SpinePoint, val parts: List<Part> = emptyList())

/** A Part the table of contents nests Chapters under: its title and where its heading starts, else its first Chapter's start. */
data class Part(val title: String, val start: SpinePoint)

/**
 * The Chapters of a Book made of [spineItems], from [listed]: the leaf entries of its table of contents
 * that name one of its Spine items, in table-of-contents order, each titled by its label. A usable table
 * of contents lists at least two, in reading order; text before the first is Front matter. Otherwise each
 * Spine item is a Chapter from its start, and there is no Front matter. Back matter always starts a Chapter:
 * when the Book has Back matter ([textEnd] is before the Book's end) and no Chapter starts where it does
 * (a point at the end of a Spine item being the next one's start), the first Chapter after that point moves
 * back to start there when no heading comes between them (Gutenberg's end-of-book lines before the license
 * its table of contents lists), else one with no title is added there. A
 * Chapter with no title takes its first heading, else "Chapter N", N counting Chapters from 1. A Chapter's
 * first heading is the first Heading block holding or after its start and before the next Chapter's start.
 * When a Chapter starts in a Caption block (a Gutenberg Chapter's illustration caption) and its label,
 * whitespace-collapsed, ends with a space and then its first heading's text, whitespace-collapsed, the
 * Chapter's title is that heading's text. Case and punctuation are the Book's own. A Chapter keeps the Parts
 * [listed] gives it, save a Chapter of Back matter, which falls under none.
 */
fun chaptersOf(
    listed: List<Chapter>,
    spineItems: List<SpineItem>,
    textEnd: SpinePoint = SpinePoint(spineItems.lastIndex, spineItems.lastOrNull()?.text?.length ?: 0),
): List<Chapter> {
    val usable = listed.size > 1 && listed.zipWithNext().none { (a, b) -> b.start < a.start }
    val found = if (usable) listed else spineItems.indices.map { Chapter("", SpinePoint(it, 0)) }
    fun normal(point: SpinePoint) =
        if (point.item < spineItems.lastIndex && point.char == spineItems[point.item].text.length) SpinePoint(point.item + 1, 0) else point
    val backMatter = normal(textEnd).takeIf { it.char < spineItems.getOrNull(it.item)?.text?.length ?: 0 }
    val chapters = if (backMatter == null || found.any { normal(it.start) == backMatter }) found else {
        val at = found.indexOfFirst { it.start > backMatter }.takeIf { it >= 0 } ?: found.size
        val next = found.getOrNull(at)
        if (next != null && firstHeading(spineItems, backMatter, next.start) == null) {
            found.subList(0, at) + next.copy(start = backMatter) + found.subList(at + 1, found.size)
        } else {
            found.subList(0, at) + Chapter("", backMatter) + found.subList(at, found.size)
        }
    }
    val titled = chapters.mapIndexed { i, chapter ->
        val heading = firstHeading(spineItems, chapter.start, chapters.getOrNull(i + 1)?.start)
        val atCaption = spineItems[chapter.start.item].kindAt(chapter.start.char) == BlockKind.Caption
        when {
            chapter.title.isEmpty() -> chapter.copy(title = heading ?: "Chapter ${i + 1}")
            heading != null && atCaption && chapter.title.endsWith(" $heading") -> chapter.copy(title = heading)
            else -> chapter
        }.let { if (it.start >= textEnd) it.copy(parts = emptyList()) else it }
    }
    return titled
}

/**
 * The index in [OpenBook.chapters] of the Chapter holding [point], or null in Front matter. Of two
 * Chapters starting at the same point, the later holds it. A binary search, cheap on every page turn.
 */
fun OpenBook.chapterAt(point: SpinePoint): Int? =
    (-chapters.binarySearch { if (it.start <= point) -1 else 1 } - 2).takeIf { it >= 0 }

/**
 * The highest a Page packed at [char] in [spineItem], Spine item [item], may start ([pack]'s floor): where
 * the Chapter holding [char] starts, of [chapterStarts] in order, or, when that is a block's start, where
 * the headings right before it start (`<h2>II</h2><p id="two">`: a table of contents pointing past a
 * Chapter's heading, which is still that Chapter's), stopping below a heading another Chapter starts in (a
 * Part's heading right above its first Chapter's). 0 when that Chapter starts in an earlier Spine item,
 * or [char] is in Front matter.
 */
fun pageFloor(chapterStarts: List<SpinePoint>, spineItem: SpineItem, item: Int, char: Int): Int {
    val start = chapterStarts.lastOrNull { it <= SpinePoint(item, char) }?.takeIf { it.item == item }?.char ?: return 0
    var block = spineItem.blockStarts.binarySearch(start).takeIf { it >= 0 } ?: return start
    fun startsChapter(b: Int) = chapterStarts.any { it.item == item && it.char in spineItem.blockStarts[b] until spineItem.blockStarts[b + 1] }
    while (block > 0 && spineItem.keepsWithNext(spineItem.blockStarts[block - 1]) && !startsChapter(block - 1)) block--
    return spineItem.blockStarts[block]
}

/** A row of Contents: a Chapter's title and start, or, [isPart], a Part's title and start. */
data class ContentsRow(val title: String, val start: SpinePoint, val isPart: Boolean = false)

/** What Contents lists: its [rows] in order, and [current], the index of the row marked "you're here", or null for none. */
data class Contents(val rows: List<ContentsRow>, val current: Int?)

/**
 * Contents for a reader at [point], the point the Page being read goes by (its start, or a Chapter starting
 * later in its first line, or in the first line under the headings it opens on), or on the end page when
 * [atEnd]. A row per Chapter, in order, each after a row for every Part it falls under that the Chapter
 * before it doesn't, outermost first. The current row is the Chapter holding [point] ([chapterAt]), in text
 * or Back matter, and on the end page the last Chapter of the text, the last starting before
 * [OpenBook.textEnd]; in Front matter no row is current, and a Part's row never is.
 */
fun OpenBook.contentsAt(point: SpinePoint, atEnd: Boolean): Contents {
    val rows = mutableListOf<ContentsRow>()
    val chapterRows = IntArray(chapters.size)
    var above = emptyList<Part>()
    chapters.forEachIndexed { i, chapter ->
        val kept = above.zip(chapter.parts).takeWhile { (a, b) -> a == b }.size
        chapter.parts.drop(kept).mapTo(rows) { ContentsRow(it.title, it.start, isPart = true) }
        above = chapter.parts
        chapterRows[i] = rows.size
        rows += ContentsRow(chapter.title, chapter.start)
    }
    val current = if (atEnd) chapters.indexOfLast { it.start < textEnd }.takeIf { it >= 0 } else chapterAt(point)
    return Contents(rows, current?.let { chapterRows[it] })
}

/**
 * The text of the first heading holding or after [from] and before [until] (the end of the Book when null),
 * whitespace-collapsed. A block's text holds [from], its separating '\n' doesn't.
 */
private fun firstHeading(spineItems: List<SpineItem>, from: SpinePoint, until: SpinePoint?): String? {
    for (item in from.item..(until?.item ?: spineItems.lastIndex)) {
        val spineItem = spineItems[item]
        val first = if (item != from.item) 0 else spineItem.blockAt(from.char).let { block ->
            if (block >= 0 && from.char < spineItem.blockStarts[block] + spineItem.blocks[block].text.length) block else block + 1
        }
        for (i in first until spineItem.blocks.size) {
            if (until != null && SpinePoint(item, spineItem.blockStarts[i]) >= until) return null
            val block = spineItem.blocks[i]
            if (block.kind == BlockKind.Heading) return block.text.replace(WHITESPACE_RUN, " ").trim()
        }
    }
    return null
}

/**
 * An entry of a table of contents: its label, whitespace-collapsed, the zip path and fragment its href names
 * (both null when it has none), and [depth], how many entries it is nested in.
 */
data class TableOfContentsEntry(val label: String, val path: String?, val fragment: String?, val depth: Int = 0)

/**
 * The entries of each of the Book's tables of contents, in document order, most preferred first: its EPUB 3
 * nav document's toc nav, then its EPUB 2 NCX's navMap. An entry is a leaf when the next entry isn't nested
 * deeper. A document that is missing, has no toc nav or navMap, or doesn't parse is left out.
 */
fun readTablesOfContents(zip: ZipFile, pkg: Package): List<List<TableOfContentsEntry>> {
    fun read(path: String?, ncx: Boolean): List<TableOfContentsEntry>? {
        val entry = path?.let(zip::getEntry) ?: return null
        val handler = TableOfContentsHandler(directoryOf(path), ncx)
        return runCatching { parseUntrusted(zip.getInputStream(entry), handler, MAX_PACKAGE_XML_BYTES) }.getOrNull()?.let { handler.entries?.filterNotNull() }
    }
    return listOfNotNull(read(pkg.nav, ncx = false), read(pkg.ncx, ncx = true))
}

/**
 * Reads the first `<nav epub:type="toc">` of a nav document, or with [ncx] the `navMap` of an NCX,
 * resolving hrefs against [dir]. [entries] stays null when the document has neither. A nav entry's label is
 * its first `<a>` with an href, or its first `<span>` or `<a>` when it has no such `<a>`; an `<a>` inside
 * that label gives the entry its href if it has none.
 */
private class TableOfContentsHandler(private val dir: String, private val ncx: Boolean) : DefaultHandler() {
    var entries: MutableList<TableOfContentsEntry?>? = null
    private val itemName = if (ncx) "navpoint" else "li"
    private val open = ArrayDeque<Entry>()
    private var depth = 0 // inside the toc nav or navMap
    private var labelDepth = 0

    private class Entry(val index: Int) {
        var href: String? = null
        val label = StringBuilder()
        var labelled = false
    }

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        val name = qName.substringAfter(':').lowercase()
        if (depth == 0) {
            val opens = if (ncx) name == "navmap" else name == "nav" && "toc" in attrs.getValue("epub:type").orEmpty().split(WHITESPACE_RUN)
            if (opens && entries == null) {
                entries = mutableListOf()
                depth = 1
            }
            return
        }
        depth++
        val top = open.lastOrNull()
        when {
            labelDepth > 0 -> {
                labelDepth++
                if (!ncx && name == "a" && top?.href == null) top?.href = attrs.getValue("href")
            }
            name == itemName -> entries?.let {
                open.addLast(Entry(it.size))
                it += null
            }
            top == null -> Unit
            !ncx && name == "a" && top.href == null && attrs.getValue("href") != null -> {
                top.label.setLength(0)
                top.labelled = true
                labelDepth = 1
                top.href = attrs.getValue("href")
            }
            !top.labelled && (if (ncx) name == "text" else name == "a" || name == "span") -> {
                top.labelled = true
                labelDepth = 1
                if (!ncx) top.href = attrs.getValue("href")
            }
            ncx && name == "content" && top.href == null -> top.href = attrs.getValue("src")
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (labelDepth > 0) open.last().label.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (depth == 0) return
        depth--
        if (labelDepth > 0) {
            labelDepth--
            return
        }
        if (qName.substringAfter(':').lowercase() != itemName) return
        val entry = open.removeLastOrNull() ?: return
        val href = entry.href
        entries?.set(entry.index, TableOfContentsEntry(
            entry.label.toString().replace(WHITESPACE_RUN, " ").trim(),
            href?.let { zipPath(dir, it) },
            href?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }?.let(::decodePercent),
            open.size,
        ))
    }
}
