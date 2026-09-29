package com.yarosz.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How adding the typed address is going. */
sealed interface AddStatus {
    data object Idle : AddStatus

    data object Checking : AddStatus

    /** Adding the address failed; the copy says why, and whether trying it again can help. */
    data class Failed(val copy: FailureCopy) : AddStatus

    data object Added : AddStatus
}

/**
 * "Add a Catalogue": the typed address and how adding it is going. The feed is fetched before
 * anything is saved, and only a page that parses as a Catalogue is added, named by its title. The
 * status is always the current address's: a new address ends a check still running for the old one,
 * so its answer can't land on the new address or add a Catalogue the reader typed over. [connected]
 * is whether the phone reports a connection, null when it can't say: a host that doesn't resolve is
 * a misspelling only when it does.
 */
class AddCatalogueViewModel(private val owner: ShelfOwner, private val connected: () -> Boolean? = { null }) : LightViewModel<Unit>() {
    val address = MutableStateFlow("")
    val status = MutableStateFlow<AddStatus>(AddStatus.Idle)
    val removedShipped: StateFlow<List<Catalogue>> =
        owner.snapshot.map { it?.data?.removedShipped().orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, owner.snapshot.value?.data?.removedShipped().orEmpty())

    private var checking: Job? = null

    /**
     * Sets the address typed in the editor and adds it at once: the editor's button is "Add". A
     * changed or cleared address starts over from Idle, ending a check of the old one.
     */
    fun typed(text: String) {
        val typed = text.trim()
        if (typed != address.value) {
            checking?.cancel()
            address.value = typed
            status.value = AddStatus.Idle
        }
        if (typed.isNotEmpty()) add()
    }

    fun add() {
        val typed = address.value
        val data = owner.snapshot.value?.data ?: return
        if (status.value == AddStatus.Checking) return
        when (val plan = data.planAdd(typed)) {
            is AddPlan.Invalid -> status.value = AddStatus.Failed(feedFailureCopy(plan.reason, shipped = false))
            AddPlan.Duplicate -> status.value = AddStatus.Failed(FailureCopy(ADD_CATALOGUE_DUPLICATE, retry = false))
            is AddPlan.AddBack -> addBack(plan.catalogue)
            is AddPlan.Fetch -> {
                status.value = AddStatus.Checking
                checking = viewModelScope.launch {
                    status.value = when (val fetched = owner.fetchPage(plan.url)) {
                        is Fetched.Ok -> AddStatus.Added.also { owner.addCatalogue(catalogueFrom(fetched.value, plan.url)) }
                        is Fetched.Failed -> AddStatus.Failed(feedFailureCopy(fetched.reason, isShipped(plan.url), typedOnline = connected() == true))
                    }
                }
            }
        }
    }

    /**
     * "Add back Project Gutenberg": one tap, no confirmation. Typing a removed shipped Catalogue's
     * address does the same, and either returns to the list.
     */
    fun addBack(catalogue: Catalogue) {
        owner.addCatalogue(catalogue)
        status.value = AddStatus.Added
    }
}

/**
 * The address field, "Add", and, only when a shipped Catalogue has been removed, one "Add back …"
 * row for each. A Catalogue that is added returns to the list.
 */
class AddCatalogueScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, AddCatalogueViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddCatalogueViewModel>
        get() = AddCatalogueViewModel::class.java

    override fun createViewModel() = AddCatalogueViewModel(ShelfOwner.of(lightContext.filesDir)) { lightContext.connectivity.reported() }

    @Composable
    override fun Content() {
        val address by viewModel.address.collectAsState()
        val status by viewModel.status.collectAsState()
        val removed by viewModel.removedShipped.collectAsState()
        LaunchedEffect(status) {
            if (status == AddStatus.Added) goBack()
        }
        BackScreen(title = ADD_CATALOGUE_TITLE, onBack = { goBack() }) {
            AddressField(address) {
                navigateTo({ TextEntryScreen(it, ADD_CATALOGUE_LABEL, address, ADD_CATALOGUE_BUTTON) }, viewModel::typed)
            }
            when (val shown = status) {
                AddStatus.Checking -> SecondaryLine(PAGE_LOADING, rowPadding())
                is AddStatus.Failed -> FailureText(shown.copy.text, rowPadding())
                AddStatus.Idle, AddStatus.Added -> Unit
            }
            val failedForGood = (status as? AddStatus.Failed)?.copy?.retry == false
            if (address.isNotEmpty() && status != AddStatus.Checking && !failedForGood) {
                val retry = (status as? AddStatus.Failed)?.copy?.retry == true
                ListRow(if (retry) RETRY else ADD_CATALOGUE_BUTTON, null, onClick = viewModel::add)
            }
            removed.forEach { catalogue -> ListRow(addBackLabel(catalogue), null, onClick = { viewModel.addBack(catalogue) }) }
        }
    }
}

/**
 * "Catalogue address" over the typed address, or the placeholder "https://…" in secondary text, so an
 * empty field doesn't look filled in, then the SDK field's underline. The SDK's LightTextField draws a
 * placeholder at full strength, so this is its layout with the placeholder lightened.
 */
@Composable
private fun AddressField(address: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE_MARGIN)) {
        LightText(text = ADD_CATALOGUE_LABEL, variant = LightTextVariant.Detail, modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
        Column(Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(top = 0.25f.gridUnitsAsDp())) {
            LightText(
                text = address.ifBlank { ADD_CATALOGUE_PLACEHOLDER },
                variant = LightTextVariant.Copy,
                lighten = address.isBlank(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            Spacer(Modifier.fillMaxWidth(0.8f).height(3f.designVerticalPxToDp()).background(LightThemeTokens.colors.content))
        }
    }
}
