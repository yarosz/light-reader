package com.yarosz.reader

import java.io.InputStream
import java.net.URLEncoder
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

private const val ATOM = "http://www.w3.org/2005/Atom"
private const val OPENSEARCH = "http://a9.com/-/spec/opensearch/1.1/"

/** Results asked of a search template that has a count parameter: Gutenberg's page size. */
const val SEARCH_PAGE_SIZE = 25

/** The most of a feed or OpenSearch body read: a Gutenberg page with inline thumbnails is under 1 MB. */
const val MAX_FEED_BYTES = 8L * 1024 * 1024

/** The most characters kept of one title, name, or summary; the rest is dropped. */
const val MAX_TEXT_CHARS = 64 * 1024

private val ACQUISITION_RELS = setOf("enclosure", "http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access")

/** Rels of Atom-typed entry links that don't lead into the entry itself. */
private val NOT_OPENING_RELS = setOf("related", "self", "start", "up", "search", "first", "last", "next", "previous")

/**
 * Reads one Catalogue page (ADR 0001): OPDS 1.x navigation and acquisition feeds, and plain Atom whose
 * entries carry EPUB enclosures. Relative links resolve against [url], the page's own URL; a link
 * that can't be made https is dropped. An entry with nothing to open or download is dropped too.
 * Null when the document isn't an Atom feed; throws SAXException when it isn't XML or passes
 * [MAX_FEED_BYTES].
 */
fun parseFeed(input: InputStream, url: HttpsUrl): CataloguePage? =
    FeedHandler(url).also { parseUntrusted(input, it, MAX_FEED_BYTES, namespaceAware = true) }.page()

/**
 * The search an OpenSearch description offers for Atom results, preferring an OPDS template, or null
 * when it offers none that can be filled.
 */
fun parseOpenSearch(input: InputStream, url: HttpsUrl): SearchTemplate? {
    val handler = OpenSearchHandler().also { parseUntrusted(input, it, MAX_FEED_BYTES, namespaceAware = true) }
    return handler.atomTemplates.sortedByDescending { (type, _) -> "profile=opds-catalog" in type }
        .map { (_, template) -> SearchTemplate(template, url) }
        .firstOrNull { it.fill("x") != null }
}

/** An OpenSearch URL template, and the description's URL that a relative template resolves against. */
class SearchTemplate(val template: String, private val base: HttpsUrl) {
    /** The results URL for [terms]. */
    fun url(terms: String): HttpsUrl = checkNotNull(fill(terms)) { "search template $template can't be filled" }

    /**
     * Fills every parameter it knows; an unknown optional one ("{name?}") is left empty. Null when the
     * template has no {searchTerms}, needs a parameter this doesn't know, or doesn't make an https URL.
     */
    internal fun fill(terms: String): HttpsUrl? {
        if ("{searchTerms}" !in template && "{searchTerms?}" !in template) return null
        var complete = true
        val filled = Regex("\\{([^}]*)\\}").replace(template) { match ->
            val name = match.groupValues[1]
            when (name.removeSuffix("?")) {
                "searchTerms" -> URLEncoder.encode(terms, "UTF-8").replace("+", "%20")
                "count" -> SEARCH_PAGE_SIZE.toString()
                "startPage", "startIndex" -> "1"
                "language" -> "*"
                "inputEncoding", "outputEncoding" -> "UTF-8"
                else -> "".also { if (!name.endsWith("?")) complete = false }
            }
        }
        return if (complete) HttpsUrl.parse(filled, base) else null
    }
}

private class FeedHandler(private val url: HttpsUrl) : DefaultHandler() {
    private var isFeed = false
    private var title = ""
    private var next: HttpsUrl? = null
    private var search: HttpsUrl? = null
    private val entries = mutableListOf<CatalogueEntry>()

    /** The Atom elements open around the current one; a foreign element is recorded as "". */
    private val path = ArrayDeque<String>()
    private var entry: EntryBuilder? = null
    private var text: TextCapture? = null

