package com.yarosz.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
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
 * so its answer can't land on the new address or add a Catalogue the reader typed over.
 */
class AddCatalogueViewModel(private val owner: ShelfOwner) : LightViewModel<Unit>() {
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
                        is Fetched.Failed -> AddStatus.Failed(feedFailureCopy(fetched.reason, isShipped(plan.url)))
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

    override fun createViewModel() = AddCatalogueViewModel(ShelfOwner.of(lightContext.filesDir))

    @Composable
    override fun Content() {
        val address by viewModel.address.collectAsState()
        val status by viewModel.status.collectAsState()
        val removed by viewModel.removedShipped.collectAsState()
        LaunchedEffect(status) {
            if (status == AddStatus.Added) goBack()
        }
        BackScreen(title = ADD_CATALOGUE_TITLE, onBack = { goBack() }) {
            LightTextField(
                label = ADD_CATALOGUE_LABEL,
                value = address,
                placeholder = ADD_CATALOGUE_PLACEHOLDER,
                onClick = {
                    navigateTo({ TextEntryScreen(it, ADD_CATALOGUE_LABEL, address, ADD_CATALOGUE_BUTTON) }, viewModel::typed)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE_MARGIN),
            )
            when (val shown = status) {
                AddStatus.Checking -> SecondaryLine(PAGE_LOADING, rowPadding())
                is AddStatus.Failed -> LightText(text = shown.copy.text, variant = LightTextVariant.Copy, modifier = rowPadding())
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
