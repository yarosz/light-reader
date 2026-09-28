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
    private val popularAt = url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads")
    private val newReleases = fixture("standardebooks-new-releases.xml", "https://standardebooks.org/feeds/atom/new-releases")

    @Test
    fun `a Gutenberg list entry opens its Book page, which shows as the Book's detail`() {
        val entry = popular.entries.first()
        val target = entryTarget(entry)
        assertEquals(PageSource.Feed(url("https://www.gutenberg.org/ebooks/1342.opds"), entry), target)
        assertEquals(PageState.Book(bookPage.entries), pageState(bookPage, target!!, url("https://www.gutenberg.org/ebooks/1342.opds")))
        assertEquals("Pride and Prejudice", pageTitle(GUTENBERG, target))
    }

    @Test
    fun `a Standard Ebooks release has a download of its own, so it opens its detail with nothing to fetch`() {
        val release = newReleases.entries.first()
        assertEquals(PageSource.Entry(release), entryTarget(release))
        val listing = assertIs<PageState.Listing>(pageState(newReleases, PageSource.Root, STANDARD_EBOOKS_NEW_RELEASES.url))
        assertEquals(newReleases.entries, listing.entries)
        assertIs<CatalogueSearch.Description>(listing.search)
    }

    @Test
    fun `a list, even one whose entries are all one Book, is a list unless an entry opened it`() {
        assertIs<PageState.Listing>(pageState(bookPage, PageSource.Root, GUTENBERG.url))
        assertIs<PageState.Listing>(pageState(bookPage, PageSource.Search(CatalogueSearch.Description(url("https://x.example/osd")), "austen"), url("https://x.example/?q=austen")))
        assertIs<PageState.Listing>(pageState(popular, PageSource.Feed(popular.next!!, popular.entries.first()), popularAt))
    }

    @Test
    fun `More appends the next page and moves on to its next`() {
        val first = pageState(popular, PageSource.Root, popularAt) as PageState.Listing
        val more = appendPage(first.copy(more = More.Loading), popular.copy(next = null), popular.next!!)
        assertEquals(popular.entries + popular.entries, more.entries)
        assertNull(more.next)
        assertEquals(More.Idle, more.more)
    }

    @Test
    fun `More ends when the next page is one this list already fetched`() {
        val second = url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&start_index=26")
        assertNull((pageState(popular.copy(next = popularAt), PageSource.Root, popularAt) as PageState.Listing).next, "a page naming itself")
        val first = pageState(popular.copy(next = second), PageSource.Root, popularAt) as PageState.Listing
        assertEquals(second, first.next)
        val back = appendPage(first, popular.copy(next = popularAt), second)
        assertNull(back.next, "the second page leads back to the first")
        assertEquals(setOf(popularAt, second), back.fetched)
        val onward = appendPage(first, popular.copy(next = url("https://www.gutenberg.org/ebooks/search.opds/?start_index=51")), second)
        assertEquals(url("https://www.gutenberg.org/ebooks/search.opds/?start_index=51"), onward.next)
    }

    @Test
    fun `a list holds at most MAX_LIST_ENTRIES entries, and More ends there`() {
        val entry = popular.entries.first()
        val page = { n: Int, next: String -> popular.copy(entries = List(n) { entry.copy(title = "Book $it") }, next = url(next)) }
        var listing = pageState(page(25, "https://books.example.org/p1"), PageSource.Root, url("https://books.example.org/p0")) as PageState.Listing
        var pages = 1
        while (listing.next != null && pages < 100) {
            listing = appendPage(listing, page(25, "https://books.example.org/p${pages + 1}"), listing.next!!)
            pages++
        }
        assertEquals(MAX_LIST_ENTRIES, listing.entries.size)
        assertEquals(MAX_LIST_ENTRIES / 25, pages)
        val huge = pageState(page(MAX_LIST_ENTRIES + 1, "https://books.example.org/p1"), PageSource.Root, url("https://books.example.org/p0")) as PageState.Listing
        assertEquals(MAX_LIST_ENTRIES, huge.entries.size)
        assertNull(huge.next)
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
