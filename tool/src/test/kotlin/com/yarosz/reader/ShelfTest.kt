package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ShelfTest {

    private val source = HttpsUrl.parse("https://books.example.org/a.epub")!!
    private val other = HttpsUrl.parse("https://books.example.org/b.epub")!!

    private fun place(updatedAt: Long) = Place("c1", 0, 0, "", updatedAt)

    private fun book(
        title: String,
        place: Place? = null,
        finished: Boolean = false,
        addedAt: Long? = null,
        author: String? = null,
        source: HttpsUrl? = null,
        file: String? = "$title.epub",
        onShelf: Boolean = true,
    ) = title to Book(title, file, place, finished, onShelf, author = author, source = source?.value, addedAt = addedAt)

    private fun rows(vararg books: Pair<String, Book>, downloads: Map<HttpsUrl, Download> = emptyMap(), present: Set<String>? = null) =
        shelfRows(ReadingData(books = mapOf(*books)), present ?: books.mapNotNull { it.second.file }.toSet(), downloads)

    @Test
    fun `in progress by most recent first, then never opened by newest added, then finished at the bottom`() {
        val shown = rows(
            book("finished old", place(1), finished = true),
            book("new, unread", addedAt = 50),
            book("read yesterday", place(10)),
            book("from before the Shelf"),
            book("finished recent", place(40), finished = true),
            book("read today", place(20)),
            book("old, unread", addedAt = 5),
            book("removed", place(99), onShelf = false),
        )
        assertEquals(
            listOf("read today", "read yesterday", "new, unread", "old, unread", "from before the Shelf", "finished recent", "finished old"),
            shown.map { it.title },
        )
    }

    @Test
    fun `a download that hasn't arrived sorts with the never-opened Books by when it started`() {
        val downloads = mapOf(source to Download("Arriving", "An Author", 30, Download.Status.Running))
        val shown = rows(book("reading", place(1)), book("newer", addedAt = 40), book("older", addedAt = 20), downloads = downloads)
        assertEquals(listOf("reading", "newer", "Arriving", "older"), shown.map { it.title })
        assertEquals(ShelfRow(RowKey.Arriving(source), "Arriving", ROW_DOWNLOADING, RowTap.None), shown[2])
    }

    @Test
    fun `each row's second line and tap follow its state`() {
        val downloads = mapOf(
            other to Download("Failing", null, 1, Download.Status.Failed(Unreachable)),
            source to Download("Redownloading", null, 1, Download.Status.Running, replacing = "Redownloading"),
            HttpsUrl.parse("https://books.example.org/offline.epub")!! to Download("Present", null, 1, Download.Status.Failed(Unreachable), replacing = "Present"),
            HttpsUrl.parse("https://books.example.org/gone.epub")!! to Download("Retry", null, 1, Download.Status.Failed(HttpError(503)), replacing = "Retry"),
        )
        val shown = rows(
            book("In progress", place(9), author = "Jane Austen"),
            book("In progress, no author", place(8)),
            book("Finished", place(7), finished = true, author = "Lewis Carroll"),
            book("Unread", author = "Jane Austen"),
            book("Missing", source = HttpsUrl.parse("https://books.example.org/m.epub")),
            book("Missing, no source"),
            book("Never downloaded", file = null),
            book("Redownloading", source = source),
            book("Present", place(6), author = "Mary Shelley", source = HttpsUrl.parse("https://books.example.org/offline.epub")),
            book("Retry", source = HttpsUrl.parse("https://books.example.org/gone.epub")),
            downloads = downloads,
            present = setOf("In progress.epub", "In progress, no author.epub", "Finished.epub", "Unread.epub", "Present.epub"),
        ).associateBy { it.title }
        fun line(title: String) = shown.getValue(title).detail to shown.getValue(title).tap
        assertEquals("Jane Austen" to RowTap.Open("In progress.epub"), line("In progress"))
        assertEquals(null to RowTap.Open("In progress, no author.epub"), line("In progress, no author"))
        assertEquals("Lewis Carroll · finished" to RowTap.Open("Finished.epub"), line("Finished"))
        assertEquals(ROW_NOT_STARTED to RowTap.Open("Unread.epub"), line("Unread"))
        assertEquals(ROW_FILE_MISSING_SOURCE to RowTap.Download(HttpsUrl.parse("https://books.example.org/m.epub")!!, "Missing", null, "Missing"), line("Missing"))
        assertEquals(ROW_FILE_MISSING to RowTap.None, line("Missing, no source"))
        assertEquals(ROW_FILE_MISSING to RowTap.None, line("Never downloaded"))
        assertEquals(ROW_DOWNLOADING to RowTap.None, line("Redownloading"))
        assertEquals(ROW_DOWNLOAD_FAILED to RowTap.Download(other, "Failing", null), line("Failing"))
        assertEquals("Mary Shelley" to RowTap.Open("Present.epub"), line("Present"))
        assertEquals(ROW_DOWNLOAD_FAILED to RowTap.Download(HttpsUrl.parse("https://books.example.org/gone.epub")!!, "Retry", null, "Retry"), line("Retry"))
        assertEquals(11, shown.size)
    }

    @Test
    fun `an opened Book's row reads its author and floored percent, or finished`() {
        fun detail(progress: Double?, author: String?, finished: Boolean = false) =
            readingDetail(Book("T", "t.epub", Place("c1", 0, 0, "", 1, progress), finished, onShelf = true, author = author))
        assertEquals("Jane Austen · 42%", detail(0.4299, "Jane Austen"))
        assertEquals("Jane Austen · 29%", detail(0.29, "Jane Austen"))
        assertEquals("Jane Austen · 1%", detail(0.01, "Jane Austen"))
        assertEquals("Jane Austen · 99%", detail(0.9999, "Jane Austen"))
        assertEquals("Jane Austen", detail(0.0099, "Jane Austen"))
        assertEquals("Jane Austen", detail(0.0, "Jane Austen"))
        assertEquals("Jane Austen", detail(null, "Jane Austen"))
        assertEquals("42%", detail(0.42, null))
        assertEquals(null, detail(0.005, null))
        assertEquals(null, detail(null, null))
        assertEquals("Jane Austen · finished", detail(0.9, "Jane Austen", finished = true))
        assertEquals("finished", detail(null, null, finished = true))
        val row = rows(book("Reading", Place("c1", 0, 0, "", 5, progress = 0.5), author = "Mary Shelley")).single()
        assertEquals("Mary Shelley · 50%", row.detail)
    }

    @Test
    fun `a permanent failure downloading a missing file again says why, and the row can only be removed`() {
        val expected = mapOf(
            CopyProtected to ROW_CANT_DOWNLOAD_COPY_PROTECTED,
            NotAnEpub to ROW_CANT_DOWNLOAD_NOT_AN_EPUB,
            NoHttps to ROW_CANT_DOWNLOAD_NO_HTTPS,
            HttpError(401) to "can't download again · needs a login",
        )
        expected.forEach { (reason, detail) ->
            val shown = rows(book("Book", source = source), downloads = mapOf(source to Download("Book", null, 1, Download.Status.Failed(reason), replacing = "Book")), present = emptySet())
            assertEquals(listOf(ShelfRow(RowKey.Shelved("Book"), "Book", detail, RowTap.None)), shown, reason.toString())
        }
    }

    @Test
    fun `an untrusted certificate leaves a row that retries, from the Shelf or from a Catalogue (D15)`() {
        val failed = Download.Status.Failed(UntrustedCertificate)
        val again = rows(book("Book", source = source), downloads = mapOf(source to Download("Book", null, 1, failed, replacing = "Book")), present = emptySet())
        assertEquals(ShelfRow(RowKey.Shelved("Book"), "Book", ROW_DOWNLOAD_FAILED, RowTap.Download(source, "Book", null, "Book")), again.single())
        val arriving = rows(downloads = mapOf(other to Download("New", null, 1, failed)))
        assertEquals(ShelfRow(RowKey.Arriving(other), "New", ROW_DOWNLOAD_FAILED, RowTap.Download(other, "New", null)), arriving.single())
    }

    @Test
    fun `a download from the Shelf shows on the one row it replaces, even when another Book has the same source`() {
        val downloads = mapOf(source to Download("Old edition", null, 1, Download.Status.Running, replacing = "Old edition"))
        val shown = rows(book("Old edition", source = source), book("New edition", source = source), downloads = downloads, present = setOf("New edition.epub"))
            .associate { it.title to it.detail }
        assertEquals(mapOf("Old edition" to ROW_DOWNLOADING, "New edition" to ROW_NOT_STARTED), shown)
    }

    @Test
    fun `a download from a Catalogue is a row of its own, even when a Book on the Shelf has its source`() {
        val downloads = mapOf(source to Download("From the Catalogue", null, 1, Download.Status.Running))
        val shown = rows(book("On the Shelf", source = source), downloads = downloads)
        assertEquals(setOf(RowKey.Shelved("On the Shelf") to ROW_NOT_STARTED, RowKey.Arriving(source) to ROW_DOWNLOADING), shown.map { it.key to it.detail }.toSet())
    }

    @Test
    fun `a source that isn't an https URL counts as none`() {
        val shown = rows(book("Odd", source = null).let { (id, e) -> id to e.copy(source = "ftp://x") }, present = emptySet())
        assertEquals(ROW_FILE_MISSING to RowTap.None, shown.single().detail to shown.single().tap)
    }

    @Test
    fun `the network, a server error, a full phone and an untrusted certificate can be retried, a login can't`() {
        val retryable = listOf(Unreachable, HttpError(500), HttpError(404), DiskError, UntrustedCertificate)
        val permanent = listOf(NoHttps, NotAnEpub, CopyProtected, HttpError(401))
        assertEquals(retryable.map { true } + permanent.map { false }, (retryable + permanent).map { it.isRetryable })
    }
}