    fun page(): CataloguePage? = if (isFeed) CataloguePage(title, entries, next, search) else null

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        text?.let {
            it.open(localName)
            path.addLast("")
            return
        }
        val name = if (uri == ATOM) localName else ""
        if (path.isEmpty()) isFeed = name == "feed"
        path.addLast(name)
        if (!isFeed) return
        when (path.joinToString("/")) {
            "feed/entry" -> entry = EntryBuilder()
            "feed/title", "feed/entry/title", "feed/entry/summary", "feed/entry/content", "feed/entry/author/name" ->
                text = TextCapture(attrs.getValue("type") ?: "text", path.size)
            "feed/link" -> feedLink(attrs)
            "feed/entry/link" -> entry?.link(attrs)
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        text?.append(ch, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val capture = text
        if (capture != null && path.size > capture.depth) {
            capture.close(localName)
            path.removeLast()
            return
        }
        val at = path.joinToString("/")
        path.removeLast()
        text = null
        val value = capture?.text()
        val current = entry
        when (at) {
            "feed/title" -> title = value.orEmpty()
            "feed/entry/title" -> current?.title = value.orEmpty()
            "feed/entry/summary" -> current?.summary = value
            "feed/entry/content" -> current?.content = value
            "feed/entry/author/name" -> value?.takeIf { it.isNotEmpty() }?.let { current?.authors?.add(it) }
            "feed/entry" -> {
                current?.build()?.let(entries::add)
                entry = null
            }
        }
    }

    private fun feedLink(attrs: Attributes) {
        val href = HttpsUrl.parse(attrs.getValue("href") ?: return, url) ?: return
        when (attrs.getValue("rel")) {
            "next" -> next = href
            "search" -> if (mediaType(attrs) == "application/opensearchdescription+xml") search = href
        }
    }

    private inner class EntryBuilder {
        var title = ""
        val authors = mutableListOf<String>()
        var summary: String? = null
        var content: String? = null
        var opens: HttpsUrl? = null
        val related = mutableListOf<NavigationLink>()
        val acquisitions = mutableListOf<Acquisition>()

        fun link(attrs: Attributes) {
            val href = HttpsUrl.parse(attrs.getValue("href") ?: return, url) ?: return
            val rel = attrs.getValue("rel") ?: "alternate"
            val type = mediaType(attrs)
            val linkTitle = attrs.getValue("title")?.trim()?.takeIf { it.isNotEmpty() }
            when {
                rel in ACQUISITION_RELS -> acquisitions += Acquisition(href, type, linkTitle, attrs.getValue("length")?.toLongOrNull())
                type != "application/atom+xml" -> Unit
                rel == "related" -> linkTitle?.let { related += NavigationLink(it, href) }
                rel !in NOT_OPENING_RELS && opens == null -> opens = href
            }
        }

        fun build(): CatalogueEntry? = if (opens == null && acquisitions.isEmpty()) null else CatalogueEntry(
            title = title,
            authors = authors.toList(),
            summary = (summary ?: content)?.takeIf { it.isNotEmpty() },
            opens = opens,
            related = related.toList(),
            acquisitions = acquisitions.toList(),
        )
    }
}

private fun mediaType(attrs: Attributes) = attrs.getValue("type").orEmpty().substringBefore(';').trim().lowercase()

private val XHTML_BLOCKS = setOf("p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "tr")
private val HTML_BLOCK_TAG = Regex("<\\s*/?\\s*(?:${XHTML_BLOCKS.joinToString("|")})\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")

/** Marks a paragraph or line break of the markup; source line ends are only whitespace. */
private const val BREAK = ' '

/**
 * The text of one Atom text construct, as plain lines: "text" as it is, "html" with its markup
 * removed, and "xhtml" as the text of its elements. Paragraph and line breaks become line ends;
 * other whitespace runs become one space. Keeps the first [MAX_TEXT_CHARS] characters.
 */
private class TextCapture(private val type: String, val depth: Int) {
    private val builder = StringBuilder()

    fun append(ch: CharArray, start: Int, length: Int) {
        builder.appendRange(ch, start, start + minOf(length, MAX_TEXT_CHARS - builder.length))
    }

    fun open(localName: String) {
        if (localName.lowercase() in XHTML_BLOCKS && builder.length < MAX_TEXT_CHARS) builder.append(BREAK)
    }

    fun close(localName: String) = open(localName)

    fun text(): String {
        val raw = builder.toString()
        val plain = if (type == "html") decodeEntities(HTML_TAG.replace(HTML_BLOCK_TAG.replace(raw, "$BREAK"), "")) else raw
        return plain.split(BREAK).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

private fun decodeEntities(text: String) = ENTITY.replace(text) { match ->
    when (val name = match.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> {
            val code = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
    }
}

private class OpenSearchHandler : DefaultHandler() {
    /** Each Atom Url's type and template, in document order. */
    val atomTemplates = mutableListOf<Pair<String, String>>()

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
        if (uri != OPENSEARCH || localName != "Url") return
        val template = attrs.getValue("template") ?: return
        val type = attrs.getValue("type").orEmpty().lowercase()
        if (type.substringBefore(';').trim() == "application/atom+xml") atomTemplates += type to template
    }
}
