package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xml.sax.SAXException

/**
 * Parses real Catalogue responses fetched on 2026-09-27 and kept under src/test/fixtures/catalogues,
 * trimmed to their first few entries, with Gutenberg's inline data: thumbnails shortened.
 */
class CatalogueTest {

    private fun url(value: String) = HttpsUrl.parse(value)!!

    private fun fixture(name: String, at: String): CataloguePage =
        parseFeed(File("src/test/fixtures/catalogues/$name").inputStream(), url(at))!!

    private fun feed(body: String, at: String = "https://books.example.org/opds/root.xml"): CataloguePage? =
        parseFeed(
            """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="urn:x">$body</feed>""".byteInputStream(),
            url(at),
        )

    private fun entry(links: String, extra: String = "<title>Book</title>") = feed("<entry>$extra$links</entry>")!!.entries.single()

    private fun epub(href: String, title: String? = null, type: String = "application/epub+zip") =
        Acquisition(url("https://books.example.org/$href"), type, title, null)

    @Test
    fun `the shipped Catalogues are Gutenberg and Standard Ebooks' new releases, both https`() {
        assertEquals(
            listOf(
                "Project Gutenberg" to "https://www.gutenberg.org/ebooks.opds/",
                "Standard Ebooks: new releases" to "https://standardebooks.org/feeds/atom/new-releases",
            ),
            SHIPPED_CATALOGUES.map { it.name to it.url.value },
        )
        assertTrue(SHIPPED_CATALOGUES.none { it.url.upgraded })
    }

