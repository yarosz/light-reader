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

/** [headingCaption]: a Caption block the parser split out of the heading after it. */
data class Block(val kind: BlockKind, val text: String, val spans: List<Span> = emptyList(), val headingCaption: Boolean = false)

/** [spineId] is this Spine item's idref; a Place names its Spine item by it (ADR 0002). */
data class SpineItem(val spineId: String, val blocks: List<Block>) {
    /** Blocks joined by '\n'. Reading positions are offsets into this string. */
    val text: String = blocks.joinToString("\n") { it.text }

    /** Offset of each block's first character within [text]. */
    val blockStarts: List<Int> = blocks.runningFold(0) { acc, b -> acc + b.text.length + 1 }.dropLast(1)

    /** Index of the block holding [offset], -1 before the first; a separating '\n' belongs to the block before it. */
    fun blockAt(offset: Int): Int = blockStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }

    /** Kind of the block holding [offset]; a separating '\n' belongs to the block before it. */
    fun kindAt(offset: Int): BlockKind? = blocks.getOrNull(blockAt(offset))?.kind

    /** Whether block [i] is a caption split out of the heading right after it, which windows and Pages keep with that heading. */
    fun isHeadingCaption(i: Int): Boolean =
        blocks.getOrNull(i)?.headingCaption == true && blocks.getOrNull(i + 1)?.kind == BlockKind.Heading

    /** Whether a Page may not end after the line at [offset]: it is in a heading or in its [isHeadingCaption]. */
    fun keepsWithNext(offset: Int): Boolean = blockAt(offset).let { blocks.getOrNull(it)?.kind == BlockKind.Heading || isHeadingCaption(it) }
}

/**
 * [identifier] keeps a Book the same Book across re-downloads, so it keeps its Place (ADR 0002); see
 * [bookIdentifier]. [author] is its package's `dc:creator`s, null when it names none. [chapters] are its
 * Chapters in reading order ([chaptersOf]); by default each Spine item is one. [textEnd] is where the
 * Book's text ends and its Back matter starts ([textEndOf]), by default the end of the last Spine item: the
 * last Page of the text ends there and the end page follows it, a Chapter starting at or after it is Back
 * matter, and no Chapter's minutes run past it. At the start of a Spine item other than the first, it is
 * written as the end of the Spine item before, so the Page ending that Spine item reaches it.
 */
data class OpenBook(
    val identifier: String,
    val title: String,
    val spineItems: List<SpineItem>,
    val author: String? = null,
    val chapters: List<Chapter> = chaptersOf(emptyList(), spineItems),
    val textEnd: SpinePoint = SpinePoint(spineItems.lastIndex, spineItems.lastOrNull()?.text?.length ?: 0),
) {
    /** The characters before each Spine item, and last the Book's total: summed once, so [progressAt] on a turn sums nothing. */
    val charsBefore: LongArray = spineItems.runningFold(0L) { acc, item -> acc + item.text.length }.toLongArray()
}

/** The most a container, package, or encryption document may decompress to; real ones are a few KB. */
const val MAX_PACKAGE_XML_BYTES = 4L * 1024 * 1024

/** The most one Spine document may decompress to: generous (the largest Gutenberg ones are under 2 MB), but no zip bomb. */
const val MAX_SPINE_ITEM_BYTES = 32L * 1024 * 1024

/**
 * Reads an EPUB (2 or 3) into plain blocks: headings, paragraphs, verse, and image captions.
 * When the book marks its body matter (Standard Ebooks does), only Spine items marked `bodymatter` or
 * `backmatter` are kept: front matter (title page, imprint) is dropped, so the Book opens on its first
 * Chapter. Back matter, Project Gutenberg's license or a trailing run of Spine items marked as back matter
 * (Standard Ebooks' colophon and uncopyright), starts at [OpenBook.textEnd].
 * Chapters come from its first table of contents ([readTablesOfContents]) with an entry naming one of the
 * Spine items it keeps: an entry naming a dropped Spine item is dropped with it. An entry whose fragment
 * its Spine item doesn't have starts at that Spine item's start, or at the previous entry's start if that
 * is later in the same Spine item.
 * [fallbackTitle] titles a Book whose package has none: the title stored for it on the Shelf, such as
 * its Catalogue entry's, else the file name.
 */
