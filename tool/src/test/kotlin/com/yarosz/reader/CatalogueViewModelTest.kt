package com.yarosz.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.thelightphone.sdk.NetworkStatus
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    private val connected = NetworkStatus(isConnected = true, isWifi = true, isMetered = false)

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

    private val transport = FakeTransport(answers)

    private fun owner() = ShelfOwner.of(dir) { ShelfOwner(dir, io, transport) { clock } }.also {
        it.refresh()
        settle()
    }

    private fun page(source: PageSource, catalogue: Catalogue = GUTENBERG) = CataloguePageViewModel(owner(), catalogue, source, PHONE_CANT_SAY).also { settle() }

    private fun openBook(): CataloguePageViewModel {
        val list = page(PageSource.Feed(url("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"), popularEntry()))
        val entry = (list.state.value as PageState.Listing).entries.first()
        return page(entryTarget(entry)!!)
    }

    private fun popularEntry() = CatalogueEntry("Popular", emptyList(), null, null, null, null, emptyList(), emptyList())

    private fun stored() = ReadingStore(dir).load()

    /** [make]'s view model held by [store], so clearing the store clears it as leaving its screen does. */
    private inline fun <reified T : ViewModel> held(store: ViewModelStore, crossinline make: () -> T): T =
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <V : ViewModel> create(modelClass: Class<V>): V = modelClass.cast(make())!!
            },
        )[T::class.java]

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
        assertEquals(emptyList(), owner().snapshot.value!!.rows)
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
        assertEquals(emptyList(), owner().snapshot.value!!.rows)

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
    fun `search terms with reserved and non-ASCII characters reach the server encoded once, as one parameter`() {
        val results = "https://m.gutenberg.org/ebooks/search.opds/?query=Tom%20%26%20Jerry%20%231%20%2B%20caf%C3%A9"
        answers[results] = Answer(body = fixture("gutenberg-popular.xml"))
        val search = CatalogueSearch.Description(url("https://www.gutenberg.org/catalog/osd-books.xml"))
        val page = page(PageSource.Search(search, "Tom & Jerry #1 + café"))
        assertIs<PageState.Listing>(page.state.value)
        assertEquals(results, transport.asked.last().value)
        assertEquals("Tom & Jerry #1 + café", pageTitle(GUTENBERG, page.source))
    }

    @Test
    fun `a page left while it loads drops the late answer and keeps its state`() {
        val store = ViewModelStore()
        val root = held(store) { CataloguePageViewModel(owner(), GUTENBERG, PageSource.Root, PHONE_CANT_SAY) }
        assertEquals(PageState.Loading, root.state.value)
        store.clear()
        settle()
        assertEquals(PageState.Loading, root.state.value)
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
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        add.typed("books.example.org/opds")
        settle()
        assertEquals(AddStatus.Added, add.status.value)
        assertEquals(Catalogue("Home books", home), stored().catalogueList().last().catalogue)
    }

    @Test
    fun `an address that isn't a Catalogue, or one already listed, isn't added`() {
        answers[home.value] = Answer(body = "<html><body>hello</body></html>".toByteArray())
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        add.typed("https://books.example.org/opds")
        settle()
        assertEquals(AddStatus.Failed(FailureCopy(COPY_UNREADABLE, retry = false)), add.status.value)
        add.typed("www.gutenberg.org/ebooks.opds/")
        assertEquals(AddStatus.Failed(FailureCopy(ADD_CATALOGUE_DUPLICATE, retry = false)), add.status.value)
        assertEquals(SHIPPED_CATALOGUES, stored().catalogueList().map { it.catalogue })
    }

    @Test
    fun `an http address that has no https says so`() {
        answers["https://plain.example.org/opds"] = Answer(connectFailure = java.net.ConnectException("refused"))
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        add.typed("http://plain.example.org/opds")
        settle()
        assertEquals(FailureCopy(COPY_NO_HTTPS, retry = false), (add.status.value as AddStatus.Failed).copy)
    }

    @Test
    fun `an http address that times out has no https only while the phone reports it is online`() {
        answers["https://slow.example.org/opds"] = Answer(connectFailure = ConnectTimeoutException(java.net.SocketTimeoutException("connect timed out")))
        val online = AddCatalogueViewModel(owner()) { true }
        online.typed("http://slow.example.org/opds")
        settle()
        assertEquals(FailureCopy(COPY_NO_HTTPS, retry = false), (online.status.value as AddStatus.Failed).copy)
        val unknown = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        unknown.typed("http://slow.example.org/opds")
        settle()
        assertEquals(FailureCopy(COPY_UNREACHABLE, retry = true), (unknown.status.value as AddStatus.Failed).copy)
    }

    @Test
    fun `removing a shipped Catalogue offers it back, and one tap adds it back`() {
        val list = CatalogueListViewModel(owner(), flowOf(connected))
        settle()
        list.toggleEdit()
        list.askToRemove(GUTENBERG.url)
        assertEquals(CatalogueListMode.Editing(GUTENBERG.url), list.mode.value)
        list.remove(GUTENBERG.url)
        settle()
        assertEquals(listOf(STANDARD_EBOOKS_NEW_RELEASES), list.catalogues.value!!.map { it.catalogue })
        assertEquals(listOf(GUTENBERG), stored().removedShipped())
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        settle()
        assertEquals(listOf(GUTENBERG), add.removedShipped.value)
        add.addBack(GUTENBERG)
        settle()
        assertEquals(emptyList(), add.removedShipped.value)
        assertEquals(SHIPPED_CATALOGUES, list.catalogues.value!!.map { it.catalogue })
    }

    @Test
    fun `a new address while one is being checked ends that check, so its answer can't land or add anything`() {
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        add.typed("books.example.org/opds")
        assertEquals(AddStatus.Checking, add.status.value)
        add.typed("ftp://books.example.org/opds")
        settle()
        assertEquals(AddStatus.Failed(FailureCopy(COPY_NO_HTTPS, retry = false)), add.status.value)
        assertEquals(SHIPPED_CATALOGUES, stored().catalogueList().map { it.catalogue })
    }

    @Test
    fun `typing over an address being checked checks the new one, and clearing the address resets to Idle`() {
        answers.remove(home.value)
        answers["https://club.example.net/opds"] = Answer(body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Book club</title></feed>""".toByteArray())
        val add = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        add.typed("books.example.org/opds")
        add.typed("club.example.net/opds")
        settle()
        assertEquals(AddStatus.Added, add.status.value)
        assertEquals(listOf("Book club"), stored().catalogueList().drop(SHIPPED_CATALOGUES.size).map { it.catalogue.name })

        val again = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        again.typed("books.example.org/opds")
        settle()
        assertIs<AddStatus.Failed>(again.status.value)
        again.typed("")
        assertEquals(AddStatus.Idle, again.status.value)
        assertEquals("", again.address.value)
    }

    @Test
    fun `leaving Add a Catalogue while it checks adds nothing`() {
        val store = ViewModelStore()
        val add = held(store) { AddCatalogueViewModel(owner(), PHONE_CANT_SAY) }
        add.typed("books.example.org/opds")
        store.clear()
        settle()
        assertEquals(SHIPPED_CATALOGUES, stored().catalogueList().map { it.catalogue })
    }

    @Test
    fun `Add back by its row and by typing its address do the same, and both return to the list`() {
        val removed = { owner().removeCatalogue(GUTENBERG.url).also { settle() } }
        removed()
        val byRow = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        byRow.addBack(GUTENBERG)
        settle()
        val afterRow = stored().catalogues
        assertEquals(AddStatus.Added, byRow.status.value)

        clock += 10
        removed()
        val byAddress = AddCatalogueViewModel(owner(), PHONE_CANT_SAY)
        byAddress.typed("www.gutenberg.org/ebooks.opds")
        settle()
        assertEquals(AddStatus.Added, byAddress.status.value)
        assertEquals(afterRow.mapValues { it.value.copy(updatedAt = 0) }, stored().catalogues.mapValues { it.value.copy(updatedAt = 0) })
        assertEquals(SHIPPED_CATALOGUES, stored().catalogueList().map { it.catalogue })
    }

    @Test
    fun `removing every Catalogue leaves Edit`() {
        val list = CatalogueListViewModel(owner(), flowOf(connected))
        settle()
        list.toggleEdit()
        SHIPPED_CATALOGUES.forEach { list.remove(it.url) }
        settle()
        assertEquals(emptyList(), list.catalogues.value)
        assertEquals(CatalogueListMode.Browsing, list.mode.value)
    }

    /** Collects [flow] on the main dispatcher as a screen does; cancel the job to leave. */
    private fun shown(flow: Flow<*>): Job = CoroutineScope(main).launch { flow.collect {} }

    @Test
    fun `the offline line follows the phone's reports while the list is open`() {
        val status = MutableStateFlow(connected)
        val list = CatalogueListViewModel(owner(), status)
        val screen = shown(list.offline)
        settle()
        assertEquals(false, list.offline.value)
        status.value = NetworkStatus(isConnected = false, isWifi = false, isMetered = false)
        settle()
        assertEquals(true, list.offline.value)
        status.value = connected
        settle()
        assertEquals(false, list.offline.value)
        screen.cancel()
    }

    @Test
    fun `the phone's network is followed only while the list is shown, so a left list holds no network callback`() {
        var following = 0
        val status = callbackFlow {
            following++
            trySend(connected)
            awaitClose { following-- }
        }
        val list = CatalogueListViewModel(owner(), status)
        settle()
        assertEquals(0, following)
        val screen = shown(list.offline)
        settle()
        assertEquals(1, following)
        screen.cancel()
        main.scheduler.advanceTimeBy(UNSUBSCRIBED_GRACE_MS - 1)
        main.scheduler.runCurrent()
        assertEquals(1, following, "a brief gap keeps following")
        settle()
        assertEquals(0, following)
        val again = shown(list.offline)
        settle()
        assertEquals(1, following)
        again.cancel()
    }

    @Test
    fun `when the phone can't report its network, the offline line isn't shown`() {
        val list = CatalogueListViewModel(owner(), flow { throw SecurityException("no ACCESS_NETWORK_STATE") })
        val screen = shown(list.offline)
        settle()
        assertEquals(false, list.offline.value)
        screen.cancel()
    }
}