    @Test
    fun `Gutenberg's root is a navigation feed with relative links and a search`() {
        val page = fixture("gutenberg-root.xml", "https://www.gutenberg.org/ebooks.opds/")
        assertEquals("Project Gutenberg", page.title)
        assertEquals(listOf("Popular", "Latest", "Random"), page.entries.map { it.title })
        val popular = page.entries[0]
        assertEquals("Our most popular books.", popular.summary)
        assertEquals(url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"), popular.opens)
        assertEquals(emptyList(), popular.acquisitions)
        assertEquals(url("https://www.gutenberg.org/catalog/osd-books.xml"), page.search)
        assertNull(page.next)
    }

    @Test
    fun `a Gutenberg list pages on with rel next, and its entries open each Book's page`() {
        val page = fixture("gutenberg-popular.xml", "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads")
        assertEquals(url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&start_index=26"), page.next)
        val first = page.entries[0]
        assertEquals("Pride and Prejudice", first.title)
        assertEquals(emptyList(), first.authors)
        assertEquals("Jane Austen", first.summary)
        assertEquals(url("https://www.gutenberg.org/ebooks/1342.opds"), first.opens)
        assertNull(first.download)
    }

    @Test
    fun `a Gutenberg Book page offers each edition, and the images edition's EPUB3 is chosen`() {
        val page = fixture("gutenberg-1342.xml", "https://www.gutenberg.org/ebooks/1342.opds")
        val (noImages, images) = page.entries
        assertEquals(listOf("Austen, Jane"), images.authors)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub.noimages", noImages.download?.url?.value)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", images.download?.url?.value)
        assertEquals(24835578L, images.download?.length)
        assertEquals("application/epub+zip", images.download?.type)
        assertEquals(
            "https://www.gutenberg.org/ebooks/1342.epub3.images",
            bestAcquisition(page.entries.flatMap { it.acquisitions })?.url?.value,
        )
        assertEquals(4, images.acquisitions.size)
        assertNull(images.opens)
        assertEquals(NavigationLink("By Austen, Jane…", url("https://www.gutenberg.org/ebooks/author/68.opds")), images.related.first())
        assertTrue(images.summary!!.startsWith("This edition has images.\nTitle: Pride and Prejudice\n"), images.summary)
    }

    @Test
    fun `Standard Ebooks' new releases are plain Atom with EPUB enclosures, and the compatible epub is chosen`() {
        val page = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")
        assertEquals("Standard Ebooks - Newest Ebooks", page.title)
        val nibs = page.entries[0]
        assertEquals("His Royal Nibs", nibs.title)
        assertEquals(listOf("Winnifred Eaton Reeve"), nibs.authors)
        assertEquals("A young Englishman finds a new purpose in life as a hired hand at a ranch in Canada.", nibs.summary)
        assertEquals(
            "https://standardebooks.org/ebooks/winnifred-eaton-reeve/his-royal-nibs/downloads/winnifred-eaton-reeve_his-royal-nibs.epub?source=feed",
            nibs.download?.url?.value,
        )
        assertEquals(listOf("application/epub+zip", "application/epub+zip", "application/kepub+zip", "application/x-mobipocket-ebook", "application/xhtml+xml"), nibs.acquisitions.map { it.type })
        assertNull(nibs.opens)
        assertEquals(url("https://standardebooks.org/opensearch"), page.search)
    }

    @Test
    fun `Standard Ebooks' OPDS search results use open-access acquisitions and page on`() {
        val page = fixture("standardebooks-opds-search.xml", "https://standardebooks.org/feeds/opds/all?query=the&per-page=5&page=1")
        val book = page.entries.single()
        assertEquals("The Three Impostors", book.title)
        assertEquals(listOf("Arthur Machen"), book.authors)
        assertEquals("https://standardebooks.org/ebooks/arthur-machen/the-three-impostors/downloads/arthur-machen_the-three-impostors.epub?source=feed", book.download?.url?.value)
        assertEquals(url("https://standardebooks.org/feeds/opds/all?query=the&page=2&per-page=5"), page.next)
    }

    @Test
    fun `links resolve against the page, http becomes https, and other schemes are dropped`() {
        val e = entry(
            """<link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="../shelf/new.xml"/>
               <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="http://books.example.org/a.epub"/>
               <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="data:application/epub+zip;base64,UEsDBA=="/>
               <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="ftp://books.example.org/b.epub"/>""",
        )
        assertEquals(url("https://books.example.org/shelf/new.xml"), e.opens)
        assertEquals(listOf("https://books.example.org/a.epub"), e.acquisitions.map { it.url.value })
        assertTrue(e.acquisitions.single().url.upgraded)
    }

    @Test
    fun `only free acquisitions and enclosures count, not buy, borrow, sample, or images`() {
        val e = entry(
            listOf("buy", "borrow", "sample", "subscribe").joinToString("") {
                """<link rel="http://opds-spec.org/acquisition/$it" type="application/epub+zip" href="$it.epub"/>"""
            } + """<link rel="http://opds-spec.org/image/thumbnail" type="image/png" href="t.png"/>
                   <link rel="enclosure" type="application/epub+zip" href="free.epub" title="  Free  " length="12"/>""",
        )
        assertEquals(listOf(epub("opds/free.epub", "Free").copy(length = 12)), e.acquisitions)
    }

    @Test
    fun `an entry opens its first navigation link, never self or up, and related links need a title`() {
        val e = entry(
            """<link rel="self" type="application/atom+xml" href="self.xml"/>
               <link rel="up" type="application/atom+xml" href="up.xml"/>
               <link rel="alternate" type="text/html" href="page.html"/>
               <link rel="related" type="application/atom+xml" href="untitled.xml"/>
               <link rel="related" type="application/atom+xml" href="author.xml" title="By the author"/>
               <link type="application/atom+xml" href="first.xml"/>
               <link rel="http://opds-spec.org/sort/new" type="application/atom+xml" href="second.xml"/>""",
        )
        assertEquals(url("https://books.example.org/opds/first.xml"), e.opens)
        assertEquals(listOf(NavigationLink("By the author", url("https://books.example.org/opds/author.xml"))), e.related)
    }

    @Test
    fun `an entry with nothing to open or download is dropped`() {
        val page = feed(
            """<entry><title>Only a web page</title><link rel="alternate" type="text/html" href="p.html"/></entry>
               <entry><title>Only Kindle</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook" href="k.azw3"/></entry>""",
        )!!
        assertEquals(listOf("Only Kindle"), page.entries.map { it.title })
        assertNull(page.entries.single().download)
    }

    @Test
    fun `text, html and xhtml titles and summaries become plain lines`() {
        val e = entry(
            """<link rel="enclosure" type="application/epub+zip" href="b.epub"/>""",
            """<title type="html">&lt;i&gt;Moby-Dick&lt;/i&gt; &amp;amp; more</title>
               <author><name> Herman   Melville </name><x:role>author</x:role></author><author><name/></author>
               <summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Call me <em>Ishmael</em>.</p><p>Some years
               ago&#8212;never mind</p></div></summary>
               <content type="text">ignored when there is a summary</content>""",
        )
        assertEquals("Moby-Dick & more", e.title)
        assertEquals(listOf("Herman Melville"), e.authors)
        assertEquals("Call me Ishmael.\nSome years ago—never mind", e.summary)
    }

    @Test
    fun `html content decodes numeric and named entities, and keeps paragraphs as lines`() {
        val e = entry(
            """<link rel="enclosure" type="application/epub+zip" href="b.epub"/>""",
            """<title>T</title><content type="html">&lt;p&gt;One&amp;nbsp;&amp;#x2014;&amp;#8212;&amp;quot;&amp;apos;&amp;lt;&amp;gt;&lt;br/&gt;Two&amp;#99999999999; &amp;copy;&lt;/p&gt;</content>""",
        )
        assertEquals("One ——\"'<>\nTwo&#99999999999; &copy;", e.summary)
    }

    @Test
    fun `content given by reference, or blank, is no summary`() {
        val link = """<link rel="enclosure" type="application/epub+zip" href="b.epub"/>"""
        assertNull(entry(link, """<title>T</title><content type="text/html" src="about.html"/>""").summary)
        assertNull(entry(link, """<title>T</title><summary>   </summary>""").summary)
    }

    @Test
    fun `a feed-level search must be an OpenSearch description, a feed without links has no next or search`() {
        val page = feed(
            """<title>T</title><link rel="search" type="application/atom+xml" href="search.xml"/>
               <author><name>Feed author</name></author><entry><title>E</title><link rel="enclosure" type="application/epub+zip" href="b.epub"/></entry>""",
        )!!
        assertNull(page.search)
        assertNull(page.next)
        assertEquals(emptyList(), page.entries.single().authors)
    }

    @Test
    fun `a document that isn't an Atom feed is no page, and one that isn't XML throws`() {
        assertNull(parseFeed("<html><body>Not found</body></html>".byteInputStream(), url("https://books.example.org/")))
        assertNull(parseFeed("""<feed xmlns="urn:not-atom"><title>T</title></feed>""".byteInputStream(), url("https://books.example.org/")))
        assertFailsWith<SAXException> { parseFeed("Service unavailable".byteInputStream(), url("https://books.example.org/")) }
    }

    @Test
    fun `a feed's external entities are never fetched`() {
        val page = parseFeed(
            """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY ext SYSTEM "http://books.example.org/secret">]>
               <feed xmlns="http://www.w3.org/2005/Atom"><title>A&ext;B</title></feed>""".byteInputStream(),
            url("https://books.example.org/"),
        )
        assertEquals("AB", page?.title)
    }

    @Test
    fun `EPUB beats kepub, EPUB3 beats EPUB2, images beat unmarked beat none, ties keep feed order`() {
        val kepub = epub("a.kepub.epub", type = "application/kepub+zip")
        val epub2 = epub("1.epub.images")
        val epub3 = epub("1.epub3.images")
        val noImages = epub("1.epub.noimages", "EPUB (no images)")
        val plain = epub("1.epub")
        val titled3 = epub("download?id=1", "EPUB 3")
        assertEquals(plain, bestAcquisition(listOf(kepub, plain)))
        assertEquals(kepub, bestAcquisition(listOf(kepub, epub("k.azw3", type = "application/x-mobipocket-ebook"))))
        assertEquals(epub3, bestAcquisition(listOf(noImages, epub2, epub3)))
        assertEquals(titled3, bestAcquisition(listOf(epub2, titled3)))
        assertEquals(epub2, bestAcquisition(listOf(noImages, plain, epub2)))
        assertEquals(plain, bestAcquisition(listOf(noImages, plain)))
        assertEquals(noImages, bestAcquisition(listOf(noImages, epub("no-images.epub"))))
        assertEquals(plain, bestAcquisition(listOf(plain, epub("2.epub"))))
        assertNull(bestAcquisition(emptyList()))
    }
}