fun parseEpub(file: File, fallbackTitle: String = file.nameWithoutExtension): OpenBook = ZipFile(file).use { zip ->
    val pkg = readPackage(zip, fallbackTitle)
    val tables = readTablesOfContents(zip, pkg)
    val fragments = tables.flatten().mapNotNullTo(hashSetOf(PG_FOOTER)) { it.fragment }
    val docs = pkg.spine.map { item -> item to XhtmlHandler(fragments).also { parseUntrusted(zip.open(item.path), it, MAX_SPINE_ITEM_BYTES) } }
    val kept = if (docs.any { it.second.isBodyMatter }) docs.filter { it.second.isBodyMatter || it.second.isBackMatter } else docs
    val body = kept.filter { it.second.blocks.isNotEmpty() }
    val spineItems = body.map { (item, doc) -> SpineItem(item.idref, doc.blocks) }
    val indexOfPath = HashMap<String, Int>().apply { body.forEachIndexed { i, (item, _) -> putIfAbsent(item.path, i) } }
    val listed = tables.firstNotNullOfOrNull { entries ->
        val resolved = mutableListOf<Chapter>()
        for (entry in entries) {
            val index = indexOfPath[entry.path] ?: continue
            val char = entry.fragment?.let { fragment ->
                body[index].second.anchors[fragment] ?: resolved.lastOrNull()?.start?.takeIf { it.item == index }?.char ?: 0
            } ?: 0
            resolved += Chapter(entry.label, SpinePoint(index, char))
        }
        resolved.ifEmpty { null }
    }.orEmpty()
    val footer = body.withIndex().firstNotNullOfOrNull { (i, doc) -> doc.second.anchors[PG_FOOTER]?.let { SpinePoint(i, it) } }
    val textEnd = textEndOf(spineItems, footer, backMatter = body.indexOfLast { !it.second.isBackMatter } + 1)
    OpenBook(pkg.identifier, pkg.title, spineItems, pkg.author, chaptersOf(listed, spineItems, textEnd), textEnd)
}

/** The id of the element holding Project Gutenberg's license, the start of a Gutenberg Book's Back matter. */
private const val PG_FOOTER = "pg-footer"

/**
 * Where the text of a Book made of [spineItems] ends ([OpenBook.textEnd]): the earlier of [footer], where
 * the element with the id `pg-footer` starts, and the start of Spine item [backMatter], the first of the
 * trailing run marked `backmatter` ([spineItems]' size when there is none). A [footer] inside a block moves
 * to the next block's start, so no Page is cut mid-block. A point at the start of a Spine item other than
 * the first is written as the end of the Spine item before. With neither, or with no text before the
 * result, it is the end of the Book.
 */
private fun textEndOf(spineItems: List<SpineItem>, footer: SpinePoint?, backMatter: Int): SpinePoint {
    val end = SpinePoint(spineItems.lastIndex, spineItems.lastOrNull()?.text?.length ?: 0)
    val snapped = footer?.let { (item, char) ->
        val starts = spineItems[item].blockStarts
        SpinePoint(item, starts.firstOrNull { it >= char } ?: spineItems[item].text.length)
    }
    val trailing = SpinePoint(backMatter, 0).takeIf { backMatter < spineItems.size }
    val point = listOfNotNull(snapped, trailing).minOrNull() ?: return end
    if (point == SpinePoint(0, 0)) return end
    return if (point.char == 0) SpinePoint(point.item - 1, spineItems[point.item - 1].text.length) else point
}

/** A Spine item's idref and the path of its document inside the zip. */
data class SpineRef(val idref: String, val path: String)

/**
 * What a Book's package document says about it, read without parsing the text. [author] is every
 * non-empty `dc:creator`, joined by ", ", or null when there is none. [nav] is the zip path of its EPUB 3
 * nav document, and [ncx] of the EPUB 2 NCX its Spine's `toc` names, each null when there is none.
 */
data class Package(
    val identifier: String,
    val title: String,
    val spine: List<SpineRef>,
    val author: String? = null,
    val nav: String? = null,
    val ncx: String? = null,
)

/**
 * Reads the package document that the container names. [fallbackTitle] titles a Book whose package
 * has no `dc:title`, or an empty one. Throws when the zip has no container or package document, no
 * Spine item that has a document, or is missing a Spine document, and when the container or package
 * isn't XML or passes [MAX_PACKAGE_XML_BYTES].
 */
