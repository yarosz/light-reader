package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Imports from the Tool Manager's inbox over a temp filesDir. The main thread and IO are test
 * dispatchers on separate schedulers, as in [ShelfViewModelTest]; the clock and free space are
 * the test's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportTest {

    private val dir: File = createTempDirectory("import").toFile()
    private val inbox = importInbox(dir)
    private val main = StandardTestDispatcher(TestCoroutineScheduler())
    private val io = StandardTestDispatcher(TestCoroutineScheduler())
    private var clock = 1_700_000_000_000L
    private var space = Long.MAX_VALUE

    private val storm = zipBytes(
        epubFiles(identifier = "urn:uuid:storm", title = "Stormy Night").let {
            it + ("OEBPS/content.opf" to it.getValue("OEBPS/content.opf").replace("</metadata>", "<dc:creator>Edward Bulwer-Lytton</dc:creator></metadata>"))
        },
    )
    private val stormFile = bookFileName("urn:uuid:storm")
    private val place = Place("c0", 0, 5, "dark and", 10, progress = 0.4)

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

    /** The process's owner, its reading data loaded and published. */
    private fun owner(): ShelfOwner = ShelfOwner.of(dir) { ShelfOwner(dir, io, FakeTransport(emptyMap()), usableSpace = { space }) { clock } }.also { it.refresh() }

    /** A fresh owner, as after the process died. */
    private fun relaunched(): ShelfOwner {
        settle()
        ShelfOwner.forget(dir)
        return owner().also {
            it.refresh()
            settle()
        }
    }

    private fun import(owner: ShelfOwner = owner()) {
        owner.importBooks()
        settle()
    }

    /** Puts [bytes] in the inbox as [name], last written [age] ms before the clock. */
    private fun upload(name: String, bytes: ByteArray, age: Long = 60_000): File =
        File(inbox, name).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
            setLastModified(clock - age)
        }

    private fun ShelfOwner.rows() = snapshot.value!!.rows

    private fun ShelfOwner.notices() = snapshot.value!!.notices

    private fun inboxFiles() = inbox.list()!!.sorted()

    private fun stored() = ReadingStore(dir).load()

    @Test
    fun `an EPUB in the inbox goes on the Shelf with its own title and author and no source, and leaves the inbox`() {
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)

        assertEquals(emptyList(), inboxFiles())
        assertTrue(File(dir, stormFile).exists())
        val row = owner.rows().single()
        assertEquals(ShelfRow(RowKey.Shelved("urn:uuid:storm"), "Stormy Night", ROW_NOT_STARTED, RowTap.Open(stormFile)), row)
        val book = stored().books.getValue("urn:uuid:storm")
        assertEquals(Book("Stormy Night", stormFile, null, finished = false, onShelf = true, author = "Edward Bulwer-Lytton", source = null, addedAt = clock), book)
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `importing the same Book again keeps one row and its Place`() {
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        ReadingStore(dir).save { stored().let { it.copy(books = it.books + ("urn:uuid:storm" to it.books.getValue("urn:uuid:storm").copy(place = place))) } }
        val again = relaunched()

        upload("Storm (1).epub", storm)
        import(again)
        assertEquals(1, again.rows().size)
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)
        assertEquals(listOf(stormFile), dir.list()!!.filter { it.endsWith(".epub") })
    }

    @Test
    fun `a removed Book imported again comes back with its Place`() {
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:storm" to Book("Stormy Night", null, place, finished = false, onShelf = false))) }
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)
        assertEquals("Stormy Night", owner.rows().single().title)

        owner.remove(RowKey.Shelved("urn:uuid:storm"))
        settle()
        assertEquals(emptyList(), owner.rows())
        upload("storm.epub", storm)
        import(owner)
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)
        assertTrue(stored().books.getValue("urn:uuid:storm").onShelf)
    }

    @Test
    fun `a PDF, a broken zip and a copy-protected EPUB are deleted, each with its notice, which survives a relaunch`() {
        upload("notes.pdf", "%PDF-1.4 not a book".toByteArray(), age = 90_000)
        upload("broken.epub", storm.copyOf(storm.size / 2), age = 80_000)
        upload("locked.epub", zipBytes(epubFiles() + ("META-INF/rights.xml" to "<rights/>")), age = 70_000)
        val owner = owner()
        import(owner)

        assertEquals(emptyList(), inboxFiles())
        assertEquals(emptyList(), owner.rows())
        val expected = listOf(
            ImportNotice("notes.pdf", NotAnEpub),
            ImportNotice("broken.epub", NotAnEpub),
            ImportNotice("locked.epub", CopyProtected),
        )
        assertEquals(expected, owner.notices())
        assertEquals(
            listOf("Couldn’t add notes.pdf: it isn’t an EPUB.", "Couldn’t add broken.epub: it isn’t an EPUB.", "Couldn’t add locked.epub: it’s copy-protected."),
            noticeLines(owner.notices()),
        )
        assertEquals(expected, relaunched().notices())
    }

    @Test
    fun `a tap clears every notice, for good`() {
        upload("notes.pdf", "not a book".toByteArray())
        val vm = ShelfViewModel(owner())
        import(vm.owner)
        assertEquals(1, vm.snapshot.value!!.notices.size)

        vm.clearNotices()
        settle()
        assertEquals(emptyList(), vm.snapshot.value!!.notices)
        assertEquals(emptyList(), relaunched().notices())
    }

    @Test
    fun `a notice the Shelf showed goes the next time the Shelf shows, and one that arrives while it shows is shown`() {
        upload("notes.pdf", "not a book".toByteArray())
        val owner = owner()
        import(owner)
        val vm = ShelfViewModel(owner)
        vm.shown()
        settle()
        assertEquals(listOf(ImportNotice("notes.pdf", NotAnEpub, shown = true)), owner.notices())

        upload("more.pdf", "not a book".toByteArray())
        import(owner)
        assertEquals(listOf(ImportNotice("notes.pdf", NotAnEpub, shown = true), ImportNotice("more.pdf", NotAnEpub, shown = true)), owner.notices())

        vm.hidden()
        upload("late.pdf", "not a book".toByteArray())
        import(owner)
        assertEquals(ImportNotice("late.pdf", NotAnEpub), owner.notices().last())

        vm.shown()
        settle()
        assertEquals(listOf(ImportNotice("late.pdf", NotAnEpub, shown = true)), owner.notices())
        vm.hidden()
        vm.shown()
        settle()
        assertEquals(emptyList(), owner.notices())
        assertEquals(emptyList(), relaunched().notices())
    }

    @Test
    fun `a file that fails while still being written is left for later, and rejected once it has been quiet`() {
        val partial = upload("storm.epub", storm.copyOf(storm.size / 2), age = 2_000)
        val empty = upload("new.epub", ByteArray(0), age = 0)
        val owner = owner()
        import(owner)
        assertEquals(listOf("new.epub", "storm.epub"), inboxFiles())
        assertEquals(emptyList(), owner.notices())

        partial.writeBytes(storm)
        partial.setLastModified(clock)
        import(owner)
        assertEquals("Stormy Night", owner.rows().single().title)
        assertEquals(listOf("new.epub"), inboxFiles())

        clock += IMPORT_QUIET_MS + 1
        import(owner)
        assertFalse(empty.exists())
        assertEquals(listOf(ImportNotice("new.epub", NotAnEpub)), owner.notices())
    }

    @Test
    fun `two passes over one inbox make the same Shelf, and passes started together take each file once`() {
        upload("storm.epub", storm)
        upload("notes.pdf", "not a book".toByteArray())
        val owner = owner()
        owner.importBooks()
        owner.shelfShown()
        owner.importBooks()
        settle()
        val first = owner.snapshot.value!!
        assertEquals(1, first.rows.size)
        assertEquals(listOf(ImportNotice("notes.pdf", NotAnEpub)), first.notices)
        assertEquals(emptyList(), inboxFiles())

        import(owner)
        assertEquals(first.rows, owner.rows())
        assertEquals(first.notices, owner.notices())
        assertEquals(first.data.books, stored().books)
    }

    @Test
    fun `a file that lands while a pass runs is taken by that pass, since LightOS drops its report then`() {
        upload("storm.epub", storm)
        val owner = ShelfOwner.of(dir) {
            ShelfOwner(dir, io, FakeTransport(emptyMap()), usableSpace = {
                // Asked as the first Book is checked: the second lands mid-pass.
                if (!File(inbox, "calm.epub").exists() && dir.list()!!.none { it == bookFileName("urn:uuid:calm") }) {
                    upload("calm.epub", zipBytes(epubFiles(identifier = "urn:uuid:calm", title = "Calm Morning")))
                }
                Long.MAX_VALUE
            }) { clock }
        }
        owner.refresh()
        import(owner)
        assertEquals(listOf("Calm Morning", "Stormy Night"), owner.rows().map { it.title }.sorted())
        assertEquals(emptyList(), inboxFiles())
    }

    @Test
    fun `a Book the phone has no room for stays in the inbox with a notice, and is added once there is room`() {
        space = MIN_FREE_BYTES - 1
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        assertEquals(listOf("storm.epub"), inboxFiles())
        assertEquals(emptyList(), owner.rows())
        assertEquals(listOf(ImportNotice("storm.epub", DiskError)), owner.notices())
        assertEquals("Couldn’t add storm.epub: there isn’t enough space on your phone.", noticeLine(owner.notices().single()))

        space = Long.MAX_VALUE
        import(owner)
        assertEquals(emptyList(), inboxFiles())
        assertEquals("Stormy Night", owner.rows().single().title)
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `a hidden file is deleted without a notice once quiet, and a directory is left alone`() {
        val recent = upload(".storm.epub.part", storm.copyOf(10), age = 1_000)
        upload(".DS_Store", ByteArray(8))
        File(inbox, "folder").mkdirs()
        val owner = owner()
        import(owner)
        assertEquals(listOf(".storm.epub.part", "folder"), inboxFiles())
        assertEquals(emptyList(), owner.notices())

        clock += IMPORT_QUIET_MS
        import(owner)
        assertFalse(recent.exists())
        assertEquals(listOf("folder"), inboxFiles())
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `the notice names three files, then how many more`() {
        val notices = (1..5).map { ImportNotice("f$it.pdf", NotAnEpub) }
        assertEquals(listOf("Couldn’t add f1.pdf: it isn’t an EPUB.", "Couldn’t add f2.pdf: it isn’t an EPUB.", "Couldn’t add f3.pdf: it isn’t an EPUB.", "and 2 more"), noticeLines(notices))
        assertEquals(3, noticeLines(notices.take(3)).size)
        assertEquals(emptyList(), noticeLines(emptyList()))
    }

    @Test
    fun `a notice file that doesn't parse reads as no notices, and an unknown reason is dropped`() {
        File(dir, "import-notices.json").writeText("{not json")
        assertEquals(emptyList(), ImportNoticeStore(dir).load())
        File(dir, "import-notices.json").writeText("""{"notices":[{"name":"a.pdf","reason":"gremlins"},{"name":"b.pdf","reason":"not-an-epub","shown":true}]}""")
        assertEquals(listOf(ImportNotice("b.pdf", NotAnEpub, shown = true)), ImportNoticeStore(dir).load())
    }
}
