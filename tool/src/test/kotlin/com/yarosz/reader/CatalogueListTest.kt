package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class CatalogueListTest {

    private fun url(value: String) = HttpsUrl.parse(value)!!

    private val home = Catalogue("Home books", url("https://books.example.org/opds"))
    private val club = Catalogue("Book club", url("https://club.example.net/feed.xml"))

    private fun names(data: ReadingData) = data.catalogueList().map { it.catalogue.name to it.shipped }

    @Test
    fun `the list starts as the shipped Catalogues, then the ones the reader added, oldest first`() {
        assertEquals(listOf("Project Gutenberg" to true, "Standard Ebooks: new releases" to true), names(ReadingData()))
        val data = ReadingData().withCatalogue(club, now = 20).withCatalogue(home, now = 10)
        assertEquals(listOf("Project Gutenberg" to true, "Standard Ebooks: new releases" to true, "Home books" to false, "Book club" to false), names(data))
    }

    @Test
    fun `shipped is decided by URL, so a typed shipped address keeps the shipped name`() {
        assertTrue(isShipped(url("https://www.gutenberg.org/ebooks.opds/")))
        assertTrue(isShipped(url("http://standardebooks.org/feeds/atom/new-releases")))
        assertFalse(isShipped(url("https://www.gutenberg.org/ebooks.opds/?x")))
        assertFalse(isShipped(home.url))
        val renamed = ReadingData().withoutCatalogue(GUTENBERG.url, 1).withCatalogue(Catalogue("Gutenberg (typed)", GUTENBERG.url), 2)
        assertEquals(null, renamed.catalogues.getValue(GUTENBERG.url.value).name)
        assertEquals(SHIPPED_CATALOGUES, renamed.catalogueList().map { it.catalogue })
    }

    @Test
    fun `a removed shipped Catalogue leaves the list and is offered back, and adding it back restores its place in order`() {
        val removed = ReadingData().withCatalogue(home, 1).withoutCatalogue(GUTENBERG.url, 2)
        assertEquals(listOf("Standard Ebooks: new releases", "Home books"), removed.catalogueList().map { it.catalogue.name })
        assertEquals(listOf(GUTENBERG), removed.removedShipped())
        val back = removed.withCatalogue(GUTENBERG, 3)
        assertEquals(listOf("Project Gutenberg", "Standard Ebooks: new releases", "Home books"), back.catalogueList().map { it.catalogue.name })
        assertEquals(emptyList(), back.removedShipped())
    }

    @Test
    fun `a removed Catalogue the reader added leaves the list and isn't offered back`() {
        val data = ReadingData().withCatalogue(home, 1).withoutCatalogue(home.url, 2)
        assertEquals(SHIPPED_CATALOGUES, data.catalogueList().map { it.catalogue })
        assertEquals(emptyList(), data.removedShipped())
    }

    @Test
    fun `a change is always later than the one it replaces, even when the clock stepped back`() {
        val data = ReadingData().withCatalogue(home, 100).withoutCatalogue(home.url, 50)
        assertEquals(CatalogueRecord("Home books", removed = true, updatedAt = 101), data.catalogues.getValue(home.url.value))
    }

    @Test
    fun `a Catalogue added with no name, or a blank one, shows its host`() {
        val data = ReadingData(catalogues = mapOf(home.url.value to CatalogueRecord(null, false, 1), club.url.value to CatalogueRecord(" ", false, 2)))
        assertEquals(listOf("books.example.org", "club.example.net"), data.catalogueList().drop(2).map { it.catalogue.name })
        assertEquals("books.example.org", home.url.host)
    }

    @Test
    fun `the Catalogue list round-trips through the file, with unknown fields kept`() {
        val data = ReadingData()
            .withCatalogue(home, 10)
            .withoutCatalogue(GUTENBERG.url, 20)
            .let { it.copy(catalogues = it.catalogues + (club.url.value to CatalogueRecord("Book club", false, 30, mapOf("future" to JsonPrimitive(1))))) }
        val text = data.encode()
        assertEquals(data, decodeReadingData(text).getOrThrow())
        assertTrue("\"catalogues\"" in text)
        assertFalse("\"catalogues\"" in ReadingData().encode(), "an untouched list writes nothing")
        assertEquals(ReadingData(), decodeReadingData("""{"schemaVersion":1}""").getOrThrow())
    }

    @Test
    fun `a catalogues field of the wrong type is corruption`() {
        assertTrue(decodeReadingData("""{"catalogues":[]}""").isFailure)
        assertTrue(decodeReadingData("""{"catalogues":{"https://a.example/":{"removed":"yes"}}}""").isFailure)
    }

    @Test
    fun `a merge keeps both sides' Catalogues and, for one on both, the newer change`() {
        val disk = ReadingData().withCatalogue(club, 5).withoutCatalogue(GUTENBERG.url, 30).withCatalogue(home, 10)
        val mine = ReadingData().withCatalogue(home, 1).withoutCatalogue(home.url, 20).withCatalogue(GUTENBERG, 25)
        val merged = merge(disk, mine)
        assertEquals(listOf("Standard Ebooks: new releases", "Book club"), merged.catalogueList().map { it.catalogue.name })
        assertEquals(listOf(GUTENBERG), merged.removedShipped())
        assertTrue(merged.catalogues.getValue(home.url.value).removed)
    }

    @Test
    fun `a Catalogue merge is idempotent and a tie goes to this process`() {
        val disk = ReadingData(catalogues = mapOf(home.url.value to CatalogueRecord("Disk name", false, 7)))
        val mine = ReadingData(catalogues = mapOf(home.url.value to CatalogueRecord("My name", true, 7)))
        assertEquals(mine.catalogues, merge(disk, mine).catalogues)
        assertEquals(merge(disk, mine), merge(merge(disk, mine), mine))
        assertEquals(disk, merge(disk, disk))
    }

    @Test
    fun `a typed address becomes https, http is tried as https once, and anything else is refused`() {
        assertEquals(Fetched.Ok(url("https://books.example.org/opds")), catalogueAddress("  books.example.org/opds "))
        val upgraded = catalogueAddress("http://books.example.org/opds") as Fetched.Ok
        assertEquals("https://books.example.org/opds", upgraded.value.value)
        assertTrue(upgraded.value.upgraded)
        assertEquals(Fetched.Failed(NoHttps), catalogueAddress("ftp://books.example.org/opds"))
        assertEquals(Fetched.Failed(Unreadable), catalogueAddress(""))
        assertEquals(Fetched.Failed(Unreadable), catalogueAddress("http://"))
    }

    @Test
    fun `Add fetches a new address, adds back a removed shipped one, and refuses one already listed`() {
        val data = ReadingData().withCatalogue(home, 1).withoutCatalogue(STANDARD_EBOOKS_NEW_RELEASES.url, 2)
        assertEquals(AddPlan.Fetch(club.url), data.planAdd(club.url.value))
        assertEquals(AddPlan.Duplicate, data.planAdd("books.example.org/opds"))
        assertEquals(AddPlan.Duplicate, data.planAdd("http://www.gutenberg.org/ebooks.opds/"))
        assertEquals(AddPlan.AddBack(STANDARD_EBOOKS_NEW_RELEASES), data.planAdd("standardebooks.org/feeds/atom/new-releases"))
        assertEquals(AddPlan.Invalid(NoHttps), data.planAdd("gopher://x"))
    }

    @Test
    fun `an added Catalogue is named by its feed's title, else its host`() {
        val page = CataloguePage(" Home books ", emptyList(), null, null)
        assertEquals(Catalogue("Home books", home.url), catalogueFrom(page, home.url))
        assertEquals(Catalogue("books.example.org", home.url), catalogueFrom(page.copy(title = ""), home.url))
    }
}