fun readPackage(zip: ZipFile, fallbackTitle: String): Package {
    val opf = readOpf(zip)
    val spine = opf.spine.mapNotNull { idref ->
        opf.manifest[idref]?.takeIf { it.mediaType == "application/xhtml+xml" }?.let { SpineRef(idref, it.path) }
    }
    check(spine.isNotEmpty()) { "the package has no Spine item with a document" }
    val documents = spine.map { zip.entry(it.path) }
    val title = opf.title?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    val author = opf.creators.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    val nav = opf.manifest.values.firstOrNull { "nav" in it.properties.split(WHITESPACE_RUN) }?.path
    val ncx = opf.toc?.let { opf.manifest[it] }?.path
    return Package(bookIdentifier(opf.identifiers, opf.uniqueIdentifier, documents), title, spine, author, nav, ncx)
}

/** The package document the container names, its manifest paths resolved inside the zip. */
private fun readOpf(zip: ZipFile): OpfHandler {
    val opfPath = ContainerHandler().also { parseUntrusted(zip.open("META-INF/container.xml"), it, MAX_PACKAGE_XML_BYTES) }.opfPath
        ?: error("container.xml has no rootfile")
    return OpfHandler(directoryOf(opfPath)).also { parseUntrusted(zip.open(opfPath), it, MAX_PACKAGE_XML_BYTES) }
}

/** The directory holding the zip entry at [path]: "" at the root, else ending in "/". */
internal fun directoryOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

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

internal fun decodePercent(text: String): String {
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

/** A manifest item: its zip path, media type, and `properties`. */
private class ManifestItem(val path: String, val mediaType: String, val properties: String)

/** Reads a package document in [dir] ("" or ending in "/"). */
private class OpfHandler(private val dir: String) : DefaultHandler() {
    val manifest = mutableMapOf<String, ManifestItem>()
    val spine = mutableListOf<String>()
    var toc: String? = null
    var title: String? = null
    var uniqueIdentifier: String? = null
    val identifiers = mutableListOf<Pair<String?, String>>()
    val creators = mutableListOf<String>()
    private var inTitle = false
    private var creatorText: StringBuilder? = null
    private val titleText = StringBuilder()
    private var identifierId: String? = null
    private var identifierText: StringBuilder? = null

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        when (qName.substringAfter(':')) {
            "item" -> {
                val id = attrs.getValue("id")
                val href = attrs.getValue("href")
                if (id != null && href != null) {
                    manifest[id] = ManifestItem(zipPath(dir, href), attrs.getValue("media-type").orEmpty().lowercase(), attrs.getValue("properties").orEmpty())
                }
            }
            "spine" -> toc = attrs.getValue("toc")
            "itemref" -> spine += attrs.getValue("idref")
            "title" -> if (title == null) inTitle = true
            "package" -> uniqueIdentifier = attrs.getValue("unique-identifier")
            "identifier" -> {
                identifierId = attrs.getValue("id")
                identifierText = StringBuilder()
            }
            "creator" -> creatorText = StringBuilder()
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inTitle) titleText.appendRange(ch, start, start + length)
        identifierText?.appendRange(ch, start, start + length)
        creatorText?.appendRange(ch, start, start + length)
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
            "creator" -> creatorText?.let {
                creators += it.toString().trim().replace(WHITESPACE_RUN, " ")
                creatorText = null
            }
        }
    }
}

internal val WHITESPACE_RUN = Regex("\\s+")

private val BLOCK_ELEMENTS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "figcaption", "pre")

/** Elements that sit inside a run of text; any other, a `<div>` or an `<img>` say, ends a run of loose text ([XhtmlHandler]). */
private val INLINE_ELEMENTS = setOf(
    "a", "abbr", "b", "bdi", "bdo", "br", "cite", "code", "data", "del", "dfn", "em", "font", "i", "ins", "kbd",
    "mark", "q", "rb", "rp", "rt", "ruby", "s", "samp", "small", "span", "strike", "strong", "sub", "sup",
    "time", "tt", "u", "var", "wbr",
)

