package com.yarosz.reader

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Parses XML from anywhere untrusted (a feed, an OpenSearch description, any document inside a
 * downloaded EPUB) into [handler], reading at most [maxBytes] of [input] and passing at most
 * [maxBytes] characters of text and attribute values to the handler. Every external entity and
 * external DTD resolves to nothing, so parsing never reads a file or touches the network, while a
 * DOCTYPE (EPUB2's XHTML 1.1 one, the OEB one) is still accepted. An entity the unread DTD would
 * have declared reaches the handler as its character when it is one of XHTML's ([XHTML_ENTITIES]),
 * so `a&nbsp;b` keeps its space; any other is skipped. The character budget bounds an
 * internal entity-expansion bomb whatever the platform parser's own limits. Closes [input].
 * Throws [TooLargeException] past either bound and another SAXException when the document isn't
 * XML; both are SAXExceptions, so an IOException means [input] itself failed.
 */
fun parseUntrusted(input: InputStream, handler: DefaultHandler, maxBytes: Long, namespaceAware: Boolean = false) {
    CappedStream(input, maxBytes).use { capped ->
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = namespaceAware }.newSAXParser().xmlReader
        reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = TextBudget(handler, maxBytes)
        reader.errorHandler = handler
        reader.dtdHandler = handler
        try {
            reader.parse(InputSource(capped))
        } catch (e: Exception) {
            if (capped.exceeded) throw TooLargeException()
            throw e
        }
    }
}

/** The input passed a size bound: more bytes than allowed, or text that expands past its budget. */
class TooLargeException : SAXException("input is larger than allowed")

/** Reads at most [max] bytes of [inner]; one more fails the read with an IOException and sets [exceeded]. */
private class CappedStream(inner: InputStream, private val max: Long) : FilterInputStream(inner) {
    private var read = 0L
    var exceeded = false
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count(it) }

    override fun markSupported() = false

    private fun count(n: Long) {
        read += n
        if (read > max) {
            exceeded = true
            throw IOException("more than $max bytes")
        }
    }
}

/**
 * Passes SAX content to [inner], failing once text and attribute values together pass [max]
 * characters, and turning a skipped XHTML entity into its character.
 */
private class TextBudget(private val inner: ContentHandler, private var max: Long) : ContentHandler by inner {
    override fun skippedEntity(name: String) {
        val code = XHTML_ENTITIES[name] ?: return inner.skippedEntity(name)
        val chars = Character.toChars(code)
        characters(chars, 0, chars.size)
    }

    override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
        for (i in 0 until atts.length) spend(atts.getValue(i).length)
        inner.startElement(uri, localName, qName, atts)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        spend(length)
        inner.characters(ch, start, length)
    }

    override fun ignorableWhitespace(ch: CharArray, start: Int, length: Int) {
        spend(length)
        inner.ignorableWhitespace(ch, start, length)
    }

    private fun spend(n: Int) {
        max -= n
        if (max < 0) throw TooLargeException()
    }
}
