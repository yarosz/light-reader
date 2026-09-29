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
 * model holds only whether the screen is editing. [connected] is whether the phone reports a
 * connection, for a download that fails (see [ShelfOwner.download]). An emptied Shelf leaves Edit,
 * since Edit is hidden then, and a confirmation whose row is gone (a download that arrived under its
 * Book's identifier, or failed) is cleared.
 */
class ShelfViewModel(internal val owner: ShelfOwner, private val connected: () -> Boolean? = { null }) : LightViewModel<Unit>() {
    /** What the Shelf knows; the screen shows its [ShelfSnapshot.rows]. Null until the reading data is loaded. */
    val snapshot: StateFlow<ShelfSnapshot?> = owner.snapshot
    val mode = MutableStateFlow<ShelfMode>(ShelfMode.Browsing)
    val devStart: MutableStateFlow<DevStart?> = owner.devStart

    init {
        viewModelScope.launch {
            snapshot.collect { latest ->
                val editing = mode.value as? ShelfMode.Editing ?: return@collect
                val shown = latest?.rows
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
        owner.download(source, title, author, replacing, connected)

    /** A row's "tap to retry" or "tap to download again". */
    fun download(tap: RowTap.Download) = download(tap.source, tap.title, tap.author, tap.replacing)

    /** Edit is hidden while the Shelf is empty, so it can't start then. */
    fun toggleEdit() {
        mode.value = when {
            mode.value is ShelfMode.Editing -> ShelfMode.Browsing
            snapshot.value?.rows.isNullOrEmpty() -> return
            else -> ShelfMode.Editing()
        }
    }

    /** Leaving the Shelf for the Catalogues ends Edit, so the Shelf is browsing when the reader comes back. */
    fun endEdit() {
        mode.value = ShelfMode.Browsing
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
