package com.yarosz.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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

/** The most a container, package, or encryption document may decompress to; real ones are a few KB. */
const val MAX_PACKAGE_XML_BYTES = 4L * 1024 * 1024

/** The most one Spine document may decompress to: generous (the largest Gutenberg ones are under 2 MB), but no zip bomb. */
const val MAX_CHAPTER_BYTES = 32L * 1024 * 1024

/**
 * Reads an EPUB (2 or 3) into plain blocks: headings, paragraphs, verse, and image captions.
 * Front and back matter are dropped when the book marks its body matter (Standard Ebooks does).
 * [fallbackTitle] titles a Book whose package has none: the title stored for it on the Shelf, such as
 * its Catalogue entry's, else the file name.
 */
fun parseEpub(file: File, fallbackTitle: String = file.nameWithoutExtension): Book = ZipFile(file).use { zip ->
    val pkg = readPackage(zip, fallbackTitle)
    val docs = pkg.spine.map { item -> item.idref to XhtmlHandler().also { parseUntrusted(zip.open(item.path), it, MAX_CHAPTER_BYTES) } }
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
 * Spine item that has a document, or is missing a Spine document, and when the container or package
 * isn't XML or passes [MAX_PACKAGE_XML_BYTES].
 */
fun readPackage(zip: ZipFile, fallbackTitle: String): Package {
    val opf = readOpf(zip)
    val spine = opf.spine.mapNotNull { idref ->
        opf.manifest[idref]?.takeIf { it.mediaType == "application/xhtml+xml" }?.let { SpineItem(idref, it.path) }
    }
    check(spine.isNotEmpty()) { "the package has no Spine item with a document" }
    val documents = spine.map { zip.entry(it.path) }
    val title = opf.title?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    return Package(bookIdentifier(opf.identifiers, opf.uniqueIdentifier, documents), title, spine)
}

/** The package document the container names, its manifest paths resolved inside the zip. */
private fun readOpf(zip: ZipFile): OpfHandler {
    val opfPath = ContainerHandler().also { parseUntrusted(zip.open("META-INF/container.xml"), it, MAX_PACKAGE_XML_BYTES) }.opfPath
        ?: error("container.xml has no rootfile")
    val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    return OpfHandler(opfDir).also { parseUntrusted(zip.open(opfPath), it, MAX_PACKAGE_XML_BYTES) }
}

/**
 * The zip path that [href] names relative to [dir] ("" or ending in "/"). An href is a URI path, not
 * form data: percent escapes decode as UTF-8, "+" stays "+", and a "%" that starts no escape stays
 * as written. A fragment is dropped, and "." and ".." segments resolve, never above the zip's root.
 */
internal fun zipPath(dir: String, href: String): String {
    val segments = ArrayDeque<String>()
    for (segment in (dir + decodePercent(href.substringBefore('#'))).split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return segments.joinToString("/")
}

private fun decodePercent(text: String): String {
    if ('%' !in text) return text
    val out = StringBuilder()
    val bytes = ByteArrayOutputStream()
    fun flush() {
        if (bytes.size() == 0) return
        out.append(bytes.toString("UTF-8"))
        bytes.reset()
    }
    var i = 0
    while (i < text.length) {
        val escape = if (text[i] == '%' && i + 2 < text.length && isHexDigit(text[i + 1]) && isHexDigit(text[i + 2])) {
            text.substring(i + 1, i + 3).toInt(16)
        } else {
            null
        }
        if (escape != null) {
            bytes.write(escape)
            i += 3
        } else {
            flush()
            out.append(text[i++])
        }
    }
    flush()
    return out.toString()
}

private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun ZipFile.entry(path: String): ZipEntry = getEntry(path) ?: error("EPUB is missing $path")

private fun ZipFile.open(path: String): InputStream = getInputStream(entry(path))

private val RIGHTS_FILES = listOf("META-INF/rights.xml", "META-INF/sinf.xml", "META-INF/license.lcpl")
private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")

/** The IDPF and Adobe font obfuscation algorithms: not protection, only a scramble of embedded fonts. */
private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

private fun isFontType(mediaType: String) = mediaType.startsWith("font/") || mediaType.startsWith("application/font-") ||
    mediaType.startsWith("application/x-font-") || mediaType == "application/vnd.ms-opentype"

/**
 * True when the EPUB is copy-protected (ADR 0005): it carries an Adobe, Apple, or Readium LCP rights
 * file, or its `META-INF/encryption.xml` encrypts anything but a font. Font obfuscation alone is
 * common in DRM-free EPUBs. An encrypted file counts as a font when it is encrypted with a font
 * obfuscation algorithm, when the manifest gives it a font media type, or, last, by its file
 * extension. An encryption.xml that doesn't parse counts as protection, since nothing it covers could
 * be read.
 */
fun isCopyProtected(zip: ZipFile): Boolean {
    if (RIGHTS_FILES.any { zip.getEntry(it) != null }) return true
    val encryption = zip.getEntry("META-INF/encryption.xml") ?: return false
    val encrypted = runCatching { EncryptionHandler().also { parseUntrusted(zip.getInputStream(encryption), it, MAX_PACKAGE_XML_BYTES) }.encrypted }
        .getOrElse { return true }
    val mediaTypes = runCatching { readOpf(zip).manifest.values.associate { it.path to it.mediaType } }.getOrDefault(emptyMap())
    return encrypted.any { (algorithm, uri) ->
        val path = zipPath("", uri)
        algorithm !in FONT_OBFUSCATION && mediaTypes[path]?.let(::isFontType) != true &&
            path.substringAfterLast('.').lowercase() !in FONT_EXTENSIONS
    }
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

/** One EncryptedData: its own EncryptionMethod's algorithm (not a key's, inside KeyInfo) and its CipherReference. */
private data class Encrypted(val algorithm: String?, val uri: String)

private class EncryptionHandler : DefaultHandler() {
    val encrypted = mutableListOf<Encrypted>()
    private var algorithm: String? = null
    private var uri: String? = null
    private var depth = 0

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        depth++
        when (qName.substringAfter(':')) {
            "EncryptedData" -> {
                algorithm = null
                this.uri = null
                depth = 0
            }
            "EncryptionMethod" -> if (depth == 1) algorithm = attrs.getValue("Algorithm")
            "CipherReference" -> if (this.uri == null) this.uri = attrs.getValue("URI").orEmpty()
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        depth--
        if (qName.substringAfter(':') == "EncryptedData") encrypted += Encrypted(algorithm, this.uri.orEmpty())
    }
}

private class ContainerHandler : DefaultHandler() {
    var opfPath: String? = null
    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        if (qName.endsWith("rootfile") && opfPath == null) opfPath = attrs.getValue("full-path")
    }
}

/** A manifest item: its zip path and media type. */
private class ManifestItem(val path: String, val mediaType: String)

/** Reads a package document in [dir] ("" or ending in "/"). */
private class OpfHandler(private val dir: String) : DefaultHandler() {
    val manifest = mutableMapOf<String, ManifestItem>()
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
            "item" -> {
                val id = attrs.getValue("id")
                val href = attrs.getValue("href")
                if (id != null && href != null) manifest[id] = ManifestItem(zipPath(dir, href), attrs.getValue("media-type").orEmpty().lowercase())
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
