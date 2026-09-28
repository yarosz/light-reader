package com.yarosz.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One page of [catalogue]: a list of entries with search and "More", or one Book's detail page.
 * The page is fetched once; a failure shows its copy with Retry where retrying can help. A download
 * runs in [owner], so it goes on after the reader leaves the page, and the detail page follows it:
 * "downloading…", then "Read" once it lands, or the failure's copy.
 */
class CataloguePageViewModel(
    private val owner: ShelfOwner,
    val catalogue: Catalogue,
    val source: PageSource,
    private val phoneOnline: PhoneOnline,
) : LightViewModel<Unit>() {
    val state = MutableStateFlow<PageState>(if (source is PageSource.Entry) PageState.Book(listOf(source.entry)) else PageState.Loading)
    val shipped = isShipped(catalogue.url)

    /** How this page's last download failed, for a failure the Shelf doesn't keep. */
    private val failure = MutableStateFlow<DownloadFailure?>(null)

    val detail: StateFlow<BookDetail?> = combine(owner.snapshot, state, failure) { snapshot, state, failure ->
        if (snapshot != null && state is PageState.Book) bookDetail(snapshot, state.entries, failure, shipped) else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var loading: Job? = null

    init {
        load()
        owner.refresh()
    }

    /** Fetches the page, or fetches it again after a failure. */
    fun load() {
        if (source is PageSource.Entry || loading?.isActive == true) return
        state.value = PageState.Loading
        loading = viewModelScope.launch {
            state.value = when (val fetched = fetch()) {
                is Fetched.Ok -> pageState(fetched.value, source)
                is Fetched.Failed -> PageState.Failed(fetched.reason)
            }
        }
    }

    private suspend fun fetch(): Fetched<CataloguePage> = when (source) {
        PageSource.Root -> owner.fetchPage(catalogue.url, phoneOnline)
        is PageSource.Feed -> owner.fetchPage(source.url, phoneOnline)
        is PageSource.Search -> when (val template = searchTemplate(source.search)) {
            is Fetched.Ok -> owner.fetchPage(template.value.url(source.terms), phoneOnline)
            is Fetched.Failed -> template
        }
        is PageSource.Entry -> error("an entry's page has nothing to fetch")
    }

    private suspend fun searchTemplate(search: CatalogueSearch): Fetched<SearchTemplate> = when (search) {
        is CatalogueSearch.Description -> owner.fetchSearch(search.url, phoneOnline)
        is CatalogueSearch.Ready -> Fetched.Ok(search.template)
    }

    /** "More": fetches the next page and appends it, or again after a failure. */
    fun more() {
        val listing = state.value as? PageState.Listing ?: return
        val next = listing.next ?: return
        if (listing.more == More.Loading) return
        state.value = listing.copy(more = More.Loading)
        viewModelScope.launch {
            val current = state.value as? PageState.Listing ?: return@launch
            state.value = when (val fetched = owner.fetchPage(next, phoneOnline)) {
                is Fetched.Ok -> appendPage(current, fetched.value)
                is Fetched.Failed -> current.copy(more = More.Failed(fetched.reason))
            }
        }
    }

    /** "Add to Shelf", "Download again" or "Retry". */
    fun download(action: DetailAction.Download) {
        val entries = (state.value as? PageState.Book)?.entries ?: return
        failure.value = null
        val result = owner.download(action.link.url, entries.first().title, detailAuthor(entries), phoneOnline, action.replacing)
        viewModelScope.launch {
            (result.await() as? DownloadResult.Failed)?.let { failure.value = it.reason }
        }
    }
}

class CataloguePageScreen(
    sealedActivity: SealedLightActivity,
    private val catalogue: Catalogue,
    private val source: PageSource,
) : LightScreen<Unit, CataloguePageViewModel>(sealedActivity) {

    override val viewModelClass: Class<CataloguePageViewModel>
        get() = CataloguePageViewModel::class.java

    override fun createViewModel() = CataloguePageViewModel(ShelfOwner.of(lightContext.filesDir), catalogue, source, lightContext::phoneOnline)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val detail by viewModel.detail.collectAsState()
        BackScreen(title = pageTitle(catalogue, source), onBack = { goBack() }) {
            when (val shown = state) {
                PageState.Loading -> SecondaryLine(PAGE_LOADING, rowPadding())
                is PageState.Failed -> FailureLine(feedFailureCopy(shown.reason, viewModel.shipped), viewModel::load)
                is PageState.Listing -> Listing(shown)
                is PageState.Book -> detail?.let { Detail(shown.entries, it) }
            }
        }
    }

    @Composable
    private fun Listing(listing: PageState.Listing) {
        listing.search?.let { search ->
            SearchField(searchPlaceholder(catalogue)) {
                navigateTo({ TextEntryScreen(it, SEARCH, "", SEARCH) }) { terms ->
                    if (terms.isNotBlank()) navigateTo({ CataloguePageScreen(it, catalogue, PageSource.Search(search, terms.trim())) })
                }
            }
        }
        listing.entries.forEach { entry ->
            val target = entryTarget(entry)
            ListRow(entry.title, entry.byline, onClick = target?.let { { navigateTo({ CataloguePageScreen(it, catalogue, target) }) } })
        }
        if (listing.next != null) {
            when (val more = listing.more) {
                More.Idle -> ListRow(PAGE_MORE, null, onClick = viewModel::more)
                More.Loading -> SecondaryLine(PAGE_LOADING, rowPadding())
                is More.Failed -> FailureLine(feedFailureCopy(more.reason, viewModel.shipped), viewModel::more)
            }
        }
    }

    @Composable
    private fun Detail(entries: List<CatalogueEntry>, detail: BookDetail) {
        Column(rowPadding()) {
            BookTitle(entries.first().title, maxLines = 4)
            detailAuthor(entries)?.let { SecondaryLine(it, maxLines = 2) }
        }
        detail.problem?.let { LightText(text = it.text, variant = LightTextVariant.Copy, modifier = rowPadding()) }
        when (val action = detail.action) {
            is DetailAction.Read -> Action(DETAIL_READ, DETAIL_ON_SHELF) { navigateTo({ ReaderScreen(it, File(lightContext.filesDir, action.file)) }) }
            is DetailAction.Download -> Action(action.label, action.link.length?.let(::formatSize)) { viewModel.download(action) }
            DetailAction.Downloading -> SecondaryLine(DETAIL_DOWNLOADING, rowPadding())
            DetailAction.None -> Unit
        }
        detailSummary(entries)?.let { LightText(text = it, variant = LightTextVariant.Detail, modifier = rowPadding()) }
    }

    /** The detail page's one button, with [note] (the size, or "On your Shelf") as secondary text beside it. */
    @Composable
    private fun Action(label: String, note: String?, onClick: () -> Unit) {
        Row(rowPadding(), verticalAlignment = Alignment.CenterVertically) {
            TextAction(label, onClick)
            note?.let { SecondaryLine(it, Modifier.padding(start = 1f.gridUnitsAsDp())) }
        }
    }
}
