package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun settle() = repeat(6) {
        main.scheduler.advanceUntilIdle()
        io.scheduler.advanceUntilIdle()
    }

    private fun shelf(transport: Transport = FakeTransport(mapOf(link.value to Answer(body = storm)))): ShelfViewModel =
        ShelfViewModel(dir, io, transport) { clock }.also {
            it.refresh()
            settle()
        }

    private fun seed(vararg books: Pair<String, BookEntry>) {
        ReadingStore(dir).save { ReadingData(books = mapOf(*books)) }
        books.forEach { (_, entry) -> entry.file?.let { File(dir, it).writeText("epub") } }
    }

    private fun entry(title: String, place: Place? = null, source: String? = null, file: String? = "$title.epub") =
        BookEntry(title, file, place, finished = false, onShelf = true, source = source)

    private fun ShelfViewModel.row(title: String) = rows.value!!.single { it.title == title }

    private fun ShelfViewModel.titles() = rows.value!!.map { it.title }

    private fun <T> Deferred<T>.done(): T = getCompleted()

    private fun stored() = ReadingStore(dir).load()

    @Test
    fun `an empty Shelf has no rows, and Edit can't start on it`() {
        val vm = shelf()
        assertEquals(emptyList(), vm.rows.value)
        vm.toggleEdit()
        vm.refresh()
        settle()
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `a tap opens the Book's file, and never while editing`() {
        seed("urn:a" to entry("A"))
        val vm = shelf()
        assertEquals(File(dir, "A.epub"), vm.tap(vm.row("A")))
        vm.toggleEdit()
        assertNull(vm.tap(vm.row("A")))
        vm.toggleEdit()
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `Remove asks inline, Cancel keeps the Book, and confirming deletes the file but keeps the Place`() {
        val place = Place("c1", 2, 5, "snippet", 10)
        seed("urn:a" to entry("A", place), "urn:b" to entry("B"))
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
        assertEquals(entry("A", place).copy(file = null, onShelf = false), stored().books.getValue("urn:a"))

        vm.remove(RowKey.Shelved("urn:b"))
        settle()
        assertEquals(emptyList(), vm.rows.value)
        assertEquals(ShelfMode.Browsing, vm.mode.value)
    }

    @Test
    fun `a download adds the Book with its source, author and date, and keeps a Place it had`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:storm" to entry("Stormy Night", place, file = null).copy(onShelf = false))) }
        val vm = shelf()
        assertEquals(emptyList(), vm.rows.value)
        clock = 5_000
        val download = vm.download(link, "From the Catalogue", "Catalogue Author")
        assertEquals(listOf(ShelfRow(RowKey.Arriving(link), "From the Catalogue", ROW_DOWNLOADING, RowTap.None)), vm.rows.value)
        assertEquals(download, vm.download(link, "again", null))
        settle()
        assertIs<DownloadState.Done>(download.done())
        assertEquals(listOf(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stormy Night", "Edward Bulwer-Lytton", RowTap.Open(stormFile))), vm.rows.value)
        vm.onAppPause()
        val added = stored().books.getValue("urn:uuid:storm")
        assertEquals(BookEntry("Stormy Night", stormFile, place, false, true, "Edward Bulwer-Lytton", link.value, 5_000), added)
    }

    @Test
    fun `a retryable failure stays as a row that retries, and a permanent one adds nothing`() {
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
        assertEquals(DownloadState.Failed(HttpError(503)), failed.done())
        assertEquals(DownloadState.Failed(Unreachable), unreachable.done())
        assertEquals(DownloadState.Failed(NotAnEpub), notAnEpub.done())
        assertEquals(DownloadState.Failed(CopyProtected), copyProtected.done())
        assertEquals(setOf("Stormy Night", "Offline"), vm.titles().toSet())
        assertEquals(ROW_DOWNLOAD_FAILED, vm.row("Stormy Night").detail)
        assertEquals(RowTap.Download(link, "Stormy Night", null), vm.row("Stormy Night").tap)
        assertTrue(dir.list()!!.none { it.endsWith(".epub") })
    }

    @Test
    fun `tapping a failed row downloads it again`() {
        val answers = mutableMapOf(link.value to Answer(status = 500))
        val vm = shelf(FakeTransport(answers))
        vm.download(link, "Stormy Night", null)
        settle()
        answers[link.value] = Answer(body = storm)
        assertNull(vm.tap(vm.row("Stormy Night")))
        assertEquals(ROW_DOWNLOADING, vm.row("Stormy Night").detail)
        settle()
        assertEquals(listOf(RowTap.Open(stormFile)), vm.rows.value!!.map { it.tap })
    }

    @Test
    fun `a missing file with a source downloads again from it and keeps the Place`() {
        val place = Place("c1", 0, 3, "snippet", 10)
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:storm" to entry("Stormy Night", place, source = link.value, file = stormFile))) }
        val transport = FakeTransport(mapOf(link.value to Answer(body = storm)))
        val vm = shelf(transport)
        assertEquals(ROW_FILE_MISSING_SOURCE, vm.row("Stormy Night").detail)
        assertNull(vm.tap(vm.row("Stormy Night")))
        assertEquals(ROW_DOWNLOADING, vm.row("Stormy Night").detail)
        settle()
        assertEquals(listOf(link), transport.asked)
        assertEquals(RowTap.Open(stormFile), vm.row("Stormy Night").tap)
        assertTrue(File(dir, stormFile).exists())
        vm.onAppPause()
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)
    }

    @Test
    fun `a missing file with no source can only be removed`() {
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:a" to entry("A"))) }
        val transport = FakeTransport(emptyMap())
        val vm = shelf(transport)
        assertEquals(ShelfRow(RowKey.Shelved("urn:a"), "A", ROW_FILE_MISSING, RowTap.None), vm.row("A"))
        assertNull(vm.tap(vm.row("A")))
        settle()
        assertEquals(emptyList(), transport.asked)
        vm.toggleEdit()
        vm.remove(RowKey.Shelved("urn:a"))
        assertEquals(emptyList(), vm.rows.value)
    }

    @Test
    fun `removing a Book mid-download cancels it, and nothing lands`() {
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:storm" to entry("Stormy Night", source = link.value, file = stormFile))) }
        lateinit var vm: ShelfViewModel
        val transport = Transport { url ->
            vm.remove(RowKey.Shelved("urn:uuid:storm"))
            FakeTransport(mapOf(link.value to Answer(body = storm))).get(url)
        }
        vm = shelf(transport)
        val download = vm.download(link, "Stormy Night", null)
        settle()
        assertTrue(download.isCancelled)
        assertEquals(emptyList(), vm.rows.value)
        assertTrue(dir.list()!!.none { it.endsWith(".epub") || it.endsWith(".part") }, dir.list()!!.toList().toString())
    }

    @Test
    fun `removing a download that hasn't arrived cancels it before it asks the network`() {
        val transport = FakeTransport(mapOf(link.value to Answer(body = storm)))
        val vm = shelf(transport)
        val download = vm.download(link, "Stormy Night", null)
        main.scheduler.runCurrent()
        vm.remove(RowKey.Arriving(link))
        settle()
        assertTrue(download.isCancelled)
        assertEquals(emptyList(), transport.asked)
        assertEquals(emptyList(), vm.rows.value)
        assertFalse(File(dir, stormFile).exists())
    }

    @Test
    fun `a dev-start file asks to open the dev Book, with a saver that writes nothing`() {
        ReadingStore(dir).save { ReadingData(settings = Settings(fontStep = 3)) }
        val before = File(dir, "reading-data.json").readText()
        File(dir, "dev-start").writeText("2 10\n")
        val vm = shelf()
        assertEquals(DevStart(2, 10), vm.devStart.value)
        val saver = vm.devSaver()
        assertEquals(ReadingData(), saver.data)
        saver.change { it.copy(settings = Settings(fontStep = 4)) }
        saver.flush()
        settle()
        assertEquals(before, File(dir, "reading-data.json").readText())
    }
}
