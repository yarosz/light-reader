package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookDetailTest {

    private fun url(value: String) = HttpsUrl.parse(value)!!

    private val noImages = url("https://www.gutenberg.org/ebooks/1342.epub.noimages")
    private val images = url("https://www.gutenberg.org/ebooks/1342.epub3.images")

    /** Gutenberg's real Pride and Prejudice page: two Editions, one "Add to Shelf" (the no-images EPUB, 558 KB). */
    private val editions = parseFeed(File("src/test/fixtures/catalogues/gutenberg-1342.xml").inputStream(), url("https://www.gutenberg.org/ebooks/1342.opds"))!!.entries
    private val best = bestDownload(editions)!!

    private fun book(source: HttpsUrl?, onShelf: Boolean = true, file: String? = "pp.epub", addedAt: Long? = null) =
        Book("Pride and Prejudice", file, null, false, onShelf, source = source?.value, addedAt = addedAt)

    private fun snapshot(vararg books: Pair<String, Book>, present: Set<String> = setOf("pp.epub"), downloads: Map<HttpsUrl, Download> = emptyMap()) =
        ShelfSnapshot(ReadingData(books = mapOf(*books)), present, downloads)

    private fun detail(snapshot: ShelfSnapshot, failure: DownloadFailure? = null) = bookDetail(snapshot, editions, failure, shipped = true)

    @Test
    fun `a Book not on the Shelf offers Add to Shelf for the best Edition`() {
        assertEquals(BookDetail(DetailAction.Download(best, DETAIL_ADD, null), null), detail(snapshot()))
        assertEquals(noImages, best.url)
    }

    @Test
    fun `any of the page's links matching a stored Book's source is a match, whichever Edition it came from`() {
        assertEquals(ShelfMatch.Here("pg", "pp.epub"), snapshot("pg" to book(images)).match(setOf(noImages, images)))
        assertNull(snapshot("pg" to book(url("https://www.gutenberg.org/ebooks/84.epub.noimages"))).match(setOf(noImages, images)))
    }

    @Test
    fun `a Book here reads Read, a missing file Download again, and a removed Book Add to Shelf, both keeping its Place`() {
        assertEquals(BookDetail(DetailAction.Read("pp.epub"), null), detail(snapshot("pg" to book(images))))
        assertEquals(BookDetail(DetailAction.Download(best, DETAIL_DOWNLOAD_AGAIN, "pg"), null), detail(snapshot("pg" to book(noImages), present = emptySet())))
        assertEquals(BookDetail(DetailAction.Download(best, DETAIL_ADD, "pg"), null), detail(snapshot("pg" to book(noImages, onShelf = false, file = null))))
    }

    @Test
    fun `a Book here beats a missing one beats a removed one, then the newest added wins`() {
        val snap = snapshot(
            "removed" to book(noImages, onShelf = false, file = null, addedAt = 99),
            "missing" to book(images, file = "gone.epub", addedAt = 50),
            "missing newer" to book(noImages, file = "gone2.epub", addedAt = 60),
        )
        assertEquals(ShelfMatch.Missing("missing newer"), snap.match(setOf(noImages, images)))
        assertEquals(ShelfMatch.Here("here", "pp.epub"), snapshot("here" to book(images), "removed" to book(noImages, onShelf = false, file = null)).match(setOf(noImages, images)))
    }

    @Test
    fun `a running download of any of its links or of the matched Book reads downloading`() {
        val running = Download("Pride and Prejudice", null, 1, Download.Status.Running)
        assertEquals(DetailAction.Downloading, detail(snapshot(downloads = mapOf(images to running))).action)
        val other = url("https://mirror.example.org/pp.epub")
        assertEquals(
            DetailAction.Downloading,
            detail(snapshot("pg" to book(noImages), present = emptySet(), downloads = mapOf(other to running.copy(replacing = "pg")))).action,
        )
    }

    @Test
    fun `a permanent failure shows its copy and no action, and adds nothing`() {
        assertEquals(BookDetail(DetailAction.None, FailureCopy(COPY_COPY_PROTECTED_DETAIL, retry = false)), detail(snapshot(), CopyProtected))
        assertEquals(BookDetail(DetailAction.None, FailureCopy(COPY_NOT_AN_EPUB, retry = false)), detail(snapshot(), NotAnEpub))
    }

    @Test
    fun `a retryable failure, kept on the Shelf or not, shows its copy and Retry`() {
        val failed = Download("Pride and Prejudice", null, 1, Download.Status.Failed(Unreachable))
        assertEquals(BookDetail(DetailAction.Download(best, RETRY, null), FailureCopy(COPY_UNREACHABLE, retry = true)), detail(snapshot(downloads = mapOf(noImages to failed))))
        assertEquals(BookDetail(DetailAction.Download(best, RETRY, null), FailureCopy(COPY_DISK_FULL, retry = true)), detail(snapshot(), DiskError))
    }

    @Test
    fun `a Book that landed reads Read even after an earlier failure`() {
        assertEquals(BookDetail(DetailAction.Read("pp.epub"), null), detail(snapshot("pg" to book(noImages)), Unreachable))
    }

    @Test
    fun `a page with no EPUB has no action`() {
        val entry = editions.first().copy(acquisitions = listOf(Acquisition(url("https://books.example.org/x.pdf"), "application/pdf", null, null)))
        assertEquals(BookDetail(DetailAction.None, null), bookDetail(snapshot(), listOf(entry), null, shipped = true))
    }
}
