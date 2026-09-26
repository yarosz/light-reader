package com.yarosz.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@InitialScreen
class ReaderScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel>
        get() = ReaderViewModel::class.java

    override fun createViewModel() = ReaderViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val book by viewModel.book.collectAsState()
        val status by viewModel.status.collectAsState()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        LaunchedEffect(measurer) { viewModel.warmUp(measurer) }

        LightTheme(colors = themeColors) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)
            ) {
                val opened = book
                when {
                    opened == null -> LightText(text = status, variant = LightTextVariant.Copy, lighten = true)
                    opened.chapters.isEmpty() -> LightText(text = "This book has no text.", variant = LightTextVariant.Copy, lighten = true)
                    else -> Reader(opened, measurer)
                }
            }
        }
    }

    @Composable
    private fun Reader(book: Book, measurer: TextMeasurer) {
        val frame by viewModel.frame.collectAsState()
        val colors = LightThemeTokens.colors
        val footerHeight = 64.dp
        val footerPx = with(LocalDensity.current) { footerHeight.roundToPx() }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val pageHeightPx = constraints.maxHeight - footerPx
            val typesetter = remember(measurer, colors.contentSecondary, widthPx, pageHeightPx) {
                Typesetter(measurer, colors.contentSecondary, widthPx, pageHeightPx)
            }
            LaunchedEffect(typesetter) { viewModel.bind(typesetter) }
            val shown = frame ?: return@BoxWithConstraints

            Column(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics { contentDescription = shown.pass.chapter.text.substring(shown.page.start, shown.page.end) }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                if (tap.x < size.width / 3f) viewModel.previousPage() else viewModel.nextPage()
                            }
                        }
                ) {
                    drawPage(shown, colors.content)
                }
                Row(
                    Modifier.fillMaxWidth().height(footerHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "A−",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.lightClickable { viewModel.changeFont(-1) }.padding(8.dp),
                    )
                    LightText(
                        text = "${shown.pass.chapterIndex + 1}/${book.chapters.size}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightText(
                        text = "A+",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.lightClickable { viewModel.changeFont(+1) }.padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Draws a Page as its bands stacked in order: each its window's layout shifted up by the band's top and clipped to the band's height. */
private fun DrawScope.drawPage(shown: Shown<WindowLayout>, color: Color) {
    var y = 0f
    for (band in shown.page.bands) {
        val layout = checkNotNull(shown.pass.measured(band.window)) { "band on unmeasured window ${band.window}" }.layout
        val height = band.bottom - band.top
        clipRect(top = y, bottom = y + height) {
            translate(top = y - band.top) { drawText(layout, color = color) }
        }
        y += height
    }
}
