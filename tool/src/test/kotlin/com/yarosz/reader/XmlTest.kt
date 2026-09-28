package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class XmlTest {

    /** Collects the text and attribute values a document delivers. */
    private class Collect : DefaultHandler() {
        val text = StringBuilder()
        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            for (i in 0 until attrs.length) text.append(attrs.getValue(i))
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }
    }

    private fun parse(xml: String, maxBytes: Long = 1_000_000): String =
        Collect().also { parseUntrusted(xml.byteInputStream(), it, maxBytes) }.text.toString()

    @Test
    fun `a file entity, in text or an attribute, reads nothing from disk`() {
        val dir = createTempDirectory("xml").toFile()
        try {
            val secret = File(dir, "secret.txt").apply { writeText("SECRET") }
            val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY s SYSTEM "${secret.toURI()}">]><x a="1">A&s;B</x>"""
            assertEquals("1AB", parse(xml))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an external DTD and parameter entity are never fetched`() {
        withNoNetwork {
            assertEquals("ok", parse("""<?xml version="1.0"?><!DOCTYPE x SYSTEM "http://127.0.0.1:9/x.dtd"><x>ok</x>"""))
            assertEquals("ok", parse("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY % p SYSTEM "http://127.0.0.1:9/p.dtd"> %p;]><x>ok</x>"""))
        }
    }

    @Test
    fun `XHTML's named entities under a DTD that is never read become their characters`() {
        withNoNetwork {
            val xml = "<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://127.0.0.1:9/xhtml11.dtd\">" +
                "<html>a&nbsp;b&mdash;&eacute;&hellip;&apos;&euro;&unknown;c</html>"
            assertEquals("a\u00A0b\u2014\u00E9\u2026'\u20ACc", parse(xml))
        }
        assertEquals(253, XHTML_ENTITIES.size)
        assertEquals(mapOf("nbsp" to 160, "yuml" to 255, "Yuml" to 376, "hearts" to 9829, "euro" to 8364), XHTML_ENTITIES.filterKeys { it in setOf("nbsp", "yuml", "Yuml", "hearts", "euro") })
    }

    @Test
    fun `XHTML's named entities become their characters with no DTD at all, as Android's parser needs`() {
        assertEquals(" a b—c", parse("<p x=\"&nbsp;\">a&nbsp;b&mdash;c</p>"))
    }

    /** [text] through [XhtmlEntityStream], its input handing over at most [chunk] bytes a read. */
    private fun rewrite(text: String, chunk: Int = Int.MAX_VALUE, bytewise: Boolean = false): String {
        val input = object : java.io.ByteArrayInputStream(text.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, chunk))
        }
        val stream = XhtmlEntityStream(input)
        return if (bytewise) generateSequence { stream.read().takeIf { it >= 0 } }.map { it.toByte() }.toList().toByteArray().decodeToString()
        else stream.readBytes().decodeToString()
    }

    @Test
    fun `an entity split between two reads of the input is still rewritten`() {
        val text = "a&nbsp;b&amp;c&euro;&hellip;d&unknown;&;&#160;&"
        val expected = "a&#160;b&amp;c&#8364;&#8230;d&unknown;&;&#160;&"
        for (chunk in 1..text.length) {
            assertEquals(expected, rewrite(text, chunk), "chunk $chunk")
            assertEquals(expected, rewrite(text, chunk, bytewise = true), "chunk $chunk, a byte at a time")
        }
    }

    @Test
    fun `an entity across the stream's own block boundary is rewritten, and a last byte of '&' passes through`() {
        for (pad in 8_180..8_200) {
            val x = "x".repeat(pad)
            assertEquals("$x&#160;$x&", rewrite("$x&nbsp;$x&"), "pad $pad")
        }
        assertEquals("${"x".repeat(8_191)}&amp;", rewrite("${"x".repeat(8_191)}&amp;"))
        assertEquals("&", rewrite("&"))
        assertEquals("", rewrite(""))
    }

    @Test
    fun `a CDATA section ended by more than two brackets still ends, even split between reads`() {
        val text = "<![CDATA[&nbsp;]]]>&nbsp;<![CDATA[&nbsp;]]>&nbsp;"
        for (chunk in 1..text.length) {
            assertEquals("<![CDATA[&nbsp;]]]>&#160;<![CDATA[&nbsp;]]>&#160;", rewrite(text, chunk), "chunk $chunk")
        }
    }

    @Test
    fun `XML's own entities, unknown or unterminated names, CDATA and numeric references pass through`() {
        assertEquals("&nbsp; <b>&amp;nbsp;  ", parse("<x>&amp;nbsp; &lt;b&gt;<![CDATA[&amp;nbsp;]]>&nbsp;&#160;</x>"))
        assertEquals("ok", parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY verylongname \"o\">]><x>&verylongname;k</x>"))
        assertEquals("été", Collect().also { parseUntrusted("﻿<x>été</x>".toByteArray(Charsets.UTF_16BE).inputStream(), it, 1_000) }.text.toString())
    }

    @Test
    fun `text that expands past the budget fails, though the document itself is small`() {
        val doc = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "xxxxxxxxxx"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
            <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">]><x>&c;&c;</x>"""
        assertEquals(2000, parse(doc, maxBytes = 2_000).length)
        assertFailsWith<TooLargeException> { parse(doc, maxBytes = 1_999) }
        val attribute = """<!DOCTYPE x [<!ENTITY a "xxxxxxxxxx"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]><x a="&b;&b;&b;"/>"""
        assertEquals(300, parse(attribute, maxBytes = 300).length)
        assertFailsWith<TooLargeException> { parse(attribute, maxBytes = 299) }
    }

    @Test
    fun `a document longer than its byte cap fails however it is formed`() {
        val doc = "<x>${" ".repeat(2_000)}</x>"
        assertEquals("", parse(doc, maxBytes = 2_007).trim())
        assertFailsWith<TooLargeException> { parse(doc, maxBytes = 2_006) }
    }

    @Test
    fun `an entity-expansion bomb fails`() {
        assertFailsWith<org.xml.sax.SAXException> { parse(LAUGHS) }
    }
}

/** Billion laughs: under 1 KB that expands to about 3 GB of text. */
val LAUGHS = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol">""" +
    (1..9).joinToString("") { n -> """<!ENTITY lol$n "${"&lol${if (n == 1) "" else n - 1};".repeat(10)}">""" } +
    """]><lolz>&lol9;</lolz>"""

/** Runs [block] with every http(s) request sent to a port that refuses it, so any fetch fails fast. */
fun <T> withNoNetwork(block: () -> T): T {
    val keys = listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")
    val saved = keys.associateWith { System.getProperty(it) }
    System.setProperty("http.proxyHost", "127.0.0.1")
    System.setProperty("http.proxyPort", "9")
    System.setProperty("https.proxyHost", "127.0.0.1")
    System.setProperty("https.proxyPort", "9")
    try {
        return block()
    } finally {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }
}
