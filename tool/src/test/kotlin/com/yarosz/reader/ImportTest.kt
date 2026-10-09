package com.yarosz.reader

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Imports from the Tool Manager's inbox over a temp filesDir. The main thread and IO are test
 * dispatchers on separate schedulers, as in [ShelfViewModelTest]; the clock, free space, and the
 * owner's renames and deletes are the test's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportTest {

    private val dir: File = createTempDirectory("import").toFile()
    private val inbox = importInbox(dir)
    private val main = StandardTestDispatcher(TestCoroutineScheduler())
    private val io = StandardTestDispatcher(TestCoroutineScheduler())
    private var clock = 1_700_000_000_000L
    private var space = Long.MAX_VALUE

    /** Runs each time the owner asks for free space, which it does as it checks a file that passed. */
    private var onSpace: () -> Unit = {}
    private var renames: (File, File) -> Boolean = File::renameTo
    private var deletes: (File) -> Boolean = File::delete

    private val storm = zipBytes(
        epubFiles(identifier = "urn:uuid:storm", title = "Stormy Night").let {
            it + ("OEBPS/content.opf" to it.getValue("OEBPS/content.opf").replace("</metadata>", "<dc:creator>Edward Bulwer-Lytton</dc:creator></metadata>"))
        },
    )
    private val calm = zipBytes(epubFiles(identifier = "urn:uuid:calm", title = "Calm Morning"))
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

    /** Runs both dispatchers until neither has work, or a bounded number of hops, so a pass that never ends fails its test instead of hanging it. */
    private fun settle() = repeat(100) {
        main.scheduler.advanceUntilIdle()
        io.scheduler.advanceUntilIdle()
    }

    /** The process's owner, its reading data loaded and published. */
    private fun owner(now: () -> Long = { clock }): ShelfOwner = ShelfOwner.of(dir) {
        ShelfOwner(
            dir,
            io,
            FakeTransport(emptyMap()),
            usableSpace = {
                onSpace()
                space
            },
            rename = { from, to -> renames(from, to) },
            delete = { deletes(it) },
            now = now,
        )
    }.also { it.refresh() }

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
    private fun upload(name: String, bytes: ByteArray, age: Long = 120_000): File =
        File(inbox, name).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
            setLastModified(clock - age)
        }

    private fun ShelfOwner.rows() = snapshot.value!!.rows

    private fun ShelfOwner.notices() = snapshot.value!!.notices

    private fun inboxFiles() = inbox.list()!!.sorted()

    private fun stored() = ReadingStore(dir).load()

    /** The Shelf screen showing, and drawing the notices it has, as the screen does when it shows or they change. */
    private fun ShelfViewModel.showsShelf() {
        shown()
        settle()
        rendered(snapshot.value!!.notices)
        settle()
    }

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
    fun `a Book stored under another file name is replaced without leaving that file behind, unless another Book names it`() {
        File(dir, "old.epub").writeBytes(storm)
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:storm" to Book("Stormy Night", "old.epub", place, finished = false, onShelf = true))) }
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        assertFalse(File(dir, "old.epub").exists())
        assertEquals(stormFile, stored().books.getValue("urn:uuid:storm").file)
        assertEquals(place, stored().books.getValue("urn:uuid:storm").place)

        ShelfOwner.forget(dir)
        dir.listFiles()!!.filter { it.isFile }.forEach { it.delete() }
        File(dir, "old.epub").writeBytes(storm)
        ReadingStore(dir).save {
            ReadingData(
                books = mapOf(
                    "urn:uuid:storm" to Book("Stormy Night", "old.epub", place, finished = false, onShelf = true),
                    "urn:uuid:twin" to Book("Twin", "old.epub", null, finished = false, onShelf = true),
                ),
            )
        }
        upload("storm.epub", storm)
        import(owner())
        assertTrue(File(dir, "old.epub").exists())
        assertEquals(stormFile, stored().books.getValue("urn:uuid:storm").file)
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
            ImportNotice("notes.pdf", ImportFailure.NotAnEpub),
            ImportNotice("broken.epub", ImportFailure.NotAnEpub),
            ImportNotice("locked.epub", ImportFailure.CopyProtected),
        )
        assertEquals(expected, owner.notices())
        assertEquals(
            listOf("Couldn’t add notes.pdf: it isn’t an EPUB.", "Couldn’t add broken.epub: it isn’t an EPUB.", "Couldn’t add locked.epub: it’s copy-protected."),
            noticeLines(owner.notices()),
        )
        assertEquals(expected, relaunched().notices())
    }

    @Test
    fun `a file over the size limit is deleted as too large, and one at the limit is checked`() {
        inbox.mkdirs()
        val huge = File(inbox, "huge.epub").apply { RandomAccessFile(this, "rw").use { it.setLength(MAX_BOOK_BYTES + 1) } }
        huge.setLastModified(clock - 120_000)
        val limit = File(inbox, "limit.epub").apply { RandomAccessFile(this, "rw").use { it.setLength(MAX_BOOK_BYTES) } }
        limit.setLastModified(clock - 110_000)
        val owner = owner()
        import(owner)
        assertEquals(emptyList(), inboxFiles())
        assertEquals(listOf(ImportNotice("huge.epub", ImportFailure.TooLarge), ImportNotice("limit.epub", ImportFailure.NotAnEpub)), owner.notices())
        assertEquals("Couldn’t add huge.epub: it’s too large.", noticeLine(owner.notices().first()))
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
    fun `a notice the Shelf drew goes the next time the Shelf shows, and one drawn while it shows is shown`() {
        upload("notes.pdf", "not a book".toByteArray())
        val owner = owner()
        import(owner)
        val vm = ShelfViewModel(owner)
        vm.showsShelf()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub, shown = true)), owner.notices())

        upload("more.pdf", "not a book".toByteArray())
        import(owner)
        vm.rendered(owner.notices())
        settle()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub, shown = true), ImportNotice("more.pdf", ImportFailure.NotAnEpub, shown = true)), owner.notices())

        vm.hidden()
        upload("late.pdf", "not a book".toByteArray())
        import(owner)
        vm.rendered(owner.notices())
        settle()
        assertEquals(ImportNotice("late.pdf", ImportFailure.NotAnEpub), owner.notices().last())

        vm.showsShelf()
        assertEquals(listOf(ImportNotice("late.pdf", ImportFailure.NotAnEpub, shown = true)), owner.notices())
        vm.hidden()
        vm.shown()
        settle()
        assertEquals(emptyList(), owner.notices())
        assertEquals(emptyList(), relaunched().notices())
    }

    @Test
    fun `a notice that arrives while Reader is paused is still there when it resumes, and goes the time after`() {
        val owner = owner()
        val vm = ShelfViewModel(owner)
        vm.showsShelf()
        // The screen turns off: the SDK pauses the Shelf and never hides it.
        vm.onAppPause()
        upload("notes.pdf", "not a book".toByteArray())
        import(owner)
        vm.rendered(owner.notices())
        settle()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub)), owner.notices())

        // The screen turns on: the SDK shows the Shelf again, which still draws the notice.
        vm.shown()
        settle()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub, shown = true)), owner.notices())

        vm.onAppPause()
        vm.shown()
        settle()
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `a Shelf view model left behind by a recreated activity marks no notice shown`() {
        val owner = owner()
        val stale = ShelfViewModel(owner)
        stale.showsShelf()
        // LightOS recreates the activity: the old one is paused, and its view model is never cleared.
        stale.onAppPause()
        val vm = ShelfViewModel(owner)
        upload("notes.pdf", "not a book".toByteArray())
        import(owner)
        stale.rendered(owner.notices())
        settle()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub)), owner.notices())

        vm.showsShelf()
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub, shown = true)), owner.notices())
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

        clock += IMPORT_GIVE_UP_MS + 1
        import(owner)
        assertFalse(empty.exists())
        assertEquals(listOf(ImportNotice("new.epub", ImportFailure.NotAnEpub)), owner.notices())
    }

    @Test
    fun `an upload that stalled for half a minute is kept, and rejected after a minute without a write`() {
        val stalled = upload("storm.epub", storm.copyOf(storm.size / 2), age = 30_000)
        val owner = owner()
        import(owner)
        assertTrue(stalled.exists())
        assertEquals(emptyList(), owner.notices())

        clock += 30_000
        import(owner)
        assertFalse(stalled.exists())
        assertEquals(listOf(ImportNotice("storm.epub", ImportFailure.NotAnEpub)), owner.notices())
    }

    @Test
    fun `a pass waits for a file still arriving at most IMPORT_WAITS times`() {
        val start = clock
        // Written in the future, so it stays recent however long the pass waits.
        upload("storm.epub", storm.copyOf(10)).setLastModified(start + 365L * 24 * 60 * 60 * 1000)
        val owner = owner { start + main.scheduler.currentTime }
        settle()
        val began = main.scheduler.currentTime
        val pass = owner.importBooks()
        settle()
        assertTrue(pass.isCompleted)
        assertEquals(IMPORT_WAITS * IMPORT_QUIET_MS, main.scheduler.currentTime - began)
        assertEquals(listOf("storm.epub"), inboxFiles())
    }

    @Test
    fun `two passes over one inbox make the same Shelf, and passes queued together change nothing more`() {
        upload("storm.epub", storm)
        upload("notes.pdf", "not a book".toByteArray())
        val owner = owner()
        owner.importBooks()
        owner.shelfShown()
        owner.importBooks()
        settle()
        val first = owner.snapshot.value!!
        assertEquals(1, first.rows.size)
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub)), first.notices)
        assertEquals(emptyList(), inboxFiles())

        import(owner)
        assertEquals(first.rows, owner.rows())
        assertEquals(first.notices, owner.notices())
        assertEquals(first.data.books, stored().books)
    }

    @Test
    fun `passes running at once on several threads check each file once`() {
        val pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val checks = AtomicInteger()
        // Holds a check until a second one starts, which only a second pass running alongside can do.
        val together = CountDownLatch(2)
        upload("storm.epub", storm)
        val owner = ShelfOwner.of(dir) {
            ShelfOwner(dir, pool, FakeTransport(emptyMap()), usableSpace = {
                checks.incrementAndGet()
                together.countDown()
                together.await(500, TimeUnit.MILLISECONDS)
                Long.MAX_VALUE
            }) { clock }
        }
        try {
            val passes = listOf(owner.importBooks(), owner.importBooks())
            val deadline = System.currentTimeMillis() + 10_000
            while (passes.any { !it.isCompleted } && System.currentTimeMillis() < deadline) {
                main.scheduler.advanceUntilIdle()
                Thread.sleep(5)
            }
            assertTrue(passes.all { it.isCompleted })
            assertEquals(1, checks.get())
            assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
            assertEquals(emptyList(), inboxFiles())
        } finally {
            ShelfOwner.forget(dir)
            pool.close()
        }
    }

    @Test
    fun `a file that lands while a pass runs is taken by that pass, since LightOS drops its report then`() {
        upload("storm.epub", storm)
        onSpace = {
            // Asked as the first Book is checked: the second lands mid-pass.
            if (!File(inbox, "calm.epub").exists() && dir.list()!!.none { it == bookFileName("urn:uuid:calm") }) upload("calm.epub", calm)
        }
        val owner = owner()
        import(owner)
        assertEquals(listOf("Calm Morning", "Stormy Night"), owner.rows().map { it.title }.sorted())
        assertEquals(emptyList(), inboxFiles())
    }

    @Test
    fun `a file that lands after a pass that took nothing last listed the inbox is taken by that pass`() {
        upload("storm.epub", storm)
        var asked = 0
        onSpace = {
            // The first check finds no room, so the pass takes nothing; meanwhile a second file lands.
            if (asked++ == 0) {
                space = MIN_FREE_BYTES - 1
                upload("calm.epub", calm)
            } else {
                space = Long.MAX_VALUE
            }
        }
        val owner = owner()
        import(owner)
        assertEquals(listOf("Calm Morning", "Stormy Night"), owner.rows().map { it.title }.sorted())
        assertEquals(emptyList(), inboxFiles())
    }

    @Test
    fun `a Book written to between its check and its move waits for a later check`() {
        val file = upload("storm.epub", storm)
        onSpace = {
            if (file.length() == storm.size.toLong()) {
                file.writeBytes(storm.copyOf(100))
                file.setLastModified(clock)
            }
        }
        val owner = owner()
        import(owner)
        assertEquals(emptyList(), owner.rows())
        assertEquals(listOf("storm.epub"), inboxFiles())
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `a rejected file written to between its check and its deletion waits for a later check`() {
        val bad = upload("notes.pdf", "not a book".toByteArray(), age = 150_000)
        upload("storm.epub", storm)
        // Asked as storm.epub, checked after notes.pdf, passes.
        onSpace = {
            if (bad.length() < 100) {
                bad.appendBytes(ByteArray(100))
                bad.setLastModified(clock)
            }
        }
        val owner = owner()
        import(owner)
        assertTrue(bad.exists())
        assertEquals(emptyList(), owner.notices())
        assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
    }

    @Test
    fun `a rejected file that can't be deleted stays, with one notice, and the pass ends`() {
        deletes = { false }
        val bad = upload("notes.pdf", "not a book".toByteArray())
        val owner = owner()
        settle()
        val pass = owner.importBooks()
        settle()
        assertTrue(pass.isCompleted)
        assertTrue(bad.exists())
        assertEquals(listOf(ImportNotice("notes.pdf", ImportFailure.NotAnEpub)), owner.notices())
    }

    @Test
    fun `a Book that can't be moved stays with a notice that says so, saying no room only when room ran out, and is added later`() {
        renames = { _, _ -> false }
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        assertEquals(listOf("storm.epub"), inboxFiles())
        assertEquals(emptyList(), owner.rows())
        assertEquals(listOf(ImportNotice("storm.epub", ImportFailure.NotSaved)), owner.notices())
        assertEquals("Couldn’t add storm.epub: it couldn’t be saved on your phone.", noticeLine(owner.notices().single()))

        renames = { _, _ ->
            space = MIN_FREE_BYTES - 1
            false
        }
        import(owner)
        assertEquals(listOf(ImportNotice("storm.epub", ImportFailure.NoRoom)), owner.notices())

        renames = File::renameTo
        space = Long.MAX_VALUE
        import(owner)
        assertEquals(emptyList(), inboxFiles())
        assertEquals("Stormy Night", owner.rows().single().title)
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `a Book the phone has no room for stays in the inbox with a notice, and is added once there is room`() {
        space = MIN_FREE_BYTES - 1
        upload("storm.epub", storm)
        val owner = owner()
        import(owner)
        assertEquals(listOf("storm.epub"), inboxFiles())
        assertEquals(emptyList(), owner.rows())
        assertEquals(listOf(ImportNotice("storm.epub", ImportFailure.NoRoom)), owner.notices())
        assertEquals("Couldn’t add storm.epub: there isn’t enough space on your phone.", noticeLine(owner.notices().single()))

        space = Long.MAX_VALUE
        import(owner)
        assertEquals(emptyList(), inboxFiles())
        assertEquals("Stormy Night", owner.rows().single().title)
        assertEquals(emptyList(), owner.notices())
    }

    @Test
    fun `a pass that finds a notice's file the same leaves the notice where it was, shown or not`() {
        space = MIN_FREE_BYTES - 1
        upload("storm.epub", storm, age = 150_000)
        val owner = owner()
        import(owner)
        upload("notes.pdf", "not a book".toByteArray(), age = 140_000)
        deletes = { false }
        import(owner)
        val vm = ShelfViewModel(owner)
        vm.showsShelf()
        val drawn = listOf(ImportNotice("storm.epub", ImportFailure.NoRoom, shown = true), ImportNotice("notes.pdf", ImportFailure.NotAnEpub, shown = true))
        assertEquals(drawn, owner.notices())

        import(owner)
        assertEquals(drawn, owner.notices())
    }

    @Test
    fun `a hidden name is checked like any upload, and a directory is left alone`() {
        upload(".storm.epub", storm)
        upload("._notes.epub", ByteArray(8))
        File(inbox, "folder").mkdirs()
        val owner = owner()
        import(owner)
        assertEquals(listOf("folder"), inboxFiles())
        assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
        assertEquals(listOf(ImportNotice("._notes.epub", ImportFailure.NotAnEpub)), owner.notices())
    }

    @Test
    fun `a Book's file that no Book names, as a kill between a move and its save leaves, is imported again`() {
        File(dir, stormFile).writeBytes(storm)
        File(dir, DEV_BOOK_FILE).writeBytes(calm)
        val gone = File(dir, bookFileName("urn:uuid:calm")).apply { writeBytes(calm) }
        File(dir, "download-1.part").writeBytes(calm)
        ReadingStore(dir).save { ReadingData(books = mapOf("urn:uuid:calm" to Book("Calm Morning", null, place, finished = false, onShelf = false))) }
        val owner = owner()
        import(owner)
        assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
        assertTrue(File(dir, stormFile).exists())
        assertEquals(emptyList(), inboxFiles())
        assertTrue(File(dir, DEV_BOOK_FILE).exists())
        assertTrue(gone.exists())
        assertFalse(stored().books.getValue("urn:uuid:calm").onShelf)
        assertTrue(File(dir, "download-1.part").exists())
    }

    @Test
    fun `a Book's file stays where it is when the reading data and its backup don't parse`() {
        val book = File(dir, stormFile).apply { writeBytes(storm) }
        File(dir, "reading-data.json").writeText("{ not json")
        File(dir, "reading-data.json.bak").writeText("also not json")
        import()
        assertTrue(book.exists())
        assertEquals(emptyList(), inboxFiles())
        assertEquals(emptyList(), owner().rows())
    }

    @Test
    fun `a Book's file stays where it is when there is no reading data at all`() {
        val book = File(dir, stormFile).apply { writeBytes(storm) }
        import()
        assertTrue(book.exists())
        assertEquals(emptyList(), inboxFiles())
        assertEquals(emptyList(), owner().rows())
    }

    @Test
    fun `a Book's file that no Book names is imported again when the reading data comes from its backup`() {
        File(dir, stormFile).writeBytes(storm)
        ReadingStore(dir).save { ReadingData() }
        ReadingStore(dir).save { ReadingData() }
        File(dir, "reading-data.json").writeText("{ not json")
        val owner = owner()
        import(owner)
        assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
        assertTrue(File(dir, stormFile).exists())
        assertEquals(emptyList(), inboxFiles())
    }

    @Test
    fun `LightOS's report imports through the process's owner, and leaves the files for the Shelf when there is none`() {
        assertNull(ShelfOwner.ofProcess())
        upload("storm.epub", storm)
        val unowned = CoroutineScope(main).launch { ReaderEntryPoint.onToolManagerDataUpdate() }
        settle()
        assertTrue(unowned.isCompleted)
        assertEquals(listOf("storm.epub"), inboxFiles())

        val owner = owner()
        settle()
        val report = CoroutineScope(main).launch { ReaderEntryPoint.onToolManagerDataUpdate() }
        settle()
        assertTrue(report.isCompleted)
        assertEquals(listOf("Stormy Night"), owner.rows().map { it.title })
        assertEquals(emptyList(), inboxFiles())
    }

    @Test
    fun `the notice names three files, then how many more`() {
        val notices = (1..5).map { ImportNotice("f$it.pdf", ImportFailure.NotAnEpub) }
        assertEquals(listOf("Couldn’t add f1.pdf: it isn’t an EPUB.", "Couldn’t add f2.pdf: it isn’t an EPUB.", "Couldn’t add f3.pdf: it isn’t an EPUB.", "and 2 more"), noticeLines(notices))
        assertEquals(3, noticeLines(notices.take(3)).size)
        assertEquals(emptyList(), noticeLines(emptyList()))
    }

    @Test
    fun `a notice file that doesn't parse reads as no notices, an unknown reason is dropped, and every reason round-trips`() {
        File(dir, "import-notices.json").writeText("{not json")
        assertEquals(emptyList(), ImportNoticeStore(dir).load())
        File(dir, "import-notices.json").writeText("""{"notices":[{"name":"a.pdf","reason":"gremlins"},{"name":"b.pdf","reason":"not-an-epub","shown":true}]}""")
        assertEquals(listOf(ImportNotice("b.pdf", ImportFailure.NotAnEpub, shown = true)), ImportNoticeStore(dir).load())
        val every = ImportFailure.entries.map { ImportNotice("${it.name}.epub", it) }
        ImportNoticeStore(dir).save(every)
        assertEquals(every, ImportNoticeStore(dir).load())
    }
}