/**
 * What separates a table row's cells on its line. A space alone runs the cells together ("CHAPTER I 5"),
 * and a tab or a run of spaces sets as one space in the Book's proportional type, so a spaced middle dot
 * marks each cell boundary, as a printed table's rule would.
 */
private const val CELL_SEPARATOR = " · "

private fun emphasisOf(name: String): Emphasis? = when (name) {
    "em", "i", "cite" -> Emphasis.Italic
    "strong", "b" -> Emphasis.Bold
    else -> null
}

private val VERSE_MARKERS = listOf("verse", "poem", "song", "lyrics")

/** What a block's leading and trailing runs are made of: spaces, no-break spaces, and line breaks. */
internal val TRIMMED = charArrayOf(' ', '\u00A0', '\n')

/**
 * Reads one Spine document into [blocks]. [anchors] maps each id in [fragments] that the document has to
 * the offset in its [SpineItem.text] of the first character its element puts in a block's kept text, a
 * no-break space included; an element with none maps to the next text, or to the end of the text when
 * none follows. An element whose class includes the token `caption` inside a heading, before any of the
 * heading's own text, is its own Caption block, before that text; later, it stays in the heading. A block's
 * leading and trailing line breaks are dropped.
 *
 * Text outside every one of [BLOCK_ELEMENTS], such as text sitting directly in a `<div>`, is kept too: each
 * run of it, up to the next element not in [INLINE_ELEMENTS], is a Paragraph block (Verse inside verse), in
 * the emphasis of any `<i>` or `<b>` around it. Text in a known block nested in a `<div>` is that block's
 * alone. A `<table>` outside a block is one block with a line per row, its cells joined by [CELL_SEPARATOR]
 * and empty cells skipped; everything inside it, blocks included, is the table's text. One block rather
 * than one per row sets the rows as a list, with no paragraph indent on each. A `<br>` in a block, and a
 * line break in a `<pre>`, is a line break in the block; other runs of whitespace are one space.
 */
private class XhtmlHandler(private val fragments: Set<String>) : DefaultHandler() {
    val blocks = mutableListOf<Block>()
    var isBodyMatter = false
    var isBackMatter = false
    val anchors = mutableMapOf<String, Int>()
    private val ids = mutableListOf<String>() // the document's ids in [fragments], in document order
    private var pending = 0 // ids from this index on wait for text
    // Runs of ids anchored in the open block: (first id's index, offset into [text]). A run ends where the
    // next starts, the last at [pending], so a blank block hands its ids back in one step.
    private val blockAnchors = mutableListOf<Pair<Int, Int>>()
    private var textBefore = 0 // the blocks so far, each with its separating '\n'

    private var skipDepth = 0 // inside <head>, <script>, <style>
    private var verseDepth = 0
    private var hgroupParts: MutableList<String>? = null
    private var hgroupLength = 0 // the parts so far, each with its ": "

