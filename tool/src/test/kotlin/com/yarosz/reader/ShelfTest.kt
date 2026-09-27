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
    ) = title to BookEntry(title, file, place, finished, onShelf, author = author, source = source?.value, addedAt = addedAt)

    private fun rows(vararg books: Pair<String, BookEntry>, transfers: Map<HttpsUrl, Transfer> = emptyMap(), present: Set<String>? = null) =
        shelfRows(ReadingData(books = mapOf(*books)), present ?: books.mapNotNull { it.second.file }.toSet(), transfers)

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
        val transfers = mapOf(source to Transfer("Arriving", "An Author", 30, TransferState.Running))
        val shown = rows(book("reading", place(1)), book("newer", addedAt = 40), book("older", addedAt = 20), transfers = transfers)
        assertEquals(listOf("reading", "newer", "Arriving", "older"), shown.map { it.title })
        assertEquals(ShelfRow(RowKey.Arriving(source), "Arriving", ROW_DOWNLOADING, RowTap.None), shown[2])
    }

    @Test
    fun `each row's second line and tap follow its state`() {
        val transfers = mapOf(
            other to Transfer("Failing", null, 1, TransferState.Failed(Unreachable)),
            source to Transfer("Redownloading", null, 1, TransferState.Running),
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
            transfers = transfers,
            present = setOf("In progress.epub", "In progress, no author.epub", "Finished.epub", "Unread.epub"),
        ).associateBy { it.title }
        fun line(title: String) = shown.getValue(title).detail to shown.getValue(title).tap
        assertEquals("Jane Austen" to RowTap.Open("In progress.epub"), line("In progress"))
        assertEquals(null to RowTap.Open("In progress, no author.epub"), line("In progress, no author"))
        assertEquals("Lewis Carroll" to RowTap.Open("Finished.epub"), line("Finished"))
        assertEquals(ROW_NOT_STARTED to RowTap.Open("Unread.epub"), line("Unread"))
        assertEquals(ROW_FILE_MISSING_SOURCE to RowTap.Download(HttpsUrl.parse("https://books.example.org/m.epub")!!, "Missing", null), line("Missing"))
        assertEquals(ROW_FILE_MISSING to RowTap.None, line("Missing, no source"))
        assertEquals(ROW_FILE_MISSING to RowTap.None, line("Never downloaded"))
        assertEquals(ROW_DOWNLOADING to RowTap.None, line("Redownloading"))
        assertEquals(ROW_DOWNLOAD_FAILED to RowTap.Download(other, "Failing", null), line("Failing"))
    }

    @Test
    fun `a source that isn't an https URL counts as none`() {
        val shown = rows(book("Odd", source = null).let { (id, e) -> id to e.copy(source = "ftp://x") }, present = emptySet())
        assertEquals(ROW_FILE_MISSING to RowTap.None, shown.single().detail to shown.single().tap)
    }

    @Test
    fun `only the network, a server error and a full phone can be retried`() {
        val retryable = listOf(Unreachable, HttpError(500), HttpError(404), DiskError)
        val permanent = listOf(NoHttps, UntrustedCertificate, NotAnEpub, CopyProtected)
        assertEquals(retryable.map { true } + permanent.map { false }, (retryable + permanent).map { it.isRetryable })
    }
}
