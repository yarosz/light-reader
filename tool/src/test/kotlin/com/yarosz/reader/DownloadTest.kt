package com.yarosz.reader

import java.io.File
import java.io.SyncFailedException
import java.net.ConnectException
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTest {

    private val dir: File = createTempDirectory("books").toFile()
    private val link = HttpsUrl.parse("https://books.example.org/book.epub")!!
    private val epub = zipBytes(epubFiles(identifier = "urn:uuid:storm", title = "Stormy Night"))
    private val progress = mutableListOf<DownloadState.Downloading>()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun serving(answer: Answer) = FakeTransport(mapOf(link.value to answer))

    private fun download(
        answer: Answer,
        downloader: (Transport) -> Downloader = { Downloader(it, dir) },
        at: HttpsUrl = link,
    ): DownloadState.Finished = downloader(serving(answer)).download(at, "From the Catalogue") { progress += it }

    private fun failure(answer: Answer, downloader: (Transport) -> Downloader = { Downloader(it, dir) }, at: HttpsUrl = link) =
        (download(answer, downloader, at) as DownloadState.Failed).reason

    private fun files() = dir.list()!!.sorted()

    @Test
    fun `a download lands under the Book's identifier with its title, and reports progress to the end`() {
        val done = download(Answer(body = epub)) as DownloadState.Done
        assertEquals(DownloadState.Done("urn:uuid:storm", "Stormy Night", bookFileName("urn:uuid:storm")), done)
        assertEquals(listOf(done.file), files())
        assertTrue(File(dir, done.file).readBytes().contentEquals(epub))
        assertEquals(DownloadState.Downloading(0, epub.size.toLong()), progress.first())
        assertEquals(1f, progress.last().progress)
    }

    @Test
    fun `downloading a Book again replaces its file`() {
        download(Answer(body = epub))
        val newEdition = zipBytes(epubFiles(identifier = "urn:uuid:storm", title = "Stormy Night", chapters = listOf("A new edition.")))
        val done = download(Answer(body = newEdition)) as DownloadState.Done
        assertEquals(listOf(done.file), files())
        assertTrue(File(dir, done.file).readBytes().contentEquals(newEdition))
    }

    @Test
    fun `a Book with no title takes the Catalogue entry's, and a server that gives no length still downloads`() {
        val done = download(Answer(body = zipBytes(epubFiles(title = null)), length = null)) as DownloadState.Done
        assertEquals("From the Catalogue", done.title)
        assertNull(progress.last().progress)
    }

    @Test
    fun `a Book with no title keeps its Catalogue title when reopened from its file`() {
        val done = download(Answer(body = zipBytes(epubFiles(title = null)))) as DownloadState.Done
        assertEquals(bookFileName("urn:uuid:test-book"), done.file)
        val shelved = ReadingData().shelve(done.identifier, done.title, done.file)
        val file = File(dir, done.file)
        val reopened = parseEpub(file, shelved.storedTitle(done.file) ?: file.nameWithoutExtension)
        assertEquals("From the Catalogue", reopened.title)
        assertEquals("From the Catalogue", shelved.shelve(reopened.identifier, reopened.title, done.file).books.getValue(done.identifier).title)
        assertEquals(file.nameWithoutExtension, parseEpub(file).title)
    }

    @Test
    fun `progress is a fraction of the total when known`() {
        assertEquals(0.25f, DownloadState.Downloading(1, 4).progress)
        assertEquals(1f, DownloadState.Downloading(9, 4).progress)
        assertNull(DownloadState.Downloading(1, 0).progress)
        assertNull(DownloadState.Downloading(1, null).progress)
    }

    @Test
    fun `a copy-protected Book is rejected and nothing is kept`() {
        val drm = zipBytes(epubFiles() + ("META-INF/rights.xml" to "<rights/>"))
        assertEquals(CopyProtected, failure(Answer(body = drm)))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a file that isn't an EPUB is rejected and nothing is kept`() {
        assertEquals(NotAnEpub, failure(Answer(body = "<html>Download page</html>".toByteArray())))
        assertEquals(NotAnEpub, failure(Answer(body = zipBytes(mapOf("readme.txt" to "hello")))))
        assertEquals(NotAnEpub, failure(Answer(body = zipBytes(epubFiles(chapters = emptyList())))))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a server error, an unreachable server, and a dropped connection each have their reason`() {
        assertEquals(HttpError(404), failure(Answer(status = 404, body = "Not found".toByteArray())))
        assertEquals(Unreachable, failure(Answer(connectFailure = ConnectException("refused"))))
        assertEquals(NoHttps, failure(Answer(connectFailure = ConnectException("refused")), at = HttpsUrl.parse("http://books.example.org/book.epub")!!))
        assertEquals(Unreachable, failure(Answer(body = epub, readFailureAfter = 100)))
        assertEquals(Unreachable, failure(Answer(body = epub.copyOf(100), length = epub.size.toLong())))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a full disk, a failed sync, or a failed rename is a disk error and leaves nothing behind`() {
        assertEquals(DiskError, failure(Answer(body = epub), { Downloader(it, dir, usableSpace = { MIN_FREE_BYTES + epub.size - 1 }) }))
        assertEquals(DiskError, failure(Answer(body = epub), { Downloader(it, dir, sync = { throw SyncFailedException("sync") }) }))
        assertEquals(DiskError, failure(Answer(body = epub), { Downloader(it, dir, rename = { _, _ -> false }) }))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a books directory that can't be written is a disk error, and the response is still closed`() {
        val notADir = File(dir, "file").apply { writeText("") }
        val transport = serving(Answer(body = epub))
        assertEquals(DownloadState.Failed(DiskError), Downloader(transport, notADir).download(link, "T") {})
        assertEquals(1, transport.closed)
    }

    @Test
    fun `cancelling from the progress callback deletes the temp file and propagates`() {
        val transport = serving(Answer(body = epub))
        assertFailsWith<CancellationException> {
            Downloader(transport, dir).download(link, "T") { if (it.received > 0) throw CancellationException("left the screen") }
        }
        assertEquals(emptyList(), files())
        assertEquals(1, transport.closed)
    }

    @Test
    fun `a download stops at the declared length, and more bytes than declared is a broken response`() {
        assertEquals(Unreachable, failure(Answer(body = epub + ByteArray(10), length = epub.size.toLong())))
        assertEquals(Unreachable, failure(Answer(body = epub, length = epub.size - 1L)))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a Book larger than the cap is refused, whether declared or found while copying`() {
        val capped = { transport: Transport -> Downloader(transport, dir, maxBookBytes = epub.size - 1L) }
        assertEquals(NotAnEpub, failure(Answer(body = epub), capped))
        assertEquals(NotAnEpub, failure(Answer(body = epub, length = null), capped))
        assertTrue(download(Answer(body = epub, length = null), { Downloader(it, dir, maxBookBytes = epub.size.toLong()) }) is DownloadState.Done)
    }

    @Test
    fun `running out of storage during the copy is a disk error, checked before the phone is full`() {
        var spaceChecks = 0
        val filling = { transport: Transport -> Downloader(transport, dir, usableSpace = { if (spaceChecks++ == 0) Long.MAX_VALUE else MIN_FREE_BYTES }) }
        assertEquals(DiskError, failure(Answer(body = epub, length = null), filling))
        assertEquals(2, spaceChecks)
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a body cut short with no declared length is a dropped connection, not a bad file`() {
        assertEquals(Unreachable, failure(Answer(body = epub.copyOf(epub.size / 2), length = null)))
        assertEquals(NotAnEpub, failure(Answer(body = "<html>Gone</html>".toByteArray(), length = null)))
    }

    @Test
    fun `a package or chapter that would expand without bound is not an EPUB`() {
        assertEquals(NotAnEpub, failure(Answer(body = zipBytes(epubFiles() + ("OEBPS/content.opf" to LAUGHS)))))
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a Downloader deletes temp files a killed download left, but not a running one's or a Book`() {
        val old = System.currentTimeMillis() - 60 * 60 * 1000
        val leftover = File(dir, "download-123.part").apply { writeText("x"); setLastModified(old) }
        val running = File(dir, "download-456.part").apply { writeText("x") }
        val book = File(dir, bookFileName("urn:uuid:storm")).apply { writeText("x"); setLastModified(old) }
        Downloader(serving(Answer()), dir)
        assertEquals(listOf(book.name, running.name).sorted(), files())
        assertFalse(leftover.exists())
    }

    @Test
    fun `a Book's file name is short, fixed, and safe for any identifier`() {
        val name = bookFileName("https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/john-tenniel")
        assertTrue(Regex("[0-9a-f]{16}\\.epub").matches(name), name)
        assertEquals(name, bookFileName("https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/john-tenniel"))
        assertTrue(name != bookFileName("urn:isbn:9780141439518"))
    }
}