    private var kind: BlockKind? = null
    private var blockDepth = 0
    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()
    private val openEmphasis = ArrayDeque<Pair<Emphasis, Int>>()
    private val elementIsVerse = ArrayDeque<Boolean>()
    private var captionStart: Int? = null
    private var captionDepth = 0
    private var loose = false // the open block is text outside any of BLOCK_ELEMENTS
    private var inPre = false
    private var tableDepth = 0 // tables open in the open block, which is a table's
    private var rowStart = 0 // where the table row being read starts in [text]
    private var cellStart = 0 // where the row's text after its last separator starts
    private var separatorStart = -1 // where the row's last separator starts, -1 for none
    // Emphasis elements open outside any block. A run of loose text inside them starts in their emphasis,
    // and the first [seeded] entries of [openEmphasis] are theirs.
    private val outerEmphasis = ArrayDeque<Emphasis>()
    private var seeded = 0

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
        if (kind != null && loose && tableDepth == 0 && name !in INLINE_ELEMENTS) finishBlock()
        attrs.getValue("id")?.takeIf { it in fragments }?.let { ids += it }
        if (kind != null && tableDepth > 0) return startInTable(name)
        when {
            name == "body" -> {
                isBodyMatter = "bodymatter" in markers
                isBackMatter = "backmatter" in markers
            }
            name == "hgroup" -> {
                hgroupParts = mutableListOf()
                hgroupLength = 0
            }
            kind != null -> {
                blockDepth++
                if (kind == BlockKind.Heading && hgroupParts == null && captionStart == null &&
                    "caption" in attrs.getValue("class").orEmpty().split(WHITESPACE_RUN) && text.isBlank()
                ) {
                    captionStart = text.length
                    captionDepth = blockDepth
                }
                if (name == "br") {
                    trimTrailingSpace()
                    text.append('\n')
                }
                emphasisOf(name)?.let { openEmphasis.addLast(it to text.length) }
            }
            name in BLOCK_ELEMENTS -> {
                kind = when {
                    hgroupParts != null || name.startsWith("h") && name.length == 2 -> BlockKind.Heading
                    verseDepth > 0 -> BlockKind.Verse
                    name == "figcaption" -> BlockKind.Caption
                    else -> BlockKind.Paragraph
                }
                blockDepth = 0
                inPre = name == "pre"
            }
            name == "table" -> {
                openLoose()
                tableDepth = 1
                rowStart = 0
                cellStart = 0
                separatorStart = -1
            }
            name == "img" -> attrs.getValue("alt")?.takeIf { it.isNotBlank() }?.let {
                anchor(textBefore)
                add(Block(BlockKind.Caption, it.trim()))
            }
            else -> emphasisOf(name)?.let { outerEmphasis.addLast(it) }
        }
    }

    /**
     * An element starting in a table's block. In the outermost table a row starts a line, and a cell after
     * one with text adds [CELL_SEPARATOR]; a nested table's rows and cells, and any other block, add a space.
     */
    private fun startInTable(name: String) {
        val emphasis = emphasisOf(name)
        when {
            name == "table" -> tableDepth++
            name == "tr" && tableDepth == 1 -> {
                endRow()
                if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                rowStart = text.length
                cellStart = rowStart
            }
            (name == "td" || name == "th") && tableDepth == 1 -> if (hasTextFrom(cellStart)) {
                trimTrailingSpace()
                separatorStart = text.length
                text.append(CELL_SEPARATOR)
                cellStart = text.length
            }
            emphasis != null -> openEmphasis.addLast(emphasis to text.length)
            name !in INLINE_ELEMENTS || name == "br" -> if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ')
        }
    }

    /** Ends a table row: drops the separator after its last cell with text when no text followed it. */
    private fun endRow() {
        if (separatorStart >= rowStart && !hasTextFrom(cellStart)) text.setLength(separatorStart)
        separatorStart = -1
        trimTrailingSpace()
    }

    private fun hasTextFrom(start: Int) = (start until text.length).any { !text[it].isWhitespace() }

    /** Opens a block for text outside any of [BLOCK_ELEMENTS], in the emphasis of the elements around it. */
    private fun openLoose() {
        kind = when {
            hgroupParts != null -> BlockKind.Heading
            verseDepth > 0 -> BlockKind.Verse
            else -> BlockKind.Paragraph
        }
        loose = true
        blockDepth = 0
        for (emphasis in outerEmphasis) openEmphasis.addLast(emphasis to 0)
        seeded = outerEmphasis.size
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skipDepth > 0) return
        if (kind == null) {
            if ((start until start + length).none { isVisible(ch[it]) }) return
            openLoose()
        }
        for (i in start until start + length) {
            val c = ch[i]
            when {
                c == '\uFEFF' || c == '\u00AD' -> Unit // zero-width no-break space, soft hyphen
                c == '\n' && inPre -> {
                    trimTrailingSpace()
                    text.append('\n')
                }
                c.isWhitespace() && c != '\u00A0' -> {
                    if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ')
                }
                else -> {
                    if (pending < ids.size) {
                        blockAnchors += pending to text.length
                        pending = ids.size
                    }
                    text.append(c)
                }
            }
        }
    }

    private fun isVisible(c: Char) = !(c.isWhitespace() && c != '\u00A0') && c != '\uFEFF' && c != '\u00AD'

    override fun endDocument() = anchor(maxOf(textBefore - 1, 0))

    override fun endElement(uri: String, localName: String, qName: String) {
        val name = qName.substringAfter(':').lowercase()
        if (elementIsVerse.removeLastOrNull() == true) verseDepth--
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        if (kind != null && tableDepth > 0) {
            if (name == "table" && --tableDepth == 0) {
                endRow()
                finishBlock()
            } else if (emphasisOf(name) != null) {
                closeEmphasis()
            }
            return
        }
        if (kind != null && loose && blockDepth == 0) {
            if (name in INLINE_ELEMENTS) {
                if (emphasisOf(name) != null && seeded > 0) {
                    seeded--
                    outerEmphasis.removeLastOrNull()
                    closeEmphasis()
                }
                return
            }
            finishBlock()
        }
        when {
            kind != null && blockDepth > 0 -> {
                if (emphasisOf(name) != null) closeEmphasis()
                if (blockDepth == captionDepth) splitCaption()
                blockDepth--
            }
            kind != null -> finishBlock()
            name == "hgroup" -> {
                hgroupParts?.takeIf { it.isNotEmpty() }?.let { parts ->
                    add(Block(BlockKind.Heading, parts.joinToString(": ")))
                }
                hgroupParts = null
            }
            kind == null && emphasisOf(name) != null -> outerEmphasis.removeLastOrNull()
        }
    }

    private fun add(block: Block) {
        blocks += block
        textBefore += block.text.length + 1
    }

    private fun anchor(offset: Int) {
        for (i in pending until ids.size) anchors.putIfAbsent(ids[i], offset)
        pending = ids.size
    }

    private fun closeEmphasis() {
        val (emphasis, start) = openEmphasis.removeLastOrNull() ?: return
        if (text.length > start) spans += Span(start, text.length, emphasis)
    }

    private fun trimTrailingSpace() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
    }

    /** Moves the caption that opened at [captionStart] out of [text] into a Caption block of its own, with the ids anchored so far. */
    private fun splitCaption() {
        val from = captionStart ?: return
        captionStart = null
        val caption = text.substring(from)
        text.setLength(from)
        val captionSpans = (spans + openEmphasis.map { (emphasis, start) -> Span(start, text.length + caption.length, emphasis) })
            .filter { it.end > from }.map { Span(maxOf(it.start - from, 0), it.end - from, it.emphasis) }
        spans.replaceAll { it.copy(end = minOf(it.end, from)) }
        spans.removeAll { it.start >= it.end }
        if (caption.isNotBlank()) emit(BlockKind.Caption, caption, captionSpans, from, headingCaption = true)
    }

    private fun finishBlock() {
        splitCaption()
        val added = blocks.size
        emit(kind!!, text.toString(), spans.toList(), 0)
        if (kind == BlockKind.Heading && blocks.size == added && hgroupParts == null) {
            blocks.lastOrNull()?.takeIf { it.headingCaption }?.let { blocks[blocks.lastIndex] = it.copy(headingCaption = false) }
        }
        kind = null
        loose = false
        inPre = false
        seeded = 0
        text.setLength(0)
        spans.clear()
        openEmphasis.clear()
    }

    /**
     * Adds a block of [raw] text, which starts at offset [base] of [text], with its [spans] (offsets into
     * [raw]), without its leading and trailing line breaks and the spaces among them, and anchors the ids in
     * [blockAnchors]. Ids at or past the end of what is kept, all of them for blank text, which adds nothing,
     * go back to waiting for the next text.
     */
    private fun emit(blockKind: BlockKind, raw: String, spans: List<Span>, base: Int, headingCaption: Boolean = false) {
        val content = raw.trimEnd(*TRIMMED)
        val first = content.indexOfFirst { it !in TRIMMED }
        val lead = if (first > 0 && '\n' in content.substring(0, first)) first else 0
        val kept = content.substring(lead)
        val parts = hgroupParts
        val start = textBefore + if (parts != null) hgroupLength else 0
        for ((run, anchor) in blockAnchors.withIndex()) {
            val (from, at) = anchor
            val offset = at - base - lead
            if (first < 0 || offset >= kept.length) {
                pending = from
                break
            }
            val end = blockAnchors.getOrNull(run + 1)?.first ?: pending
            for (i in from until end) anchors.putIfAbsent(ids[i], start + maxOf(offset, 0))
        }
        blockAnchors.clear()
        if (first < 0) return
        if (parts != null) {
            parts += kept
            hgroupLength += kept.length + 2
        } else {
            add(Block(blockKind, kept, spans.map { Span(maxOf(it.start - lead, 0), minOf(it.end - lead, kept.length), it.emphasis) }
                .filter { it.start < it.end }, headingCaption))
        }
    }
}
