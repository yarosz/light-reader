package com.yarosz.reader

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTest {

    private fun url(value: String) = HttpsUrl.parse(value)!!

    private fun fixture(name: String) = File("src/test/fixtures/catalogues/$name").readBytes()

    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Home</title>
        <entry><title>Book</title><link rel="enclosure" type="application/epub+zip" href="book.epub"/></entry></feed>""".toByteArray()

    @Test
    fun `https stays, http becomes https marked as upgraded, anything else is no URL`() {
        assertEquals("https://example.org/opds", url("https://example.org/opds").value)
        assertFalse(url("https://example.org/opds").upgraded)
        val typed = url("  HTTP://Example.org:8080/opds?x=1 ")
        assertEquals("https://Example.org:8080/opds?x=1", typed.value)
        assertTrue(typed.upgraded)
        listOf("ftp://example.org/", "example.org/opds", "mailto:a@example.org", "data:text/plain,hi", "https:///path", "https://exa mple.org/", "http://[bad").forEach {
            assertNull(HttpsUrl.parse(it), it)
        }
    }

    @Test
    fun `a URL is its value, and upgraded is request context, not identity`() {
        assertEquals(url("http://example.org/a"), url("https://example.org/a"))
        assertEquals(url("http://example.org/a").hashCode(), url("https://example.org/a").hashCode())
        assertEquals(setOf(url("https://example.org/a")), setOf(url("http://example.org/a"), url("https://example.org/a")))
    }

    @Test
    fun `a URL is always ASCII, and any non-http scheme is refused before parsing`() {
        assertEquals("https://example.org/caf%C3%A9/%E2%80%94.epub", url("https://example.org/café/—.epub").value)
        assertEquals("https://example.org/a/caf%C3%A9", HttpsUrl.parse("café", url("https://example.org/a/b"))?.value)
        assertNull(HttpsUrl.parse("data:image/png;base64," + "A".repeat(1_000_000)))
        assertNull(HttpsUrl.parse(" javascript:alert(1)", url("https://example.org/")))
        assertNull(HttpsUrl.parse("DATA:,x", url("https://example.org/")))
    }

    @Test
    fun `relative URLs resolve against the base, including a bare host and a query of its own`() {
        val base = url("https://books.example.org/opds/root.xml?page=2#top")
        assertEquals("https://books.example.org/opds/new.xml", HttpsUrl.parse("new.xml", base)?.value)
        assertEquals("https://books.example.org/ebooks/1.opds", HttpsUrl.parse("/ebooks/1.opds", base)?.value)
        assertEquals("https://cdn.example.org/b.epub", HttpsUrl.parse("//cdn.example.org/b.epub", base)?.value)
        assertEquals("https://books.example.org/opds/root.xml?page=3", HttpsUrl.parse("?page=3", base)?.value)
        assertEquals("https://books.example.org/a%20b.epub", HttpsUrl.parse("/a b.epub", base)?.value)
        assertEquals("https://books.example.org/new.xml", HttpsUrl.parse("new.xml", url("https://books.example.org"))?.value)
        assertTrue(HttpsUrl.parse("http://other.example.org/x", base)!!.upgraded)
        assertFalse(HttpsUrl.parse("new.xml", url("http://books.example.org/"))!!.upgraded)
    }

    @Test
    fun `an upgraded URL that refuses the connection or the handshake has no HTTPS, other failures are unreachable`() {
        val upgraded = HttpsUrl.parse("http://books.example.org/")!!
        val https = url("https://books.example.org/")
        assertEquals(NoHttps, unreachable(upgraded, ConnectException("refused")))
        assertEquals(NoHttps, unreachable(upgraded, SSLHandshakeException("not TLS")))
        assertEquals(NoHttps, unreachable(upgraded, SSLException("Unsupported or unrecognized SSL message")))
        assertEquals(Unreachable, unreachable(upgraded, UnknownHostException("offline")))
        assertEquals(Unreachable, unreachable(upgraded, SocketTimeoutException("Read timed out")))
        assertEquals(Unreachable, unreachable(https, ConnectException("refused")))
        assertEquals(Unreachable, unreachable(https, SSLHandshakeException("not TLS")))
    }

    @Test
    fun `a connect timeout on an upgraded URL is unreachable, since the phone can't say its network is validated`() {
        val transport = Transport { throw SocketTimeoutException("failed to connect to books.example.org/203.0.113.9 (port 443) after 15000ms") }
        val upgraded = HttpsUrl.parse("http://books.example.org/opds")!!
        assertEquals(Fetched.Failed(Unreachable), fetchPage(transport, upgraded))
        assertEquals(Fetched.Failed(Unreachable), fetchSearch(transport, upgraded))
        assertEquals(DownloadState.Failed(Unreachable), Downloader(transport, createTempDirectory("timeout").toFile()).download(upgraded, "T") {})
    }

    @Test
    fun `a certificate the phone doesn't trust is its own failure, upgraded or not`() {
        val untrusted = SSLHandshakeException("untrusted").apply {
            initCause(CertificateException("Trust anchor for certification path not found.", CertPathValidatorException("no anchor")))
        }
        val pathOnly = SSLHandshakeException("path").apply { initCause(CertPathValidatorException("expired")) }
        listOf(url("https://books.example.org/"), HttpsUrl.parse("http://books.example.org/")!!).forEach { at ->
            assertEquals(UntrustedCertificate, unreachable(at, untrusted))
            assertEquals(UntrustedCertificate, unreachable(at, pathOnly))
            assertEquals(UntrustedCertificate, unreachable(at, SSLPeerUnverifiedException("Hostname books.example.org not verified")))
        }
    }

    @Test
    fun `a redirect resolves against the URL it came from, only to https`() {
        val from = url("https://books.example.org/opds/root.xml")
        assertEquals(url("https://books.example.org/opds/new.xml"), redirectTarget(from, "new.xml"))
        assertEquals(url("https://cdn.example.org/b.epub"), redirectTarget(from, "https://cdn.example.org/b.epub"))
        assertNull(redirectTarget(from, null))
        assertNull(redirectTarget(from, "ftp://books.example.org/b.epub"))
        assertNull(redirectTarget(from, "http://[bad"))
        assertEquals(NoHttps, unreachable(from, kotlin.runCatching { redirectTarget(from, "HTTP://books.example.org/") }.exceptionOrNull() as IOException))
    }

    @Test
    fun `a fetched page resolves its links against where redirects landed, and the response is closed`() {
        val transport = FakeTransport(mapOf("https://books.example.org/" to Answer(body = atom, landsAt = "https://books.example.org/opds/")))
        val page = assertIs<Fetched.Ok<CataloguePage>>(fetchPage(transport, url("https://books.example.org/"))).value
        assertEquals("https://books.example.org/opds/book.epub", page.entries.single().download?.url?.value)
        assertEquals(1, transport.closed)
    }

    @Test
    fun `a typed http Catalogue is fetched over https only`() {
        val transport = FakeTransport(mapOf("https://books.example.org/opds" to Answer(body = atom)))
        assertIs<Fetched.Ok<CataloguePage>>(fetchPage(transport, url("http://books.example.org/opds")))
        assertEquals(listOf("https://books.example.org/opds"), transport.asked.map { it.value })
    }

    @Test
    fun `each way a fetch fails has its own reason`() {
        val answers = mapOf(
            "https://a.example.org/login" to Answer(status = 401, body = fixture("standardebooks-osd.xml")),
            "https://a.example.org/html" to Answer(body = "<html><body>Hello</body></html>".toByteArray()),
            "https://a.example.org/text" to Answer(body = "Hello".toByteArray()),
            "https://a.example.org/cut" to Answer(body = atom, readFailureAfter = 40),
            "https://a.example.org/refused" to Answer(connectFailure = ConnectException("refused")),
            "https://a.example.org/io" to Answer(connectFailure = IOException("reset")),
        )
        val transport = FakeTransport(answers)
        fun reason(path: String) = assertIs<Fetched.Failed>(fetchPage(transport, url(path))).reason
        assertEquals(HttpError(401), reason("https://a.example.org/login"))
        assertEquals(Unreadable, reason("https://a.example.org/html"))
        assertEquals(Unreadable, reason("https://a.example.org/text"))
        assertEquals(Unreachable, reason("https://a.example.org/cut"))
        assertEquals(Unreachable, reason("https://a.example.org/refused"))
        assertEquals(NoHttps, reason("http://a.example.org/refused"))
        assertEquals(Unreachable, reason("https://a.example.org/io"))
        assertEquals(NoSuchHost, reason("https://offline.example.org/"))
        assertEquals(4, transport.closed)
    }

    @Test
    fun `each failed fetch is logged with its URL, the status or exception behind it, and the failure it became`() {
        val answers = mapOf(
            "https://a.example.org/busy" to Answer(status = 504, landsAt = "https://www.example.org/busy"),
            "https://a.example.org/html" to Answer(body = "<html/>".toByteArray()),
            "https://a.example.org/text" to Answer(body = "Hello".toByteArray()),
            "https://a.example.org/refused" to Answer(connectFailure = ConnectException("refused")),
        )
        val transport = FakeTransport(answers)
        val lines = mutableListOf<String>()
        listOf("busy", "html", "text", "refused").forEach { fetchPage(transport, url("https://a.example.org/$it"), lines::add) }
        fetchSearch(transport, url("https://nowhere.example.org/osd"), lines::add)
        assertEquals(
            listOf(
                "catalogue fetch failed: https://a.example.org/busy: HTTP 504 at https://www.example.org/busy -> HttpError(status=504)",
                "catalogue fetch failed: https://a.example.org/html: not a feed -> Unreadable",
                "catalogue fetch failed: https://a.example.org/text: org.xml.sax.SAXParseException; lineNumber: 1; columnNumber: 1; " +
                    "Content is not allowed in prolog. -> Unreadable",
                "catalogue fetch failed: https://a.example.org/refused: java.net.ConnectException: refused -> Unreachable",
                "catalogue fetch failed: https://nowhere.example.org/osd: java.net.UnknownHostException: https://nowhere.example.org/osd -> NoSuchHost",
            ),
            lines,
        )
        fetchPage(FakeTransport(mapOf("https://a.example.org/" to Answer(body = atom))), url("https://a.example.org/"), lines::add)
        assertEquals(5, lines.size, "a fetch that works logs nothing")
    }

    @Test
    fun `an entity-expansion bomb, or a body past the feed cap, is unreadable`() {
        val huge = """<feed xmlns="http://www.w3.org/2005/Atom"><title>${" ".repeat(MAX_FEED_BYTES.toInt())}</title></feed>""".toByteArray()
        val transport = FakeTransport(
            mapOf(
                "https://a.example.org/bomb" to Answer(body = LAUGHS.toByteArray()),
                "https://a.example.org/huge" to Answer(body = huge),
                "https://a.example.org/osd" to Answer(body = huge),
            ),
        )
        assertEquals(Fetched.Failed(Unreadable), fetchPage(transport, url("https://a.example.org/bomb")))
        assertEquals(Fetched.Failed(Unreadable), fetchPage(transport, url("https://a.example.org/huge")))
        assertEquals(Fetched.Failed(Unreadable), fetchSearch(transport, url("https://a.example.org/osd")))
    }

    @Test
    fun `Gutenberg's search template is an http one, upgraded to https`() {
        val transport = FakeTransport(mapOf("https://www.gutenberg.org/catalog/osd-books.xml" to Answer(body = fixture("gutenberg-osd.xml"))))
        val search = assertIs<Fetched.Ok<SearchTemplate>>(fetchSearch(transport, url("https://www.gutenberg.org/catalog/osd-books.xml"))).value
        val results = search.url("jane austen")
        assertEquals("https://m.gutenberg.org/ebooks/search.opds/?query=jane%20austen", results.value)
        assertTrue(results.upgraded)
    }

    @Test
    fun `Standard Ebooks' search prefers the OPDS template and fills count and page`() {
        val search = parseOpenSearch(fixture("standardebooks-osd.xml").inputStream(), url("https://standardebooks.org/opensearch"))!!
        assertEquals(
            "https://standardebooks.org/feeds/opds/all?query=war%20%26%20peace&per-page=$SEARCH_PAGE_SIZE&page=1",
            search.url("war & peace").value,
        )
    }

    @Test
    fun `a template fills what it knows, leaves unknown optional parameters empty, and rejects the rest`() {
        fun osd(vararg urls: Pair<String, String>) = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
            ${urls.joinToString("") { (type, template) -> """<Url type="$type" template="${template.replace("&", "&amp;")}"/>""" }}
            </OpenSearchDescription>"""
        fun parse(xml: String) = parseOpenSearch(xml.byteInputStream(), url("https://books.example.org/opds/osd.xml"))

        val full = parse(osd("application/atom+xml" to "search?q={searchTerms}&n={count?}&s={startIndex}&l={language}&e={inputEncoding}&o={outputEncoding?}&g={geo:box?}"))!!
        assertEquals("https://books.example.org/opds/search?q=x&n=$SEARCH_PAGE_SIZE&s=1&l=*&e=UTF-8&o=UTF-8&g=", full.url("x").value)

        assertNull(parse(osd("application/atom+xml" to "https://books.example.org/s?q={searchTerms}&t={time:start}")))
        assertNull(parse(osd("application/atom+xml" to "https://books.example.org/all")))
        assertNull(parse(osd("text/html" to "https://books.example.org/s?q={searchTerms}", "application/opds+json" to "https://books.example.org/j?q={searchTerms}")))
        assertNull(parse(osd("application/atom+xml" to "ftp://books.example.org/s?q={searchTerms}")))
        assertEquals(
            "https://books.example.org/ok?q=x",
            parse(osd("application/atom+xml" to "https://books.example.org/bad?q={searchTerms}&t={time:start}", "application/atom+xml" to "/ok?q={searchTerms?}"))?.url("x")?.value,
        )
    }

    @Test
    fun `an OpenSearch description with no Atom template can't be searched`() {
        val transport = FakeTransport(mapOf("https://books.example.org/osd" to Answer(body = "<OpenSearchDescription xmlns=\"http://a9.com/-/spec/opensearch/1.1/\"/>".toByteArray())))
        assertEquals(Fetched.Failed(Unreadable), fetchSearch(transport, url("https://books.example.org/osd")))
    }
}
