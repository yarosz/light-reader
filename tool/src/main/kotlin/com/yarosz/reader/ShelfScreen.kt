package com.yarosz.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The Shelf: the Books on this phone, text only, in the order of [shelfRows]. A tap opens a Book at
 * its Place in the Reader. "Add" and "Add a Book" will open the list of Catalogues, which the next
 * change builds; until then they are labels only.
 */
@InitialScreen
class ShelfScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ShelfViewModel>(sealedActivity) {

    override val viewModelClass: Class<ShelfViewModel>
        get() = ShelfViewModel::class.java

    override fun createViewModel() = ShelfViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()
        val mode by viewModel.mode.collectAsState()
        val devStart by viewModel.devStart.collectAsState()

        LaunchedEffect(devStart) {
            val start = devStart ?: return@LaunchedEffect
            viewModel.devStart.value = null
            open(File(lightContext.filesDir, DEV_BOOK_FILE), viewModel.devSaver(), start)
        }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                val shown = rows
                LightTopBar(
                    leftButton = if (shown.isNullOrEmpty()) null else LightBarButton.Text(
                        text = if (mode is ShelfMode.Editing) SHELF_DONE else SHELF_EDIT,
                        onClick = viewModel::toggleEdit,
                    ),
                    center = LightTopBarCenter.Text(SHELF_TITLE),
                    rightButton = LightBarButton.Text(SHELF_ADD, onClick = null),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                when {
                    shown == null -> Unit
                    shown.isEmpty() -> Empty()
                    else -> LightScrollView(Modifier.weight(1f).fillMaxWidth()) {
                        shown.forEach { row -> ShelfRowView(row, mode) }
                    }
                }
            }
        }
    }

    private fun open(file: File, saver: ReadingSaver, start: DevStart? = null) {
        navigateTo({ ReaderScreen(it, file, saver, start) })
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
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }

    @Composable
    private fun ShelfRowView(row: ShelfRow, mode: ShelfMode) {
        val rowPadding = Modifier.fillMaxWidth().padding(horizontal = SIDE_MARGIN, vertical = 0.75f.gridUnitsAsDp())
        if (mode is ShelfMode.Editing && mode.confirming == row.key) {
            Column(rowPadding) {
                LightText(text = SHELF_CONFIRM_REMOVE, variant = LightTextVariant.Detail)
                Row(Modifier.padding(top = 0.5f.gridUnitsAsDp())) {
                    LightText(
                        text = SHELF_REMOVE,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.lightClickable { viewModel.remove(row.key) }.padding(end = 2f.gridUnitsAsDp()),
                    )
                    LightText(text = SHELF_CANCEL, variant = LightTextVariant.Copy, modifier = Modifier.lightClickable(onClick = viewModel::cancelRemove))
                }
            }
            return
        }
        val tappable = mode == ShelfMode.Browsing && row.tap != RowTap.None
        Row(
            (if (tappable) Modifier.lightClickable { viewModel.tap(row)?.let { open(it, viewModel.saver) } } else Modifier).then(rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LightText(text = row.title, variant = LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis)
                row.detail?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (mode is ShelfMode.Editing) {
                Box(Modifier.lightClickable { viewModel.askToRemove(row.key) }.padding(start = 1f.gridUnitsAsDp())) {
                    LightText(text = SHELF_REMOVE, variant = LightTextVariant.Copy)
                }
            }
        }
    }
}
