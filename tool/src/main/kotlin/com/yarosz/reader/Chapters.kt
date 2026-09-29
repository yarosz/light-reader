package com.yarosz.reader

import java.util.zip.ZipFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** A Chapter (ADR 0004): its title and where its text starts. It runs to the next Chapter's start, or the end of the Book. */
data class Chapter(val title: String, val start: SpinePoint)

/**
 * The Chapters of a Book made of [spineItems], from [listed]: the leaf entries of its table of contents
 * that name one of its Spine items, in table-of-contents order, each titled by its label. A usable table
 * of contents lists at least two, in reading order; text before the first is front matter. Otherwise each
 * Spine item is a Chapter from its start, and there is no front matter. Back matter always starts a Chapter:
 * when the Book has Back matter ([textEnd] is before the Book's end) and no Chapter starts where it does
 * (a point at the end of a Spine item being the next one's start), one with no title is added there. A
 * Chapter with no title takes its first heading, else "Chapter N", N counting Chapters from 1. A Chapter's
 * first heading is the first Heading block holding or after its start and before the next Chapter's start.
 * When a Chapter starts in a Caption block (a Gutenberg Chapter's illustration caption) and its label,
 * whitespace-collapsed, ends with a space and then its first heading's text, whitespace-collapsed, the
 * Chapter's title is that heading's text. Case and punctuation are the Book's own.
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
        found.subList(0, at) + Chapter("", backMatter) + found.subList(at, found.size)
    }
    return chapters.mapIndexed { i, chapter ->
        val heading = firstHeading(spineItems, chapter.start, chapters.getOrNull(i + 1)?.start)
        val atCaption = spineItems[chapter.start.item].kindAt(chapter.start.char) == BlockKind.Caption
        when {
            chapter.title.isEmpty() -> chapter.copy(title = heading ?: "Chapter ${i + 1}")
            heading != null && atCaption && chapter.title.endsWith(" $heading") -> chapter.copy(title = heading)
            else -> chapter
        }
    }
}

/**
 * The index in [OpenBook.chapters] of the Chapter holding [point], or null in front matter. Of two
 * Chapters starting at the same point, the later holds it. A binary search, cheap on every page turn.
 */
fun OpenBook.chapterAt(point: SpinePoint): Int? =
    (-chapters.binarySearch { if (it.start <= point) -1 else 1 } - 2).takeIf { it >= 0 }

/** What Contents lists: every Chapter's title, in order, and [current], the row marked "you're here", or null for none. */
data class Contents(val titles: List<String>, val current: Int?)

/**
 * Contents for a reader at [place], the start of the Page being read, or on the end page when [atEnd]. The
 * current row is the Chapter holding [place] ([chapterAt]), in text or Back matter, and on the end page the
 * last Chapter of the text, the last starting before [OpenBook.textEnd]; in front matter no row is current.
 */
fun OpenBook.contentsAt(place: SpinePoint, atEnd: Boolean): Contents = Contents(
    chapters.map { it.title },
    if (atEnd) chapters.indexOfLast { it.start < textEnd }.takeIf { it >= 0 } else chapterAt(place),
)

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

/** A leaf entry of a table of contents: its label, whitespace-collapsed, and the zip path and fragment its href names. */
data class TableOfContentsEntry(val label: String, val path: String, val fragment: String?)

/**
 * The leaf entries of each of the Book's tables of contents, in document order, most preferred first: its
 * EPUB 3 nav document's toc nav, then its EPUB 2 NCX's navMap. A document that is missing, has no toc nav
 * or navMap, or doesn't parse is left out. An entry without an href is left out.
 */
fun readTablesOfContents(zip: ZipFile, pkg: Package): List<List<TableOfContentsEntry>> {
    fun read(path: String?, ncx: Boolean): List<TableOfContentsEntry>? {
        val entry = path?.let(zip::getEntry) ?: return null
        val handler = TableOfContentsHandler(directoryOf(path), ncx)
        return runCatching { parseUntrusted(zip.getInputStream(entry), handler, MAX_PACKAGE_XML_BYTES) }.getOrNull()?.let { handler.leaves }
    }
    return listOfNotNull(read(pkg.nav, ncx = false), read(pkg.ncx, ncx = true))
}

/**
 * Reads the first `<nav epub:type="toc">` of a nav document, or with [ncx] the `navMap` of an NCX,
 * resolving hrefs against [dir]. [leaves] stays null when the document has neither. A nav entry's label is
 * its first `<a>` with an href, or its first `<span>` or `<a>` when it has no such `<a>`; an `<a>` inside
 * that label gives the entry its href if it has none.
 */
private class TableOfContentsHandler(private val dir: String, private val ncx: Boolean) : DefaultHandler() {
    var leaves: MutableList<TableOfContentsEntry>? = null
    private val itemName = if (ncx) "navpoint" else "li"
    private val open = ArrayDeque<Entry>()
    private var depth = 0 // inside the toc nav or navMap
    private var labelDepth = 0

    private class Entry {
        var href: String? = null
        val label = StringBuilder()
        var labelled = false
        var parent = false
    }

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        val name = qName.substringAfter(':').lowercase()
        if (depth == 0) {
            val opens = if (ncx) name == "navmap" else name == "nav" && "toc" in attrs.getValue("epub:type").orEmpty().split(WHITESPACE_RUN)
            if (opens && leaves == null) {
                leaves = mutableListOf()
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
            name == itemName -> {
                top?.parent = true
                open.addLast(Entry())
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
        val href = entry.href?.takeIf { !entry.parent } ?: return
        leaves?.add(TableOfContentsEntry(
            entry.label.toString().replace(WHITESPACE_RUN, " ").trim(),
            zipPath(dir, href),
            href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decodePercent),
        ))
    }
}
