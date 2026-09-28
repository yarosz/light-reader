package com.yarosz.reader

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Shelf screen's view onto [owner], which holds the reading data and the downloads; this view
 * model holds only whether the screen is editing. An emptied Shelf leaves Edit, since Edit is
 * hidden then, and a confirmation whose row is gone (a download that arrived under its Book's
 * identifier, or failed) is cleared.
 */
class ShelfViewModel(internal val owner: ShelfOwner) : LightViewModel<Unit>() {
    val rows: StateFlow<List<ShelfRow>?> = owner.rows
    val mode = MutableStateFlow<ShelfMode>(ShelfMode.Browsing)
    val devStart: MutableStateFlow<DevStart?> = owner.devStart

    init {
        viewModelScope.launch {
            owner.rows.collect { shown ->
                val editing = mode.value as? ShelfMode.Editing ?: return@collect
                when {
                    shown.isNullOrEmpty() -> mode.value = ShelfMode.Browsing
                    editing.confirming != null && shown.none { it.key == editing.confirming } -> mode.value = ShelfMode.Editing()
                }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = owner.refresh()

    internal fun refresh() = owner.refresh()

    /** See [ShelfOwner.download]. */
    fun download(source: HttpsUrl, title: String, author: String?, replacing: String? = null): Deferred<DownloadResult> =
        owner.download(source, title, author, replacing)

    /** A row's "tap to retry" or "tap to download again". */
    fun download(tap: RowTap.Download) = download(tap.source, tap.title, tap.author, tap.replacing)

    /** Edit is hidden while the Shelf is empty, so it can't start then. */
    fun toggleEdit() {
        mode.value = when {
            mode.value is ShelfMode.Editing -> ShelfMode.Browsing
            rows.value.isNullOrEmpty() -> return
            else -> ShelfMode.Editing()
        }
    }

    /** The row's trailing "Remove" in Edit: the row turns into the confirmation. */
    fun askToRemove(key: RowKey) {
        if (mode.value is ShelfMode.Editing) mode.value = ShelfMode.Editing(confirming = key)
    }

    fun cancelRemove() {
        if (mode.value is ShelfMode.Editing) mode.value = ShelfMode.Editing()
    }

    /** Confirms the removal: see [ShelfOwner.remove]. */
    fun remove(key: RowKey) {
        owner.remove(key)
        cancelRemove()
    }

    override fun onAppPause() = owner.saver.flush()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = owner.saver.flush()

    override fun onCleared() {
        owner.saver.flush()
        super.onCleared()
    }
}
