package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CataloguePagesTest {

    private fun url(value: String) = HttpsUrl.parse(value)!!

    private fun fixture(name: String, at: String) = parseFeed(File("src/test/fixtures/catalogues/$name").inputStream(), url(at))!!

    private val popular = fixture("gutenberg-popular.xml", "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads")
    private val bookPage = fixture("gutenberg-1342.xml", "https://www.gutenberg.org/ebooks/1342.opds")
    private val newReleases = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")

    @Test
    fun `a Gutenberg list entry opens its Book page, which shows as the Book's detail`() {
        val entry = popular.entries.first()
        val target = entryTarget(entry)
        assertEquals(PageSource.Feed(url("https://www.gutenberg.org/ebooks/1342.opds"), entry), target)
        assertEquals(PageState.Book(bookPage.entries), pageState(bookPage, target!!))
        assertEquals("Pride and Prejudice", pageTitle(GUTENBERG, target))
    }

    @Test
    fun `a Standard Ebooks release has a download of its own, so it opens its detail with nothing to fetch`() {
        val release = newReleases.entries.first()
        assertEquals(PageSource.Entry(release), entryTarget(release))
        val listing = assertIs<PageState.Listing>(pageState(newReleases, PageSource.Root))
        assertEquals(newReleases.entries, listing.entries)
        assertIs<CatalogueSearch.Description>(listing.search)
    }

    @Test
    fun `a list, even one whose entries are all one Book, is a list unless an entry opened it`() {
        assertIs<PageState.Listing>(pageState(bookPage, PageSource.Root))
        assertIs<PageState.Listing>(pageState(bookPage, PageSource.Search(CatalogueSearch.Description(url("https://x.example/osd")), "austen")))
        assertIs<PageState.Listing>(pageState(popular, PageSource.Feed(popular.next!!, popular.entries.first())))
    }

    @Test
    fun `More appends the next page and moves on to its next`() {
        val first = pageState(popular, PageSource.Root) as PageState.Listing
        val more = appendPage(first.copy(more = More.Loading), popular.copy(next = null))
        assertEquals(popular.entries + popular.entries, more.entries)
        assertNull(more.next)
        assertEquals(More.Idle, more.more)
    }

    @Test
    fun `Standard Ebooks' search says it covers all of Standard Ebooks, any other says Search`() {
        assertEquals("Search all Standard Ebooks", searchPlaceholder(STANDARD_EBOOKS_NEW_RELEASES))
        assertEquals("Search", searchPlaceholder(GUTENBERG))
        assertEquals("Search", searchPlaceholder(Catalogue("Home", url("https://books.example.org/opds"))))
    }

    @Test
    fun `the detail page's author is the first Edition that names one, and a summary repeating it is dropped`() {
        assertEquals("Jane Austen", detailAuthor(bookPage.entries))
        assertNull(detailSummary(bookPage.entries), "Gutenberg's metadata dump is no summary")
        assertNull(detailSummary(listOf(popular.entries.first())), "a list entry's content that is only the author")
        val release = newReleases.entries.first()
        assertEquals(release.authors.joinToString(", "), detailAuthor(listOf(release)))
        assertEquals(release.summary, detailSummary(listOf(release)))
    }

    @Test
    fun `an entry with nothing to open or download goes nowhere`() {
        assertNull(entryTarget(popular.entries.first().copy(opens = null)))
    }
}
