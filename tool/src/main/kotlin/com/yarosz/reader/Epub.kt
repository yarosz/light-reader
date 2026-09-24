package com.yarosz.reader

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

enum class BlockKind { Heading, Paragraph, Verse, Caption }

enum class Emphasis { Italic, Bold }

/** [start, end) offsets are relative to the owning [Block.text]. */
data class Span(val start: Int, val end: Int, val emphasis: Emphasis)

data class Block(val kind: BlockKind, val text: String, val spans: List<Span> = emptyList())

data class Chapter(val title: String, val blocks: List<Block>) {
    /** Blocks joined by '\n'. Reading positions are offsets into this string. */
    val text: String = blocks.joinToString("\n") { it.text }

    /** Offset of each block's first character within [text]. */
    val blockStarts: List<Int> = blocks.runningFold(0) { acc, b -> acc + b.text.length + 1 }.dropLast(1)
}

data class Book(val title: String, val chapters: List<Chapter>)

/**
 * Reads an EPUB (2 or 3) into plain blocks: headings, paragraphs, verse, and image captions.
 * Front and back matter are dropped when the book marks its body matter (Standard Ebooks does).
 */
fun parseEpub(file: File): Book = ZipFile(file).use { zip ->
    fun open(path: String): InputStream =
        zip.getInputStream(zip.getEntry(path) ?: error("EPUB is missing $path"))

    val opfPath = ContainerHandler().also { sax(open("META-INF/container.xml"), it) }.opfPath
        ?: error("container.xml has no rootfile")
    val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val opf = OpfHandler().also { sax(open(opfPath), it) }

    val docs = opf.spine.mapNotNull { opf.manifest[it] }.map { href ->
        XhtmlHandler().also { sax(open(opfDir + URLDecoder.decode(href, "UTF-8")), it) }
    }
    val body = docs.filter { it.isBodyMatter }.ifEmpty { docs }.filter { it.blocks.isNotEmpty() }
    Book(
        title = opf.title ?: file.nameWithoutExtension,
        chapters = body.mapIndexed { i, doc -> Chapter(doc.title ?: "Section ${i + 1}", doc.blocks) },
    )
}

private fun sax(input: InputStream, handler: DefaultHandler) = input.use {
    SAXParserFactory.newInstance().newSAXParser().parse(it, handler)
}

private class ContainerHandler : DefaultHandler() {
    var opfPath: String? = null
    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        if (qName.endsWith("rootfile") && opfPath == null) opfPath = attrs.getValue("full-path")
    }
}

private class OpfHandler : DefaultHandler() {
    val manifest = mutableMapOf<String, String>()
    val spine = mutableListOf<String>()
    var title: String? = null
    private var inTitle = false
    private val titleText = StringBuilder()

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        when (qName.substringAfter(':')) {
            "item" -> if (attrs.getValue("media-type") == "application/xhtml+xml") {
                manifest[attrs.getValue("id")] = attrs.getValue("href")
            }
            "itemref" -> spine += attrs.getValue("idref")
            "title" -> if (title == null) inTitle = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inTitle) titleText.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (inTitle && qName.substringAfter(':') == "title") {
            title = titleText.toString().trim()
            inTitle = false
        }
    }
}

private val BLOCK_ELEMENTS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "figcaption", "pre")
private val VERSE_MARKERS = listOf("verse", "poem", "song", "lyrics")

private class XhtmlHandler : DefaultHandler() {
    val blocks = mutableListOf<Block>()
    var isBodyMatter = false
    var title: String? = null

    private var skipDepth = 0 // inside <head>, <script>, <style>
    private var verseDepth = 0
    private var hgroupParts: MutableList<String>? = null

    private var kind: BlockKind? = null
    private var blockDepth = 0
    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()
    private val openEmphasis = ArrayDeque<Pair<Emphasis, Int>>()
    private val elementIsVerse = ArrayDeque<Boolean>()

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        val name = qName.substringAfter(':').lowercase()
        val markers = "${attrs.getValue("epub:type").orEmpty()} ${attrs.getValue("class").orEmpty()}"
        val isVerse = VERSE_MARKERS.any { it in markers }
        elementIsVerse.addLast(isVerse)
        if (isVerse) verseDepth++

        if (skipDepth > 0 || name in setOf("head", "script", "style")) {
            skipDepth++
            return
        }
        when {
            name == "body" -> isBodyMatter = "bodymatter" in markers
            name == "hgroup" -> hgroupParts = mutableListOf()
            kind != null -> {
                blockDepth++
                when (name) {
                    "br" -> { trimTrailingSpace(); text.append('\n') }
                    "em", "i", "cite" -> openEmphasis.addLast(Emphasis.Italic to text.length)
                    "strong", "b" -> openEmphasis.addLast(Emphasis.Bold to text.length)
                }
            }
            name in BLOCK_ELEMENTS -> {
                kind = when {
                    hgroupParts != null || name.startsWith("h") && name.length == 2 -> BlockKind.Heading
                    verseDepth > 0 -> BlockKind.Verse
                    name == "figcaption" -> BlockKind.Caption
                    else -> BlockKind.Paragraph
                }
                blockDepth = 0
            }
            name == "img" -> attrs.getValue("alt")?.takeIf { it.isNotBlank() }?.let {
                blocks += Block(BlockKind.Caption, it.trim())
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skipDepth > 0 || kind == null) return
        for (i in start until start + length) {
            val c = ch[i]
            when {
                c == '﻿' || c == '­' -> Unit // zero-width no-break space, soft hyphen
                c.isWhitespace() && c != ' ' -> {
                    if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ')
                }
                else -> text.append(c)
            }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val name = qName.substringAfter(':').lowercase()
        if (elementIsVerse.removeLastOrNull() == true) verseDepth--
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        when {
            kind != null && blockDepth > 0 -> {
                blockDepth--
                if (name in setOf("em", "i", "cite", "strong", "b")) closeEmphasis()
            }
            kind != null -> finishBlock()
            name == "hgroup" -> {
                hgroupParts?.takeIf { it.isNotEmpty() }?.let { parts ->
                    val heading = parts.joinToString(": ")
                    blocks += Block(BlockKind.Heading, heading)
                    if (title == null) title = heading
                }
                hgroupParts = null
            }
        }
    }

    private fun closeEmphasis() {
        val (emphasis, start) = openEmphasis.removeLastOrNull() ?: return
        if (text.length > start) spans += Span(start, text.length, emphasis)
    }

    private fun trimTrailingSpace() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
    }

    private fun finishBlock() {
        trimTrailingSpace()
        val content = text.toString()
        val blockKind = kind!!
        if (content.isNotBlank()) {
            val parts = hgroupParts
            if (parts != null) {
                parts += content
            } else {
                blocks += Block(blockKind, content, spans.filter { it.start < content.length }
                    .map { it.copy(end = minOf(it.end, content.length)) })
                if (blockKind == BlockKind.Heading && title == null) title = content
            }
        }
        kind = null
        text.setLength(0)
        spans.clear()
        openEmphasis.clear()
    }
}
