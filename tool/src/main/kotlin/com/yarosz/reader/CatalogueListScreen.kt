package com.yarosz.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.NetworkStatus
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The list of Catalogues, opened by the Shelf's "Add" and "Add a Book": its view onto [owner], plus
 * whether the screen is editing and whether the phone is offline. [networkStatus] is the SDK's
 * report of the phone's network, followed while the list is open, so "You're offline. Your Shelf
 * still works." comes and goes as the connection does. Offline means the phone reports no internet
 * connection; when it can't report (the flow fails), the line isn't shown, so it never claims what
 * the phone didn't say. The report is followed only while the screen collects [offline], and for
 * [UNSUBSCRIBED_GRACE_MS] after: the SDK's flow holds a system network callback, and LightOS never
 * clears an old screen's view model when it relaunches the activity (ADR 0008), so following it for
 * the view model's life would leave one callback per relaunch.
 */
class CatalogueListViewModel(private val owner: ShelfOwner, networkStatus: Flow<NetworkStatus>) : LightViewModel<OpenFromShelf>() {
    val catalogues: StateFlow<List<ListedCatalogue>?> =
        owner.snapshot.map { it?.data?.catalogueList() }.stateIn(viewModelScope, SharingStarted.Eagerly, owner.snapshot.value?.data?.catalogueList())
    val mode = MutableStateFlow<CatalogueListMode>(CatalogueListMode.Browsing)
    val offline: StateFlow<Boolean> =
        networkStatus.map { !it.isConnected }.catch { emit(false) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UNSUBSCRIBED_GRACE_MS), false)

    init {
        viewModelScope.launch {
            catalogues.collect { shown ->
                val editing = mode.value as? CatalogueListMode.Editing ?: return@collect
                when {
                    shown.isNullOrEmpty() -> mode.value = CatalogueListMode.Browsing
                    editing.confirming != null && shown.none { it.catalogue.url == editing.confirming } -> mode.value = CatalogueListMode.Editing()
                }
            }
        }
    }

    /** Rechecks the Shelf's files, which the Catalogue pages match against. */
    override fun onScreenShow(screen: SimpleLightScreen<OpenFromShelf>) = owner.refresh()

    /** Edit is hidden while the list is empty, so it can't start then. */
    fun toggleEdit() {
        mode.value = when {
            mode.value is CatalogueListMode.Editing -> CatalogueListMode.Browsing
            catalogues.value.isNullOrEmpty() -> return
            else -> CatalogueListMode.Editing()
        }
    }

    fun askToRemove(url: HttpsUrl) {
        if (mode.value is CatalogueListMode.Editing) mode.value = CatalogueListMode.Editing(confirming = url)
    }

    fun cancelRemove() {
        if (mode.value is CatalogueListMode.Editing) mode.value = CatalogueListMode.Editing()
    }

    fun remove(url: HttpsUrl) {
        owner.removeCatalogue(url)
        cancelRemove()
    }
}

/**
 * Whether the phone reports an internet connection now, or null when it can't say (the permission
 * missing, say), so a caller never claims more than the phone did.
 */
fun LightConnectivity.reported(): Boolean? = try {
    currentStatus.isConnected
} catch (e: RuntimeException) {
    null
}

/** How long [CatalogueListViewModel.offline] keeps following the network after its screen stops collecting, which rides out a recomposition. */
const val UNSUBSCRIBED_GRACE_MS = 5_000L

/**
 * "Add a Book": each Catalogue by name (one the reader added has its host below), then "Add a
 * Catalogue". Edit removes a Catalogue, inline, keeping its name above the question.
 */
class CatalogueListScreen(sealedActivity: SealedLightActivity) : LightScreen<OpenFromShelf, CatalogueListViewModel>(sealedActivity) {

    override val viewModelClass: Class<CatalogueListViewModel>
        get() = CatalogueListViewModel::class.java

    override fun createViewModel() = CatalogueListViewModel(ShelfOwner.of(lightContext.filesDir), lightContext.connectivity.observeNetworkStatus())

    @Composable
    override fun Content() {
        val catalogues by viewModel.catalogues.collectAsState()
        val mode by viewModel.mode.collectAsState()
        val offline by viewModel.offline.collectAsState()
        val shown = catalogues
        BackScreen(
            title = CATALOGUES_TITLE,
            onBack = { goBack() },
            right = if (shown.isNullOrEmpty()) null else LightBarButton.Text(
                text = if (mode is CatalogueListMode.Editing) CATALOGUES_DONE else CATALOGUES_EDIT,
                onClick = viewModel::toggleEdit,
            ),
        ) {
            if (offline) SecondaryLine(CATALOGUES_OFFLINE, rowPadding())
            shown?.forEach { listed -> CatalogueRow(listed, mode) }
            if (shown != null && mode == CatalogueListMode.Browsing) {
                ListRow(CATALOGUES_ADD, null, onClick = { navigateTo({ AddCatalogueScreen(it) }) })
            }
        }
    }

    @Composable
    private fun CatalogueRow(listed: ListedCatalogue, mode: CatalogueListMode) {
        val catalogue = listed.catalogue
        if (mode is CatalogueListMode.Editing && mode.confirming == catalogue.url) {
            ConfirmRemoval(
                catalogue.name,
                CATALOGUES_CONFIRM_REMOVE,
                CATALOGUES_REMOVE,
                CATALOGUES_CANCEL,
                onRemove = { viewModel.remove(catalogue.url) },
                onCancel = viewModel::cancelRemove,
            )
            return
        }
        ListRow(
            catalogue.name,
            catalogue.url.host.takeUnless { listed.shipped },
            onClick = if (mode == CatalogueListMode.Browsing) ({ navigateTo({ CataloguePageScreen(it, catalogue, PageSource.Root) }, ::goBack) }) else null,
            trailing = if (mode is CatalogueListMode.Editing) ({
                TextAction(CATALOGUES_REMOVE, { viewModel.askToRemove(catalogue.url) }, Modifier.padding(start = 1f.gridUnitsAsDp()), lighten = true)
            }) else null,
        )
    }
}
