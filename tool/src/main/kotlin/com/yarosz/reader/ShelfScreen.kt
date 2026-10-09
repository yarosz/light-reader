package com.yarosz.reader

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File

/** Logcat tag; `scripts/ci.sh` waits for the "shelf rows=" line to know the Shelf rendered. */
private const val TAG = "Reader"

/**
 * The Shelf: the Books on this phone, text only, in the order of [shelfRows]. A tap opens a Book at
 * its Place in the Reader. "Add" and "Add a Book" open the list of Catalogues; "Reader" in the top bar
 * opens About.
 */
@InitialScreen
class ShelfScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ShelfViewModel>(sealedActivity) {

    override val viewModelClass: Class<ShelfViewModel>
        get() = ShelfViewModel::class.java

    override fun createViewModel() = ShelfViewModel(ShelfOwner.of(lightContext.filesDir), lightContext.connectivity.reporter())

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val snapshot by viewModel.snapshot.collectAsState()
        val rows = snapshot?.rows
        val mode by viewModel.mode.collectAsState()
        val devStart by viewModel.devStart.collectAsState()

        // A dev-start session opens once the Shelf has drawn: LightActivity's splash cancels every draw, and Compose
        // lays out new content in its draw, so an open started under the splash would wait for it in its timing.
        var drawn by remember { mutableStateOf(false) }
        LaunchedEffect(devStart, drawn) {
            if (!drawn) return@LaunchedEffect
            val start = devStart ?: return@LaunchedEffect
            viewModel.devStart.value = null
            open(File(lightContext.filesDir, DEV_BOOK_FILE), start)
        }
        LaunchedEffect(rows?.size) {
            rows?.let { Log.i(TAG, "shelf rows=${it.size}") }
        }

        LightTheme(colors = themeColors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .then(if (devStart != null && !drawn) Modifier.drawBehind { drawn = true } else Modifier)
            ) {
                val shown = rows
                LightTopBar(
                    leftButton = if (shown.isNullOrEmpty()) null else LightBarButton.Text(
                        text = if (mode is ShelfMode.Editing) SHELF_DONE else SHELF_EDIT,
                        onClick = viewModel::toggleEdit,
                    ),
                    center = LightTopBarCenter.Text(SHELF_TITLE, onClick = ::openAbout),
                    rightButton = LightBarButton.Text(SHELF_ADD, onClick = ::openCatalogues),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                when {
                    shown == null -> Unit
                    shown.isEmpty() -> {
                        Notice(snapshot?.notices.orEmpty())
                        Empty()
                    }
                    else -> LightScrollView(Modifier.weight(1f).fillMaxWidth()) {
                        Notice(snapshot?.notices.orEmpty())
                        shown.forEach { row -> ShelfRowView(row, mode) }
                    }
                }
            }
        }
    }

    private fun open(file: File, start: DevStart? = null) {
        navigateTo({ ReaderScreen(it, file, start) })
    }

    /** Opens the Catalogues; a Book opened from one comes back here, and the Reader opens over the Shelf. */
    private fun openCatalogues() {
        viewModel.endEdit()
        navigateTo({ CatalogueListScreen(it) }) { opened -> open(File(lightContext.filesDir, opened.file)) }
    }

    private fun openAbout() {
        navigateTo({ AboutScreen(it) })
    }

    private fun tap(tap: RowTap) {
        when (tap) {
            is RowTap.Open -> open(File(lightContext.filesDir, tap.file))
            is RowTap.Download -> viewModel.download(tap)
            RowTap.None -> Unit
        }
    }

    /**
     * The files Reader didn't add, a line each in secondary text ([noticeLines]); a tap clears them.
     * Tells the view model what it drew, which marks those notices shown ([ShelfViewModel.rendered]).
     */
    @Composable
    private fun Notice(notices: List<ImportNotice>) {
        LaunchedEffect(notices) { viewModel.rendered(notices) }
        if (notices.isEmpty()) return
        Column(Modifier.lightClickable(onClick = viewModel::clearNotices).then(rowPadding())) {
            noticeLines(notices).forEach { SecondaryLine(it, maxLines = 2) }
        }
    }

    @Composable
    private fun Empty() {
        Column(
            Modifier.fillMaxSize().padding(horizontal = SIDE_MARGIN),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(text = SHELF_EMPTY, variant = LightTextVariant.Copy, align = TextAlign.Center, lighten = true)
            LightText(
                text = SHELF_ADD_A_BOOK,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()).lightClickable(onClick = ::openCatalogues),
            )
        }
    }

    @Composable
    private fun ShelfRowView(row: ShelfRow, mode: ShelfMode) {
        if (mode is ShelfMode.Editing && mode.confirming == row.key) {
            ConfirmRemoval(row.title, SHELF_CONFIRM_REMOVE, SHELF_REMOVE, SHELF_CANCEL, onRemove = { viewModel.remove(row.key) }, onCancel = viewModel::cancelRemove)
            return
        }
        val tappable = mode == ShelfMode.Browsing && row.tap != RowTap.None
        ListRow(
            row.title,
            row.detail,
            onClick = if (tappable) ({ tap(row.tap) }) else null,
            trailing = if (mode is ShelfMode.Editing) ({
                TextAction(SHELF_REMOVE, { viewModel.askToRemove(row.key) }, Modifier.padding(start = 1f.gridUnitsAsDp()), lighten = true)
            }) else null,
        )
    }
}
