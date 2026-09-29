package com.yarosz.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File

/** Reads the Book in [file], opened from the Shelf; back returns there. See [ReaderViewModel] for [start]. */
class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val file: File,
    private val start: DevStart? = null,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel>
        get() = ReaderViewModel::class.java

    override fun createViewModel() = ReaderViewModel(file, ShelfOwner.of(lightContext.filesDir), start)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val book by viewModel.book.collectAsState()
        val status by viewModel.status.collectAsState()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        LaunchedEffect(measurer) { viewModel.warmUp(measurer) }

        LightTheme(colors = themeColors) {
            val topLineHeight = with(LocalDensity.current) { LightThemeTokens.typography.detail.lineHeight.value.designVerticalPxToSp().toDp() }
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                val opened = book
                Box(Modifier.fillMaxSize().padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)) {
                    when {
                        opened == null -> LightText(text = status, variant = LightTextVariant.Copy, lighten = true)
                        opened.spineItems.isEmpty() -> LightText(text = READING_NO_TEXT, variant = LightTextVariant.Copy, lighten = true)
                        else -> Reader(measurer, topLineHeight)
                    }
                }
                if (opened != null && opened.spineItems.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(contentsTargetHeight(TOP_BOTTOM_MARGIN, topLineHeight))
                            .semantics { contentDescription = CONTENTS_TITLE }
                            .lightClickable(role = Role.Button) { openContents() }
                    )
                }
            }
        }
    }

    /** Opens Contents over the Reader; a Chapter chosen there is jumped to, and back changes nothing. */
    private fun openContents() {
        val contents = viewModel.openContents() ?: return
        navigateTo({ ContentsScreen(it, contents) }) { chapter -> viewModel.jumpTo(chapter) }
    }

    /**
     * The top line, the Page (or the end page) and the footer, stacked (DESIGN.md "Reading"). The Page gets
     * whatever height the top line and footer leave, so a change to either re-packs the Pages at the Place.
     * The top line is [topLineHeight], one Detail line high at the system font scale, whatever the title: a
     * title in a fallback font's taller line can't re-pack the Pages at a Chapter change. Its tap target,
     * which opens Contents, lies over it and the top of the Page ([contentsTargetHeight]).
     */
    @Composable
    private fun Reader(measurer: TextMeasurer, topLineHeight: Dp) {
        val frame by viewModel.frame.collectAsState()
        val atEnd by viewModel.atEnd.collectAsState()
        val topLine by viewModel.topLine.collectAsState()
        val progressLine by viewModel.progressLine.collectAsState()
        val colors = LightThemeTokens.colors

        Column(Modifier.fillMaxSize()) {
            LightText(
                text = topLine,
                variant = LightTextVariant.Detail,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).height(topLineHeight),
                align = TextAlign.Center,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                detectTapGestures { tap -> if (tap.x < size.width / 3f) viewModel.previousPage() else viewModel.nextPage() }
            }) {
                val widthPx = constraints.maxWidth
                val pageHeightPx = constraints.maxHeight
                val density = LocalDensity.current
                val typesetter = remember(measurer, colors.contentSecondary, widthPx, pageHeightPx, density) {
                    Typesetter(measurer, colors.contentSecondary, widthPx, pageHeightPx, density)
                }
                LaunchedEffect(typesetter) { viewModel.bind(typesetter) }
                val shown = frame ?: return@BoxWithConstraints
                if (atEnd) EndPage() else Canvas(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = shown.pass.spineItem.text.substring(shown.page.start, shown.page.end) }
                ) {
                    drawPage(shown, colors.content)
                }
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = "A−",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxHeight().lightClickable { viewModel.changeFont(-1) }.padding(horizontal = 8.dp).wrapContentHeight(),
                )
                LightText(
                    text = progressLine.orEmpty(),
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.weight(1f),
                    align = TextAlign.Center,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = "A+",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.fillMaxHeight().lightClickable { viewModel.changeFont(+1) }.padding(horizontal = 8.dp).wrapContentHeight(),
                )
            }
        }
    }

    /** "The end." and "Back to Shelf", which leaves the Reader as system back does. Taps elsewhere turn as on a Page. */
    @Composable
    private fun EndPage() {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(text = END_PAGE_TEXT, variant = LightTextVariant.Copy, align = TextAlign.Center)
            LightText(
                text = END_PAGE_BACK_TO_SHELF,
                variant = LightTextVariant.Copy,
                modifier = Modifier.lightClickable { goBack() }.padding(8.dp),
                align = TextAlign.Center,
            )
        }
    }
}

/**
 * The height of the top line's tap target, measured from the screen's top edge: 48 dp, or the top [margin],
 * the top line ([topLine] high) and the 4 dp under it when that is taller (large text).
 */
fun contentsTargetHeight(margin: Dp, topLine: Dp): Dp = maxOf(48.dp, margin + topLine + 4.dp)

/** Draws a Page as its bands stacked in order: each its window's layout shifted up by the band's top and clipped to the band's height. */
private fun DrawScope.drawPage(shown: Shown<WindowLayout>, color: Color) {
    var y = 0f
    for (band in shown.page.bands) {
        val layout = checkNotNull(shown.pass.measured(band.window)) { "band on unmeasured window ${band.window}" }
        val height = band.bottom - band.top
        clipRect(top = y, bottom = y + height) {
            translate(top = y - band.top) { layout.draw(this, color) }
        }
        y += height
    }
}
