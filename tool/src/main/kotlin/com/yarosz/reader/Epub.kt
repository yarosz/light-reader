package com.yarosz.reader

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

enum class BlockKind { Heading, Paragraph, Verse, Caption }

enum class Emphasis { Italic, Bold }

/** [start, end) offsets are relative to the owning [Block.text]. */
data class Span(val start: Int, val end: Int, val emphasis: Emphasis)

data class Block(val kind: BlockKind, val text: String, val spans: List<Span> = emptyList())

/** [spineId] is the idref of the Spine item this Chapter came from; a Place names its Chapter by it (ADR 0002). */
data class Chapter(val spineId: String, val title: String, val blocks: List<Block>) {
    /** Blocks joined by '\n'. Reading positions are offsets into this string. */
    val text: String = blocks.joinToString("\n") { it.text }

    /** Offset of each block's first character within [text]. */
    val blockStarts: List<Int> = blocks.runningFold(0) { acc, b -> acc + b.text.length + 1 }.dropLast(1)

    /** Kind of the block holding [offset]; a separating '\n' belongs to the block before it. */
    fun kindAt(offset: Int): BlockKind? {
        val found = blockStarts.binarySearch(offset)
        return blocks.getOrNull(if (found >= 0) found else -found - 2)?.kind
    }
}

/** [identifier] keeps a Book the same Book across re-downloads, so it keeps its Place (ADR 0002); see [bookIdentifier]. */
data class Book(val identifier: String, val title: String, val chapters: List<Chapter>)

/**
 * Reads an EPUB (2 or 3) into plain blocks: headings, paragraphs, verse, and image captions.
 * Front and back matter are dropped when the book marks its body matter (Standard Ebooks does).
 */
fun parseEpub(file: File): Book = ZipFile(file).use { zip ->
    val pkg = readPackage(zip, file.nameWithoutExtension)
    val docs = pkg.spine.map { item -> item.idref to XhtmlHandler().also { sax(zip.open(item.path), it) } }
    val body = docs.filter { it.second.isBodyMatter }.ifEmpty { docs }.filter { it.second.blocks.isNotEmpty() }
    Book(
        identifier = pkg.identifier,
        title = pkg.title,
        chapters = body.mapIndexed { i, (idref, doc) -> Chapter(idref, doc.title ?: "Section ${i + 1}", doc.blocks) },
    )
}

/** A Spine item's idref and the path of its document inside the zip. */
data class SpineItem(val idref: String, val path: String)

/** What a Book's package document says about it, read without parsing the text. */
data class Package(val identifier: String, val title: String, val spine: List<SpineItem>)

/**
 * Reads the package document that the container names. [fallbackTitle] titles a Book whose package
 * has no `dc:title`, or an empty one. Throws when the zip has no container or package document, no
 * Spine item that has a document, or is missing a Spine document.
 */
fun readPackage(zip: ZipFile, fallbackTitle: String): Package {
    val opfPath = ContainerHandler().also { sax(zip.open("META-INF/container.xml"), it) }.opfPath
        ?: error("container.xml has no rootfile")
    val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val opf = OpfHandler().also { sax(zip.open(opfPath), it) }
    val spine = opf.spine.mapNotNull { idref ->
        opf.manifest[idref]?.let { SpineItem(idref, opfDir + URLDecoder.decode(it, "UTF-8")) }
    }
    check(spine.isNotEmpty()) { "the package has no Spine item with a document" }
    val documents = spine.map { zip.entry(it.path) }
    val title = opf.title?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    return Package(bookIdentifier(opf.identifiers, opf.uniqueIdentifier, documents), title, spine)
}

private fun ZipFile.entry(path: String): ZipEntry = getEntry(path) ?: error("EPUB is missing $path")

private fun ZipFile.open(path: String): InputStream = getInputStream(entry(path))

private val RIGHTS_FILES = listOf("META-INF/rights.xml", "META-INF/sinf.xml", "META-INF/license.lcpl")
private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

/**
 * True when the EPUB is copy-protected (ADR 0005): it carries an Adobe, Apple, or Readium LCP rights
 * file, or its `META-INF/encryption.xml` encrypts anything but a font. Font obfuscation alone is
 * common in DRM-free EPUBs. A font is known by its file extension. An encryption.xml that doesn't
 * parse counts as protection, since nothing it covers could be read.
 */
fun isCopyProtected(zip: ZipFile): Boolean {
    if (RIGHTS_FILES.any { zip.getEntry(it) != null }) return true
    val encryption = zip.getEntry("META-INF/encryption.xml") ?: return false
    val targets = runCatching { EncryptionHandler().also { sax(zip.getInputStream(encryption), it) }.targets }
        .getOrElse { return true }
    return targets.any { URLDecoder.decode(it, "UTF-8").substringAfterLast('.').lowercase() !in FONT_EXTENSIONS }
}

/**
 * The Book's identifier: the `dc:identifier` that the package's `unique-identifier` names, else the
 * first `dc:identifier`. [identifiers] pairs each one's `id` attribute (null when absent) with its
 * trimmed text, in document order. A book with none gets "sha256:" plus the hex SHA-256 of its Spine's
 * content: each Spine document's CRC-32 and length, one pair per line in reading order. The zip's
 * central directory records both for every entry, so this reads no document, yet any change to the
 * text changes it. Two different books never share it just because they share a title and generic
 * idrefs. It is stable across re-downloads and repackaging of the same text, not across editions.
 */
fun bookIdentifier(identifiers: List<Pair<String?, String>>, uniqueIdentifier: String?, spine: List<ZipEntry>): String {
    val usable = identifiers.filter { it.second.isNotEmpty() }
    usable.firstOrNull { uniqueIdentifier != null && it.first == uniqueIdentifier }?.let { return it.second }
    usable.firstOrNull()?.let { return it.second }
    val content = spine.joinToString("\n") { "${it.crc.toString(16)} ${it.size}" }
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}

private fun sax(input: InputStream, handler: DefaultHandler) = input.use {
    SAXParserFactory.newInstance().newSAXParser().parse(it, handler)
}

private class EncryptionHandler : DefaultHandler() {
    val targets = mutableListOf<String>()
    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        if (qName.substringAfter(':') == "CipherReference") targets += attrs.getValue("URI").orEmpty()
    }
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
    var uniqueIdentifier: String? = null
    val identifiers = mutableListOf<Pair<String?, String>>()
    private var inTitle = false
    private val titleText = StringBuilder()
    private var identifierId: String? = null
    private var identifierText: StringBuilder? = null

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        when (qName.substringAfter(':')) {
            "item" -> if (attrs.getValue("media-type") == "application/xhtml+xml") {
                manifest[attrs.getValue("id")] = attrs.getValue("href")
            }
            "itemref" -> spine += attrs.getValue("idref")
            "title" -> if (title == null) inTitle = true
            "package" -> uniqueIdentifier = attrs.getValue("unique-identifier")
            "identifier" -> {
                identifierId = attrs.getValue("id")
                identifierText = StringBuilder()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inTitle) titleText.appendRange(ch, start, start + length)
        identifierText?.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when (qName.substringAfter(':')) {
            "title" -> if (inTitle) {
                title = titleText.toString().trim()
                inTitle = false
            }
            "identifier" -> identifierText?.let {
                identifiers += identifierId to it.toString().trim()
                identifierText = null
            }
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
                c == '\uFEFF' || c == '\u00AD' -> Unit // zero-width no-break space, soft hyphen
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
