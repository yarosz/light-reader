package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        assertEquals(CatalogueSearch.Description(url("https://www.gutenberg.org/catalog/osd-books.xml")), page.search)
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
        assertEquals(listOf("Jane Austen", "Herman Melville", "Mary Wollstonecraft Shelley"), page.entries.map { it.byline })
        assertEquals(url("https://www.gutenberg.org/ebooks/1342.opds"), first.opens)
        assertNull(first.download)
    }

    @Test
    fun `a Gutenberg Book page offers each edition, and one Add to Shelf picks the no-images EPUB`() {
        val page = fixture("gutenberg-1342.xml", "https://www.gutenberg.org/ebooks/1342.opds")
        val (noImages, images) = page.entries
        assertEquals(listOf("Jane Austen"), images.authors)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub.noimages", noImages.download?.url?.value)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", images.download?.url?.value)
        assertEquals(24835578L, images.download?.length)
        assertEquals("application/epub+zip", images.download?.type)
        val best = bestDownload(page.entries)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub.noimages", best?.url?.value)
        assertEquals(558381L, best?.length)
        assertEquals("558 KB", formatSize(best!!.length!!))
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", bestAcquisition(page.entries.flatMap { it.acquisitions }, preferImages = true)?.url?.value)
        assertEquals(4, images.acquisitions.size)
        assertNull(images.opens)
        assertEquals(NavigationLink("By Austen, Jane…", url("https://www.gutenberg.org/ebooks/author/68.opds")), images.related.first())
    }

    @Test
    fun `a row's second line is the author, else a short one-line content that isn't a Key-value pair`() {
        val root = fixture("gutenberg-root.xml", "https://www.gutenberg.org/ebooks.opds/")
        assertEquals(listOf("Our most popular books.", "Our latest releases.", "Random books."), root.entries.map { it.byline })
        val book = fixture("gutenberg-1342.xml", "https://www.gutenberg.org/ebooks/1342.opds")
        assertEquals(listOf("Jane Austen", "Jane Austen"), book.entries.map { it.byline })
        val se = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")
        assertEquals(listOf("Winnifred Eaton Reeve", "W. H. Davies"), se.entries.map { it.byline })
        val link = """<link rel="subsection" type="application/atom+xml" href="/b"/>"""
        assertEquals("A, B", entry(link, "<title>T</title><author><name>A</name></author><author><name>B</name></author><content>ignored</content>").byline)
        assertEquals("x".repeat(MAX_BYLINE_CHARS), entry(link, "<title>T</title><content>${"x".repeat(MAX_BYLINE_CHARS)}</content>").byline)
        assertNull(entry(link, "<title>T</title><content>${"x".repeat(MAX_BYLINE_CHARS + 1)}</content>").byline)
        assertNull(entry(link, "<title>T</title><content>EBook No.: 1342</content>").byline)
        assertNull(entry(link, """<title>T</title><content type="html">&lt;p&gt;One&lt;/p&gt;&lt;p&gt;Two&lt;/p&gt;</content>""").byline)
        assertNull(entry(link, "<title>T</title><summary>Only a summary</summary>").byline)
    }

    @Test
    fun `one download for a page is picked only when every entry is the same Book`() {
        val se = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")
        assertTrue(se.entries.size > 1 && se.entries.all { it.download != null })
        assertNull(bestDownload(se.entries))
        assertEquals(se.entries[0].download, bestDownload(se.entries.take(1)))
        assertNull(bestDownload(emptyList()))
    }

    @Test
    fun `Gutenberg's metadata dump is no summary, Standard Ebooks' prose is`() {
        val gutenberg = fixture("gutenberg-1342.xml", "https://www.gutenberg.org/ebooks/1342.opds")
        assertEquals(listOf(null, null), gutenberg.entries.map { it.summary })
        val se = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")
        assertTrue(se.entries.all { !it.summary.isNullOrEmpty() })
        assertTrue(isMetadataList("This edition has images.\nTitle: Pride and Prejudice\nEBook No.: 1342\nReading Level: Reading ease score: 69.2"))
        assertFalse(isMetadataList("Note: a short novel.\nIt was a dark and stormy night.\nThe end."))
        assertFalse(isMetadataList("Title: T\nAuthor: A"))
    }

    @Test
    fun `only a simple inverted author name is un-inverted`() {
        listOf(
            "Austen, Jane" to "Jane Austen",
            "Austen, Jane, 1775-1817" to "Jane Austen",
            "Shelley, Mary Wollstonecraft, 1797-1851" to "Mary Wollstonecraft Shelley",
            "Du Maurier, George, 1834-" to "George Du Maurier",
            "Scott, Walter, -1832" to "Walter Scott",
            "Anonymous, -1650" to "Anonymous, -1650",
            "Marlowe, Christopher, 1564?-1593" to "Christopher Marlowe",
            "O'Brien, Fitz-James" to "Fitz-James O'Brien",
            "Brontë, Charlotte" to "Charlotte Brontë",
            "Balzac, Honoré de" to "Honoré de Balzac",
            "Balzac, Honoré de, 1799-1850" to "Honoré de Balzac",
            "Marcus Aurelius, Emperor of Rome" to "Marcus Aurelius, Emperor of Rome",
            "Marcus Aurelius, Emperor of Rome, 121-180" to "Marcus Aurelius, Emperor of Rome, 121-180",
            "Palme, Olof" to "Olof Palme",
            "Doyle, Arthur Conan, Sir, 1859-1930" to "Doyle, Arthur Conan, Sir, 1859-1930",
            "Tolkien, J. R. R. (John Ronald Reuel), 1892-1973" to "Tolkien, J. R. R. (John Ronald Reuel), 1892-1973",
            "Smith, John, Jr." to "Smith, John, Jr.",
            "Austen, Jane; Brontë, Charlotte" to "Austen, Jane; Brontë, Charlotte",
            "Homer, 751? BCE-651? BCE" to "Homer, 751? BCE-651? BCE",
            "Various" to "Various",
            "United States. Central Intelligence Agency" to "United States. Central Intelligence Agency",
            "Winnifred Eaton Reeve" to "Winnifred Eaton Reeve",
            "Austen, " to "Austen, ",
        ).forEach { (name, shown) -> assertEquals(shown, displayAuthor(name), name) }
    }

    @Test
    fun `a lowercase title of nobility is dropped from an author as life dates are, and nothing else is`() {
        listOf(
            "Tolstoy, Leo, graf" to "Leo Tolstoy",
            "Tolstoy, Leo, graf, 1828-1910" to "Leo Tolstoy",
            "Leo Tolstoy, graf" to "Leo Tolstoy",
            "graf Leo Tolstoy" to "Leo Tolstoy",
            "Tolstoy, Lev Nikolaevich, graf" to "Lev Nikolaevich Tolstoy",
            "Kropotkin, Petr Alekseevich, kniaz, 1842-1921" to "Petr Alekseevich Kropotkin",
            "Kropotkin, Petr Alekseevich, kniaz" to "Petr Alekseevich Kropotkin",
            "kniaz Petr Alekseevich Kropotkin" to "Petr Alekseevich Kropotkin",
            "hrabia Zygmunt Krasiński" to "Zygmunt Krasiński",
            "Baroness Emmuska Orczy Orczy" to "Baroness Emmuska Orczy Orczy",
            "Tolstoy, Leo, graf, Maude, Louise" to "Tolstoy, Leo, graf, Maude, Louise",
            "Tolstoy, Leo, graf, Maude, Louise, Maude, Aylmer" to "Tolstoy, Leo, graf, Maude, Louise, Maude, Aylmer",
            "Grafton, Sue" to "Sue Grafton",
            "Corvo, Baron" to "Baron Corvo",
            "Baron Corvo" to "Baron Corvo",
            "Chateaubriand, François-René, vicomte de, 1768-1848" to "Chateaubriand, François-René, vicomte de, 1768-1848",
            "graf" to "graf",
            "count Dracula's guests" to "count Dracula's guests",
        ).forEach { (name, shown) -> assertEquals(shown, displayAuthor(name), name) }
    }

    @Test
    fun `a row's content line loses a leading title of nobility only when a name follows it`() {
        assertEquals("Leo Tolstoy", entryByline(emptyList(), "graf Leo Tolstoy"))
        assertEquals("Petr Alekseevich Kropotkin", entryByline(emptyList(), "kniaz Petr Alekseevich Kropotkin"))
        assertEquals("Zygmunt Krasiński", entryByline(emptyList(), "hrabia Zygmunt Krasiński"))
        assertEquals("Honoré de Balzac", entryByline(emptyList(), "count Honoré de Balzac"))
        assertEquals("baron of the Exchequer", entryByline(emptyList(), "baron of the Exchequer"))
        assertEquals("prince Hal and the tavern", entryByline(emptyList(), "prince Hal and the tavern"))
        assertEquals("Our most popular books.", entryByline(emptyList(), "Our most popular books."))
    }

    @Test
    fun `a download size reads in KB below a megabyte and MB above`() {
        listOf(
            0L to "1 KB",
            1_499L to "1 KB",
            558_381L to "558 KB",
            999_499L to "999 KB",
            999_500L to "1.0 MB",
            24_835_578L to "24.8 MB",
            99_949_999L to "99.9 MB",
            99_950_000L to "100 MB",
            314_572_800L to "315 MB",
        ).forEach { (bytes, shown) -> assertEquals(shown, formatSize(bytes), "$bytes") }
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
        assertEquals(CatalogueSearch.Description(url("https://standardebooks.org/opensearch")), page.search)
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
    fun `a reference to no character, NUL or a lone surrogate, stays as written`() {
        assertEquals("&#0; &#x0; &#xD800; &#56320; \uD83D\uDE00", decodeEntities("&#0; &#x0; &#xD800; &#56320; &#x1F600;"))
    }

    @Test
    fun `a title or summary longer than the text cap is cut at the cap`() {
        val e = entry(
            """<link rel="enclosure" type="application/epub+zip" href="b.epub"/>""",
            "<title>${"t".repeat(MAX_TEXT_CHARS + 10)}</title><summary type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\">${"<p>s</p>".repeat(MAX_TEXT_CHARS)}</div></summary>",
        )
        assertEquals(MAX_TEXT_CHARS, e.title.length)
        assertTrue(e.summary!!.length <= MAX_TEXT_CHARS, "${e.summary!!.length}")
    }

    @Test
    fun `content given by reference, or blank, is no summary`() {
        val link = """<link rel="enclosure" type="application/epub+zip" href="b.epub"/>"""
        assertNull(entry(link, """<title>T</title><content type="text/html" src="about.html"/>""").summary)
        assertNull(entry(link, """<title>T</title><summary>   </summary>""").summary)
    }

    @Test
    fun `an Atom search link is a search only when it is a template, a feed without links has no next or search`() {
        val page = feed(
            """<title>T</title><link rel="search" type="application/atom+xml" href="search.xml"/>
               <author><name>Feed author</name></author><entry><title>E</title><link rel="enclosure" type="application/epub+zip" href="b.epub"/></entry>""",
        )!!
        assertNull(page.search)
        assertNull(page.next)
        assertEquals(emptyList(), page.entries.single().authors)
        assertNull(feed("<title>T</title>")!!.search)
    }

    @Test
    fun `Calibre's Atom search template is ready to fill, and wins over an OpenSearch description`() {
        val calibre = """<link rel="search" type="application/atom+xml" href="/opds/search/{searchTerms}" title="Search"/>"""
        val osd = """<link rel="search" type="application/opensearchdescription+xml" href="/opds/osd.xml"/>"""
        listOf(calibre, osd + calibre, calibre + osd).forEach { links ->
            val search = feed("<title>Calibre</title>$links", at = "https://calibre.example.org/opds")!!.search
            val ready = assertIs<CatalogueSearch.Ready>(search, links)
            assertEquals("https://calibre.example.org/opds/search/jane%20austen", ready.template.url("jane austen").value)
        }
        assertEquals(
            CatalogueSearch.Description(url("https://calibre.example.org/opds/osd.xml")),
            feed("<title>T</title>$osd", at = "https://calibre.example.org/opds")!!.search,
        )
    }

    @Test
    fun `an OPDS complete-entry link is the entry's details, never a feed it opens`() {
        val e = entry(
            """<link rel="alternate" type="application/atom+xml;type=entry;profile=opds-catalog" href="entry/1.xml"/>
               <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="1.epub"/>""",
        )
        assertNull(e.opens)
        assertEquals(url("https://books.example.org/opds/entry/1.xml"), e.details)
        val spaced = entry("""<link type="application/atom+xml; type = entry" href="entry/2.xml"/><link type="application/atom+xml;profile=opds-catalog" href="feed.xml"/>""")
        assertEquals(url("https://books.example.org/opds/feed.xml"), spaced.opens)
        assertEquals(url("https://books.example.org/opds/entry/2.xml"), spaced.details)
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
    fun `EPUB beats kepub, no images or unmarked beat images, then EPUB3 beats EPUB2, ties keep feed order`() {
        val kepub = epub("a.kepub.epub", type = "application/kepub+zip")
        val epub2 = epub("1.epub.images")
        val epub3 = epub("1.epub3.images")
        val noImages = epub("1.epub.noimages", "EPUB (no images)")
        val plain = epub("1.epub")
        val titled3 = epub("download?id=1", "EPUB 3")
        assertEquals(plain, bestAcquisition(listOf(kepub, plain)))
        assertEquals(kepub, bestAcquisition(listOf(kepub, epub("k.azw3", type = "application/x-mobipocket-ebook"))))
        assertEquals(noImages, bestAcquisition(listOf(epub3, epub2, noImages)))
        assertEquals(epub3, bestAcquisition(listOf(epub2, epub3)))
        assertEquals(titled3, bestAcquisition(listOf(epub2, titled3)))
        assertEquals(titled3, bestAcquisition(listOf(noImages, titled3)))
        assertEquals(noImages, bestAcquisition(listOf(noImages, plain)))
        assertEquals(plain, bestAcquisition(listOf(plain, noImages)))
        assertEquals(plain, bestAcquisition(listOf(epub3, plain)))
        assertEquals(plain, bestAcquisition(listOf(plain, epub("2.epub"))))
        assertNull(bestAcquisition(emptyList()))
    }

    @Test
    fun `with images preferred, images beat unmarked beat none, then EPUB3 beats EPUB2`() {
        val epub2 = epub("1.epub.images")
        val epub3 = epub("1.epub3.images")
        val noImages = epub("1.epub.noimages", "EPUB (no images)")
        val plain = epub("1.epub")
        assertEquals(epub3, bestAcquisition(listOf(noImages, epub2, epub3), preferImages = true))
        assertEquals(epub2, bestAcquisition(listOf(noImages, plain, epub2), preferImages = true))
        assertEquals(plain, bestAcquisition(listOf(noImages, plain), preferImages = true))
    }
}
