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
        assertEquals(null, renamed.catalogues.getValue(GUTENBERG.url.catalogueKey).name)
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
        assertEquals(CatalogueRecord("Home books", null, removed = true, updatedAt = 101), data.catalogues.getValue(home.url.catalogueKey))
    }

    @Test
    fun `a Catalogue added with no name, or a blank one, shows its host`() {
        val data = ReadingData(catalogues = mapOf(home.url.catalogueKey to CatalogueRecord(null, null, false, 1), club.url.catalogueKey to CatalogueRecord(" ", null, false, 2)))
        assertEquals(listOf("books.example.org", "club.example.net"), data.catalogueList().drop(2).map { it.catalogue.name })
        assertEquals("books.example.org", home.url.host)
    }

    @Test
    fun `the Catalogue list round-trips through the file, with unknown fields kept`() {
        val data = ReadingData()
            .withCatalogue(home, 10)
            .withoutCatalogue(GUTENBERG.url, 20)
            .withCatalogue(Catalogue("Slashed", url("https://slash.example.org/opds/")), 25)
            .let { it.copy(catalogues = it.catalogues + (club.url.catalogueKey to CatalogueRecord("Book club", null, false, 30, mapOf("future" to JsonPrimitive(1))))) }
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
        assertTrue(merged.catalogues.getValue(home.url.catalogueKey).removed)
    }

    @Test
    fun `a Catalogue merge is idempotent, and at a tie a removal beats an addition in either order`() {
        val added = ReadingData(catalogues = mapOf(home.url.catalogueKey to CatalogueRecord("Home books", null, false, 7)))
        val removed = ReadingData(catalogues = mapOf(home.url.catalogueKey to CatalogueRecord("Home books", null, true, 7)))
        assertEquals(removed.catalogues, merge(added, removed).catalogues)
        assertEquals(removed.catalogues, merge(removed, added).catalogues)
        assertEquals(merge(added, removed), merge(merge(added, removed), removed))
        assertEquals(added, merge(added, added))
    }

    @Test
    fun `a Catalogue merge keeps the losing record's unknown fields, the winner's taking a clash`() {
        val key = home.url.catalogueKey
        val older = CatalogueRecord("Old name", null, false, 5, mapOf("future" to JsonPrimitive("kept"), "both" to JsonPrimitive("old")))
        val newer = CatalogueRecord("New name", null, true, 9, mapOf("both" to JsonPrimitive("new")))
        val expected = newer.copy(extras = mapOf("future" to JsonPrimitive("kept"), "both" to JsonPrimitive("new")))
        assertEquals(expected, merge(ReadingData(catalogues = mapOf(key to older)), ReadingData(catalogues = mapOf(key to newer))).catalogues.getValue(key))
        assertEquals(expected, merge(ReadingData(catalogues = mapOf(key to newer)), ReadingData(catalogues = mapOf(key to older))).catalogues.getValue(key))
    }

    @Test
    fun `one Catalogue has one key, whatever its host's case, trailing slash or empty path`() {
        assertEquals(url("https://books.example.org/opds").catalogueKey, url("https://Books.Example.ORG/opds/").catalogueKey)
        assertEquals(url("https://books.example.org").catalogueKey, url("https://books.example.org/").catalogueKey)
        assertEquals(url("https://books.example.org:443/opds#top").catalogueKey, url("http://books.example.org/opds").catalogueKey)
        assertEquals("https://books.example.org/", url("https://BOOKS.example.org").catalogueKey.value)
        assertTrue(url("https://books.example.org/opds?a=1").catalogueKey != url("https://books.example.org/opds?a=2").catalogueKey)
        assertTrue(url("https://books.example.org/opds").catalogueKey != url("https://books.example.org/opds//").catalogueKey, "only one slash is ignored")
    }

    @Test
    fun `a shipped address is recognised without its trailing slash, in any host case, and a bare host matches its root`() {
        assertTrue(isShipped(url("https://www.gutenberg.org/ebooks.opds")))
        assertTrue(isShipped(url("https://WWW.Gutenberg.org/ebooks.opds/")))
        assertTrue(isShipped(url("https://standardebooks.org/feeds/atom/new-releases/")))
        val data = ReadingData().withoutCatalogue(GUTENBERG.url, 1)
        assertEquals(AddPlan.AddBack(GUTENBERG), data.planAdd("www.gutenberg.org/ebooks.opds"))
        assertEquals(AddPlan.Duplicate, data.planAdd("STANDARDEBOOKS.org/feeds/atom/new-releases/"))
        val root = ReadingData().withCatalogue(Catalogue("Root", url("https://root.example.org")), 1)
        assertEquals(AddPlan.Duplicate, root.planAdd("https://root.example.org/"))
        assertEquals(AddPlan.Duplicate, ReadingData().withCatalogue(home, 1).planAdd("https://books.example.org/opds/"))
    }

    @Test
    fun `an added Catalogue is fetched at the address it was added with, under its key`() {
        val slashed = Catalogue("Slashed", url("https://Slash.example.org/opds/"))
        val data = ReadingData().withCatalogue(slashed, 1)
        assertEquals(setOf(url("https://slash.example.org/opds").catalogueKey), data.catalogues.keys)
        assertEquals(slashed, data.catalogueList().last().catalogue)
        assertEquals(SHIPPED_CATALOGUES, data.withoutCatalogue(url("https://slash.example.org/opds"), 2).catalogueList().map { it.catalogue })
    }

    @Test
    fun `two keys for one Catalogue merge at decode, whichever the file lists first`() {
        val newer = """"https://books.example.org/opds":{"name":"Newer","removed":true,"updatedAt":9,"future":1}"""
        val older = """"http://Books.example.org/opds/":{"name":"Older","removed":false,"updatedAt":3,"past":2}"""
        listOf("{\"catalogues\":{$newer,$older}}", "{\"catalogues\":{$older,$newer}}").forEach { text ->
            val record = decodeReadingData(text).getOrThrow().catalogues.getValue(home.url.catalogueKey)
            assertEquals(CatalogueRecord("Newer", null, true, 9, mapOf("future" to JsonPrimitive(1), "past" to JsonPrimitive(2))), record, text)
        }
    }

    @Test
    fun `decoding re-keys each Catalogue, so an http or slashed key can be removed, and drops a key that isn't a URL`() {
        val text = """{"catalogues":{
            "http://Books.example.org/opds/":{"name":"Home books","removed":false,"updatedAt":3},
            "https://www.gutenberg.org/ebooks.opds/":{"removed":true,"updatedAt":4},
            "https://books.example.org/opds":{"name":"Newer","removed":false,"updatedAt":9},
            "ftp://nowhere.example/":{"name":"Bad","removed":false,"updatedAt":5},
            "https://club.example.net/feed.xml":{"name":"Book club","removed":false,"updatedAt":6}
        }}"""
        val data = decodeReadingData(text).getOrThrow()
        assertEquals(setOf(home.url.catalogueKey, GUTENBERG.url.catalogueKey, club.url.catalogueKey), data.catalogues.keys)
        assertEquals(listOf("Standard Ebooks: new releases", "Book club", "Newer"), data.catalogueList().map { it.catalogue.name })
        assertEquals(listOf(GUTENBERG), data.removedShipped())
        val removed = data.withoutCatalogue(url("http://books.example.org/opds/"), 10)
        assertEquals(listOf("Standard Ebooks: new releases", "Book club"), removed.catalogueList().map { it.catalogue.name })
        assertEquals(removed, decodeReadingData(removed.encode()).getOrThrow())
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
