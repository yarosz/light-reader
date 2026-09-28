package com.yarosz.reader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Drives the Catalogue screens' view models over a temp filesDir, with Gutenberg's and Standard
 * Ebooks' real responses (src/test/fixtures/catalogues) behind a fake network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModelTest {

    private val dir: File = createTempDirectory("catalogue-vm").toFile()
    private val main = StandardTestDispatcher(TestCoroutineScheduler())
    private val io = StandardTestDispatcher(TestCoroutineScheduler())
    private var clock = 1_000L

    private fun url(value: String) = HttpsUrl.parse(value)!!
    private fun fixture(name: String) = File("src/test/fixtures/catalogues/$name").readBytes()

    private val bookPage = url("https://www.gutenberg.org/ebooks/1342.opds")
    private val noImages = url("https://www.gutenberg.org/ebooks/1342.epub.noimages")
    private val pride = zipBytes(epubFiles(identifier = "http://www.gutenberg.org/1342", title = "Pride and Prejudice"))
    private val home = url("https://books.example.org/opds")

    private val answers = mutableMapOf(
        GUTENBERG.url.value to Answer(body = fixture("gutenberg-root.xml")),
        "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads" to Answer(body = fixture("gutenberg-popular.xml")),
        bookPage.value to Answer(body = fixture("gutenberg-1342.xml")),
        noImages.value to Answer(body = pride),
        "https://www.gutenberg.org/catalog/osd-books.xml" to Answer(body = fixture("gutenberg-osd.xml")),
        "https://m.gutenberg.org/ebooks/search.opds/?query=austen" to Answer(body = fixture("gutenberg-popular.xml")),
        home.value to Answer(body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Home books</title></feed>""".toByteArray()),
    )

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

    private fun owner() = ShelfOwner.of(dir) { ShelfOwner(dir, io, FakeTransport(answers)) { clock } }.also {
        it.refresh()
        settle()
    }

    private fun page(source: PageSource, catalogue: Catalogue = GUTENBERG) = CataloguePageViewModel(owner(), catalogue, source).also { settle() }

    private fun openBook(): CataloguePageViewModel {
        val list = page(PageSource.Feed(url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"), popularEntry()))
        val entry = (list.state.value as PageState.Listing).entries.first()
        return page(entryTarget(entry)!!)
    }

    private fun popularEntry() = CatalogueEntry("Popular", emptyList(), null, null, null, null, emptyList(), emptyList())

    private fun stored() = ReadingStore(dir).load()

    @Test
    fun `a Catalogue's root lists its entries and its search`() {
        val root = page(PageSource.Root)
        val listing = assertIs<PageState.Listing>(root.state.value)
        assertEquals(listOf("Popular", "Latest", "Random"), listing.entries.map { it.title })
        assertIs<CatalogueSearch.Description>(listing.search)
    }

    @Test
    fun `Add to Shelf downloads the Book, the page follows it to Read, and it lands on the Shelf with its source`() {
        val book = openBook()
        val add = assertIs<DetailAction.Download>(book.detail.value!!.action)
        assertEquals(DETAIL_ADD, add.label)
        assertEquals("558 KB", formatSize(add.link.length!!))
        book.download(add)
        main.scheduler.runCurrent()
        assertEquals(DetailAction.Downloading, book.detail.value!!.action)
        settle()
        val read = assertIs<DetailAction.Read>(book.detail.value!!.action)
        assertTrue(File(dir, read.file).exists())
        val entry = stored().books.getValue("http://www.gutenberg.org/1342")
        assertEquals(noImages.value, entry.source)
        assertEquals("Pride and Prejudice", entry.title)
        assertEquals("Jane Austen", entry.author)
    }

    @Test
    fun `a copy-protected Book shows its copy on the detail page and adds nothing to the Shelf`() {
        answers[noImages.value] = Answer(body = zipBytes(epubFiles() + ("META-INF/rights.xml" to "<rights/>")))
        val book = openBook()
        book.download(book.detail.value!!.action as DetailAction.Download)
        settle()
        assertEquals(BookDetail(DetailAction.None, FailureCopy(COPY_COPY_PROTECTED, retry = false)), book.detail.value)
        assertEquals(emptyList(), owner().rows.value)
    }

    @Test
    fun `a removed Book is added back with its Place, and a permanent failure adding it back leaves no row`() {
        val place = Place("c0", 0, 5, "was a", 10)
        ReadingStore(dir).save {
            ReadingData(books = mapOf("http://www.gutenberg.org/1342" to BookEntry("Pride and Prejudice", null, place, false, onShelf = false, source = noImages.value)))
        }
        answers[noImages.value] = Answer(body = "not an epub".toByteArray())
        val book = openBook()
        val add = assertIs<DetailAction.Download>(book.detail.value!!.action)
        assertEquals(DetailAction.Download(add.link, DETAIL_ADD, "http://www.gutenberg.org/1342"), add)
        book.download(add)
        settle()
        assertEquals(FailureCopy(COPY_NOT_AN_EPUB, retry = false), book.detail.value!!.problem)
        assertEquals(emptyList(), owner().rows.value)

        answers[noImages.value] = Answer(body = pride)
        val again = openBook()
        again.download(again.detail.value!!.action as DetailAction.Download)
        settle()
        assertIs<DetailAction.Read>(again.detail.value!!.action)
        assertEquals(place, stored().books.getValue("http://www.gutenberg.org/1342").place)
    }

    @Test
    fun `a failed page shows why, and Retry fetches it again`() {
        answers.remove(GUTENBERG.url.value)
        val root = page(PageSource.Root)
        assertEquals(PageState.Failed(Unreachable), root.state.value)
        answers[GUTENBERG.url.value] = Answer(body = fixture("gutenberg-root.xml"))
        root.load()
        settle()
        assertIs<PageState.Listing>(root.state.value)
    }

    @Test
    fun `a Catalogue that needs a login is its own failure`() {
        answers[GUTENBERG.url.value] = Answer(status = 401)
        assertEquals(PageState.Failed(HttpError(401)), page(PageSource.Root).state.value)
    }

    @Test
    fun `search resolves the OpenSearch description, then fetches the results`() {
        val search = CatalogueSearch.Description(url("https://www.gutenberg.org/catalog/osd-books.xml"))
        val results = page(PageSource.Search(search, "austen"))
        assertEquals("Pride and Prejudice", (results.state.value as PageState.Listing).entries.first().title)
    }

    @Test
    fun `More appends the next page, and a failed More offers Retry in place`() {
        val list = page(PageSource.Feed(url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"), popularEntry()))
        val first = (list.state.value as PageState.Listing)
        list.more()
        settle()
        assertEquals(More.Failed(Unreachable), (list.state.value as PageState.Listing).more)
        answers[first.next!!.value] = Answer(body = fixture("gutenberg-popular.xml"))
        list.more()
        settle()
        assertEquals(first.entries.size * 2, (list.state.value as PageState.Listing).entries.size)
    }

    @Test
    fun `adding a Catalogue fetches it, names it by its title, and saves it at once`() {
        val add = AddCatalogueViewModel(owner())
        add.typed("books.example.org/opds")
        settle()
        assertEquals(AddStatus.Added, add.status.value)
        assertEquals(Catalogue("Home books", home), stored().catalogueList().last().catalogue)
    }

    @Test
    fun `an address that isn't a Catalogue, or one already listed, isn't added`() {
        answers[home.value] = Answer(body = "<html><body>hello</body></html>".toByteArray())
        val add = AddCatalogueViewModel(owner())
        add.typed("https://books.example.org/opds")
        settle()
        assertEquals(AddStatus.Failed("https://books.example.org/opds", FailureCopy(COPY_UNREADABLE, retry = false)), add.status.value)
        add.typed("www.gutenberg.org/ebooks.opds/")
        assertEquals(AddStatus.Failed("www.gutenberg.org/ebooks.opds/", FailureCopy(ADD_CATALOGUE_DUPLICATE, retry = false)), add.status.value)
        assertEquals(SHIPPED_CATALOGUES, stored().catalogueList().map { it.catalogue })
    }

    @Test
    fun `an http address that has no https says so`() {
        answers["https://plain.example.org/opds"] = Answer(connectFailure = java.net.ConnectException("refused"))
        val add = AddCatalogueViewModel(owner())
        add.typed("http://plain.example.org/opds")
        settle()
        assertEquals(FailureCopy(COPY_NO_HTTPS, retry = false), (add.status.value as AddStatus.Failed).copy)
    }

    @Test
    fun `removing a shipped Catalogue offers it back, and one tap adds it back`() {
        val list = CatalogueListViewModel(owner()) { true }
        settle()
        list.toggleEdit()
        list.askToRemove(GUTENBERG.url)
        assertEquals(CatalogueListMode.Editing(GUTENBERG.url), list.mode.value)
        list.remove(GUTENBERG.url)
        settle()
        assertEquals(listOf(STANDARD_EBOOKS_NEW_RELEASES), list.catalogues.value!!.map { it.catalogue })
        assertEquals(listOf(GUTENBERG), stored().removedShipped())
        val add = AddCatalogueViewModel(owner())
        settle()
        assertEquals(listOf(GUTENBERG), add.removedShipped.value)
        add.addBack(GUTENBERG)
        settle()
        assertEquals(emptyList(), add.removedShipped.value)
        assertEquals(SHIPPED_CATALOGUES, list.catalogues.value!!.map { it.catalogue })
    }

    @Test
    fun `removing every Catalogue leaves Edit, and the offline line follows what the phone says`() {
        var online = true
        val list = CatalogueListViewModel(owner()) { online }
        settle()
        list.checkConnection()
        assertEquals(false, list.offline.value)
        online = false
        list.checkConnection()
        assertEquals(true, list.offline.value)
        list.toggleEdit()
        SHIPPED_CATALOGUES.forEach { list.remove(it.url) }
        settle()
        assertEquals(emptyList(), list.catalogues.value)
        assertEquals(CatalogueListMode.Browsing, list.mode.value)
    }
}
