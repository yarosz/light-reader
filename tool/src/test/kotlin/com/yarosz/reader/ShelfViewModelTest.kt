package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Drives the Shelf over a temp filesDir with a fake network. The main thread and IO are test
 * dispatchers on separate schedulers, so a download can be caught while it runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    private val dir: File = createTempDirectory("shelf-vm").toFile()
    private val main = StandardTestDispatcher(TestCoroutineScheduler())
    private val io = StandardTestDispatcher(TestCoroutineScheduler())
    private var clock = 1_000L

    private val link = HttpsUrl.parse("https://books.example.org/storm.epub")!!
    private val storm = zipBytes(
        epubFiles(identifier = "urn:uuid:storm", title = "Stormy Night").let {
            it + ("OEBPS/content.opf" to it.getValue("OEBPS/content.opf").replace("</metadata>", "<dc:creator>Edward Bulwer-Lytton</dc:creator></metadata>"))
        },
    )
    private val stormFile = bookFileName("urn:uuid:storm")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() {
        ShelfOwner.forget(dir)
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun settle() = repeat(6) {
        main.scheduler.advanceUntilIdle()
        io.scheduler.advanceUntilIdle()
    }

    /** Runs what is ready without moving the main thread's clock, so no debounced save fires. */
    private fun settleWithoutTime() = repeat(6) {
        main.scheduler.runCurrent()
        io.scheduler.advanceUntilIdle()
    }

    private fun serving(body: ByteArray = storm) = FakeTransport(mapOf(link.value to Answer(body = body)))

    private fun owner(transport: Transport) = ShelfOwner.of(dir) { ShelfOwner(dir, io, transport) { clock } }

    private fun shelf(transport: Transport = serving(), connected: () -> Boolean? = { null }): ShelfViewModel =
        ShelfViewModel(owner(transport), connected).also {
            it.refresh()
            settle()
        }

    private fun seed(vararg books: Pair<String, Book>) {
        ReadingStore(dir).save { ReadingData(books = mapOf(*books)) }
        books.forEach { (_, book) -> book.file?.let { File(dir, it).writeText("epub") } }
    }

    private fun store(vararg books: Pair<String, Book>) = ReadingStore(dir).save { ReadingData(books = mapOf(*books)) }

    private fun book(title: String, place: Place? = null, source: String? = null, file: String? = "$title.epub") =
        Book(title, file, place, finished = false, onShelf = true, source = source)

    private fun ShelfViewModel.row(title: String) = snapshot.value!!.rows.single { it.title == title }

    private fun ShelfViewModel.titles() = snapshot.value!!.rows.map { it.title }

    private fun ShelfViewModel.downloadAgain(title: String) = download(row(title).tap as RowTap.Download)

    private fun <T> Deferred<T>.done(): T = getCompleted()

    private fun stored() = ReadingStore(dir).load()

    private fun leftovers() = dir.list()!!.filter { it.endsWith(".epub") || it.endsWith(".part") }

    @Test
    fun `an empty Shelf has no rows, and Edit can't start on it`() {
        val vm = shelf()
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        vm.toggleEdit()
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `a Book whose file is here opens it, and Edit toggles back to browsing`() {
        seed("urn:a" to book("A"))
        val vm = shelf()
        assertEquals(RowTap.Open("A.epub"), vm.row("A").tap)
        vm.toggleEdit()
        assertEquals(ShelfMode.Editing(), vm.mode.value)
        vm.toggleEdit()
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `Remove asks inline, Cancel keeps the Book, and confirming deletes the file but keeps the Place`() {
        val place = Place("c1", 2, 5, "snippet", 10)
        seed("urn:a" to book("A", place), "urn:b" to book("B"))
        val vm = shelf()
        vm.askToRemove(RowKey.Shelved("urn:a"))
        assertEquals(ShelfMode.Browsing, vm.mode.value)
        vm.toggleEdit()
        vm.askToRemove(RowKey.Shelved("urn:a"))
        assertEquals(ShelfMode.Editing(RowKey.Shelved("urn:a")), vm.mode.value)
        vm.cancelRemove()
        assertEquals(ShelfMode.Editing(), vm.mode.value)
        assertEquals(listOf("A", "B"), vm.titles())

        vm.askToRemove(RowKey.Shelved("urn:a"))
        vm.remove(RowKey.Shelved("urn:a"))
        settle()
        vm.onAppPause()
        assertEquals(ShelfMode.Editing(), vm.mode.value)
        assertEquals(listOf("B"), vm.titles())
        assertFalse(File(dir, "A.epub").exists())
        assertEquals(book("A", place).copy(file = null, onShelf = false), stored().books.getValue("urn:a"))

        vm.remove(RowKey.Shelved("urn:b"))
        settle()
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `a download adds the Book under its Catalogue title with its source, author and date, and keeps a Place it had`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        store("urn:uuid:storm" to book("Stormy Night", place, file = null).copy(onShelf = false))
        val vm = shelf()
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        clock = 5_000
        val download = vm.download(link, "Stormy night (Catalogue)", "Catalogue Author")
        assertEquals(listOf(ShelfRow(RowKey.Arriving(link), "Stormy night (Catalogue)", ROW_DOWNLOADING, RowTap.None)), vm.snapshot.value?.rows)
        assertSame(download, vm.download(link, "again", null))
        settle()
        assertEquals(DownloadResult.Done("urn:uuid:storm"), download.done())
        assertEquals(listOf(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stormy night (Catalogue)", "Edward Bulwer-Lytton", RowTap.Open(stormFile))), vm.snapshot.value?.rows)
        vm.onAppPause()
        val added = stored().books.getValue("urn:uuid:storm")
        assertEquals(Book("Stormy night (Catalogue)", stormFile, place, false, true, "Edward Bulwer-Lytton", link.value, 5_000), added)
    }

    @Test
    fun `a landed download and a removal are on disk at once, with no wait for the save debounce`() {
        val vm = shelf()
        vm.download(link, "Stormy Night", null)
        settleWithoutTime()
        assertEquals(RowTap.Open(stormFile), vm.row("Stormy Night").tap)
        assertTrue(stored().books.getValue("urn:uuid:storm").onShelf)
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:uuid:storm"))
        assertFalse(stored().books.getValue("urn:uuid:storm").onShelf)
    }

    @Test
    fun `a retryable failure stays as a row that retries, and a permanent one from a Catalogue adds nothing`() {
        val broken = HttpsUrl.parse("https://books.example.org/broken.epub")!!
        val protected = HttpsUrl.parse("https://books.example.org/protected.epub")!!
        val offline = HttpsUrl.parse("https://books.example.org/offline.epub")!!
        val answers = mutableMapOf(
            link.value to Answer(status = 503),
            broken.value to Answer(body = "not an epub".toByteArray()),
            protected.value to Answer(body = zipBytes(epubFiles() + ("META-INF/rights.xml" to "<rights/>"))),
        )
        val vm = shelf(FakeTransport(answers))
        val failed = vm.download(link, "Stormy Night", null)
        val unreachable = vm.download(offline, "Offline", null)
        val notAnEpub = vm.download(broken, "Broken", null)
        val copyProtected = vm.download(protected, "Protected", null)
        settle()
        assertEquals(DownloadResult.Failed(HttpError(503)), failed.done())
        assertEquals(DownloadResult.Failed(Unreachable), unreachable.done())
        assertEquals(DownloadResult.Failed(NotAnEpub), notAnEpub.done())
        assertEquals(DownloadResult.Failed(CopyProtected), copyProtected.done())
        assertEquals(setOf("Stormy Night", "Offline"), vm.titles().toSet())
        assertEquals(ROW_DOWNLOAD_FAILED, vm.row("Stormy Night").detail)
        assertEquals(RowTap.Download(link, "Stormy Night", null), vm.row("Stormy Night").tap)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `tapping a failed row downloads it again`() {
        val answers = mutableMapOf(link.value to Answer(status = 500))
        val vm = shelf(FakeTransport(answers))
        vm.download(link, "Stormy Night", null)
        settle()
        answers[link.value] = Answer(body = storm)
        vm.downloadAgain("Stormy Night")
        assertEquals(ROW_DOWNLOADING, vm.row("Stormy Night").detail)
        settle()
        assertEquals(listOf(RowTap.Open(stormFile)), vm.snapshot.value!!.rows.map { it.tap })
    }

    @Test
    fun `a retry tapped while the phone is offline says so on the row, and one tapped back online lands`() {
        var connected: Boolean? = true
        val answers = mutableMapOf(link.value to Answer(status = 500))
        val vm = shelf(FakeTransport(answers)) { connected }
        vm.download(link, "Stormy Night", null)
        settle()
        assertEquals(ROW_DOWNLOAD_FAILED, vm.row("Stormy Night").detail)
        connected = false
        answers[link.value] = Answer(connectFailure = java.net.UnknownHostException("books.example.org"))
        vm.downloadAgain("Stormy Night")
        settle()
        assertEquals(ROW_DOWNLOAD_FAILED_OFFLINE, vm.row("Stormy Night").detail)
        connected = true
        answers[link.value] = Answer(body = storm)
        vm.downloadAgain("Stormy Night")
        settle()
        assertEquals(listOf(RowTap.Open(stormFile)), vm.snapshot.value!!.rows.map { it.tap })
    }

    @Test
    fun `an unreachable download reads as offline only when the phone reports no connection`() {
        listOf(true, null).forEach { reported ->
            val vm = shelf(FakeTransport(emptyMap())) { reported }
            vm.download(link, "Stormy Night", null)
            settle()
            assertEquals(ROW_DOWNLOAD_FAILED, vm.row("Stormy Night").detail, "reported $reported")
            vm.remove(vm.row("Stormy Night").key)
        }
    }

    @Test
    fun `leaving the Shelf for the Catalogues ends Edit`() {
        seed("urn:a" to book("A"))
        val vm = shelf()
        vm.toggleEdit()
        vm.askToRemove(RowKey.Shelved("urn:a"))
        vm.endEdit()
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `a missing file with a source downloads again from it and keeps the Place`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        store("urn:uuid:storm" to book("Stormy Night", place, source = link.value, file = stormFile))
        val transport = serving()
        val vm = shelf(transport)
        assertEquals(ROW_FILE_MISSING_SOURCE, vm.row("Stormy Night").detail)
        vm.downloadAgain("Stormy Night")
        assertEquals(ROW_DOWNLOADING, vm.row("Stormy Night").detail)
        settle()
        assertEquals(listOf(link), transport.asked)
        assertEquals(RowTap.Open(stormFile), vm.row("Stormy Night").tap)
        assertTrue(File(dir, stormFile).exists())
        vm.onAppPause()
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)
    }

    @Test
    fun `a Book downloaded again under a new identifier keeps its row, title, Place and date`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        store(
            "urn:uuid:old-conversion" to book("Stored Title", place, source = link.value, file = bookFileName("urn:uuid:old-conversion")).copy(addedAt = 7),
            "urn:other" to book("Other", source = link.value, file = null),
        )
        val vm = shelf()
        val download = vm.downloadAgain("Stored Title")
        assertEquals(mapOf("Stored Title" to ROW_DOWNLOADING, "Other" to ROW_FILE_MISSING_SOURCE), vm.snapshot.value!!.rows.associate { it.title to it.detail })
        settle()
        assertEquals(DownloadResult.Done("urn:uuid:storm"), download.done())
        assertEquals(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stored Title", "Edward Bulwer-Lytton", RowTap.Open(stormFile)), vm.row("Stored Title"))
        assertEquals(listOf("Stored Title", "Other"), vm.titles())
        vm.onAppPause()
        val books = stored().books
        assertEquals(Book("Stored Title", stormFile, place, false, true, "Edward Bulwer-Lytton", link.value, 7), books.getValue("urn:uuid:storm"))
        assertFalse(books.getValue("urn:uuid:old-conversion").onShelf)
    }

    @Test
    fun `a Book downloaded again under the identifier of a Book already on the Shelf merges into that row`() {
        val newer = Place("c1", 0, 3, "snippet", 10)
        store(
            "urn:uuid:old-conversion" to book("Stored Title", newer, source = link.value, file = bookFileName("urn:uuid:old-conversion")).copy(addedAt = 7),
            "urn:uuid:storm" to book("On the Shelf", Place("c1", 0, 9, "older", 5), file = stormFile).copy(addedAt = 3),
        )
        File(dir, stormFile).writeText("epub")
        val vm = shelf()
        assertEquals(DownloadResult.Done("urn:uuid:storm"), vm.downloadAgain("Stored Title").also { settle() }.done())
        assertEquals(listOf(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "On the Shelf", "Edward Bulwer-Lytton", RowTap.Open(stormFile))), vm.snapshot.value?.rows)
        vm.onAppPause()
        val books = stored().books
        assertEquals(Book("On the Shelf", stormFile, newer, false, true, "Edward Bulwer-Lytton", link.value, 3), books.getValue("urn:uuid:storm"))
        assertFalse(books.getValue("urn:uuid:old-conversion").onShelf)
    }

    @Test
    fun `a permanent failure downloading a missing file again stays on its row, which can only be removed`() {
        store("urn:uuid:storm" to book("Stormy Night", source = link.value, file = stormFile))
        val vm = shelf(serving("<html>moved</html>".toByteArray()))
        val download = vm.downloadAgain("Stormy Night")
        settle()
        assertEquals(DownloadResult.Failed(NotAnEpub), download.done())
        assertEquals(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stormy Night", ROW_CANT_DOWNLOAD_NOT_AN_EPUB, RowTap.None), vm.row("Stormy Night"))
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:uuid:storm"))
        assertEquals(emptyList(), vm.snapshot.value?.rows)
    }

    @Test
    fun `a missing file whose source needs a login says so, and the row can only be removed`() {
        store("urn:uuid:storm" to book("Stormy Night", source = link.value, file = stormFile))
        val vm = shelf(FakeTransport(mapOf(link.value to Answer(status = 401))))
        val download = vm.downloadAgain("Stormy Night")
        settle()
        assertEquals(DownloadResult.Failed(HttpError(401)), download.done())
        assertEquals(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stormy Night", "can't download again · needs a login", RowTap.None), vm.row("Stormy Night"))
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:uuid:storm"))
        assertEquals(emptyList(), vm.snapshot.value?.rows)
    }

    @Test
    fun `a missing file with no source can only be removed`() {
        store("urn:a" to book("A"))
        val vm = shelf(FakeTransport(emptyMap()))
        assertEquals(ShelfRow(RowKey.Shelved("urn:a"), "A", ROW_FILE_MISSING, RowTap.None), vm.row("A"))
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:a"))
        assertEquals(emptyList(), vm.snapshot.value?.rows)
    }

    @Test
    fun `removing a Book mid-download ends it as removed, never cancelled, and nothing lands`() {
        store("urn:uuid:storm" to book("Stormy Night", source = link.value, file = stormFile))
        lateinit var vm: ShelfViewModel
        val transport = Transport { url ->
            vm.remove(RowKey.Shelved("urn:uuid:storm"))
            serving().get(url)
        }
        vm = shelf(transport)
        val download = vm.downloadAgain("Stormy Night")
        settle()
        assertFalse(download.isCancelled)
        assertEquals(DownloadResult.Removed, download.done())
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `removing a download that hasn't arrived ends it before it asks the network`() {
        val transport = serving()
        val vm = shelf(transport)
        val download = vm.download(link, "Stormy Night", null)
        vm.remove(RowKey.Arriving(link))
        settle()
        assertEquals(DownloadResult.Removed, download.done())
        assertEquals(emptyList(), transport.asked)
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        assertFalse(File(dir, stormFile).exists())
    }

    @Test
    fun `a removal after the body arrived but before the main thread lands it leaves no file`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        store("urn:uuid:storm" to book("Stormy Night", place, source = link.value, file = stormFile))
        val vm = shelf()
        val download = vm.downloadAgain("Stormy Night")
        main.scheduler.runCurrent()
        io.scheduler.advanceUntilIdle()
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:uuid:storm"))
        settle()
        assertEquals(DownloadResult.Removed, download.done())
        assertEquals(emptyList(), leftovers())
        assertEquals(emptyList(), vm.snapshot.value?.rows)
        vm.onAppPause()
        assertEquals(book("Stormy Night", place, source = link.value, file = null).copy(onShelf = false), stored().books.getValue("urn:uuid:storm"))
    }

    @Test
    fun `a download removed after its body arrived and then added again lands once, from the second download`() {
        val vm = shelf()
        val first = vm.download(link, "Stormy Night", null)
        main.scheduler.runCurrent()
        io.scheduler.advanceUntilIdle()
        vm.remove(RowKey.Arriving(link))
        val second = vm.download(link, "Stormy Night", null)
        settle()
        assertEquals(DownloadResult.Removed, first.done())
        assertEquals(DownloadResult.Done("urn:uuid:storm"), second.done())
        assertEquals(listOf(RowTap.Open(stormFile)), vm.snapshot.value!!.rows.map { it.tap })
        assertEquals(listOf(stormFile), leftovers())
    }

    @Test
    fun `a file check that started before a download landed doesn't hide the Book it landed`() {
        store("urn:uuid:storm" to book("Stormy Night", source = link.value, file = stormFile))
        val vm = shelf()
        vm.downloadAgain("Stormy Night")
        main.scheduler.runCurrent()
        vm.refresh()
        main.scheduler.runCurrent()
        settle()
        assertEquals(RowTap.Open(stormFile), vm.row("Stormy Night").tap)
    }

    @Test
    fun `a file this Shelf changed before a check, then deleted outside it, shows missing after the check`() {
        store("urn:uuid:storm" to book("Stormy Night", source = link.value, file = stormFile))
        val vm = shelf()
        vm.downloadAgain("Stormy Night")
        settle()
        assertEquals(RowTap.Open(stormFile), vm.row("Stormy Night").tap)
        File(dir, stormFile).delete()
        vm.refresh()
        settle()
        assertEquals(ROW_FILE_MISSING_SOURCE, vm.row("Stormy Night").detail)
    }

    @Test
    fun `a pending confirmation on a download is cleared when the download arrives`() {
        val vm = shelf()
        vm.download(link, "Stormy Night", null)
        vm.toggleEdit()
        vm.askToRemove(RowKey.Arriving(link))
        settle()
        assertEquals(listOf(RowKey.Shelved("urn:uuid:storm")), vm.snapshot.value!!.rows.map { it.key })
        assertEquals(ShelfMode.Editing(), vm.mode.value)
    }

    @Test
    fun `a download started before the Shelf loaded lands on the loaded reading data`() {
        seed("urn:a" to book("A"))
        val vm = ShelfViewModel(owner(serving()))
        val download = vm.download(link, "Stormy Night", null)
        settle()
        vm.refresh()
        settle()
        assertEquals(DownloadResult.Done("urn:uuid:storm"), download.done())
        assertEquals(setOf("A", "Stormy Night"), vm.titles().toSet())
        vm.onAppPause()
        assertEquals(setOf("urn:a", "urn:uuid:storm"), stored().books.keys)
    }

    @Test
    fun `two Shelves in one process share one owner, so a removal in one isn't undone by the other's flush`() {
        seed("urn:a" to book("A"), "urn:b" to book("B"))
        val first = shelf()
        val second = ShelfViewModel(ShelfOwner.of(File(dir, ".")) { error("a second owner for the same directory") })
        assertSame(first.owner, second.owner)
        first.toggleEdit()
        first.remove(RowKey.Shelved("urn:a"))
        second.onAppPause()
        settle()
        assertEquals(listOf("B"), second.titles())
        assertFalse(stored().books.getValue("urn:a").onShelf)
    }

    @Test
    fun `a dev-start file asks to open the dev Book`() {
        File(dir, "dev-start").writeText("2 10\n")
        val vm = shelf()
        assertEquals(DevStart(2, 10), vm.devStart.value)
    }
}
