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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
        val topLine by viewModel.topLine.collectAsState()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val source = MeasurerSource(LocalDensity.current, LocalFontFamilyResolver.current, LocalLayoutDirection.current)
        LaunchedEffect(measurer) { viewModel.warmUp(measurer) }

        LightTheme(colors = themeColors) {
            val topLineHeight = with(LocalDensity.current) { LightThemeTokens.typography.detail.lineHeight.value.designVerticalPxToSp().toDp() }
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                val opened = book
                Box(Modifier.fillMaxSize().padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)) {
                    when {
                        opened == null -> NoPage(status)
                        opened.spineItems.isEmpty() -> NoPage(READING_NO_TEXT)
                        else -> Reader(measurer, source, topLineHeight)
                    }
                }
                if (opened != null && opened.spineItems.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(contentsTargetHeight(TOP_BOTTOM_MARGIN, topLineHeight))
                            .semantics { contentDescription = "$CONTENTS_TITLE: $topLine" }
                            .lightClickable(role = Role.Button) { openContents() }
                    )
                }
            }
        }
    }

    /**
     * Opens Contents over the Reader; a Chapter chosen there is jumped to, "Shelf" leaves the Reader as system
     * back does, and back changes nothing. Leaving pops the Reader as Contents' result arrives, before a frame
     * shows the Page again. That relies on LightActivity.goBack delivering the result after the Reader is
     * current again, and on ReaderViewModel not overriding onBackPressed, which system back never asks.
     */
    private fun openContents() {
        val contents = viewModel.openContents() ?: return
        navigateTo({ ContentsScreen(it, contents) }) { choice ->
            when (choice) {
                is ContentsChoice.Chapter -> viewModel.jumpTo(choice.index)
                ContentsChoice.Shelf -> goBack()
            }
        }
    }

    /**
     * The top line, the Page (or the end page) and the footer, stacked (DESIGN.md "Reading"). The Page gets
     * whatever height the top line and footer leave, so a change to either re-packs the Pages at the Place.
     * The top line is [topLineHeight], one Detail line high at the system font scale, whatever the title: a
     * title in a fallback font's taller line can't re-pack the Pages at a Chapter change. Its tap target,
     * which opens Contents, lies over it and the top of the Page ([contentsTargetHeight]).
     */
    @Composable
    private fun Reader(measurer: TextMeasurer, source: MeasurerSource, topLineHeight: Dp) {
        val frame by viewModel.frame.collectAsState()
        val atEnd by viewModel.atEnd.collectAsState()
        val topLine by viewModel.topLine.collectAsState()
        val progressLine by viewModel.progressLine.collectAsState()
        val fontStep by viewModel.fontStep.collectAsState()
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
                val typesetter = remember(measurer, colors.contentSecondary, widthPx, pageHeightPx) {
                    Typesetter(measurer, colors.contentSecondary, widthPx, pageHeightPx, source)
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
                FontButton("A−", -1, fontStep)
                ProgressText(progressLine, Modifier.weight(1f))
                FontButton("A+", +1, fontStep)
            }
        }
    }

    /**
     * "A−" or "A+", changing the size by [delta]. Where that would do nothing (the smallest or largest size)
     * it is drawn in secondary text, ignores taps, and a screen reader hears it as disabled.
     */
    @Composable
    private fun FontButton(label: String, delta: Int, fontStep: Int) {
        val enabled = canChangeFont(fontStep, delta)
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxHeight()
                .lightClickable(enabled = enabled, role = Role.Button) { viewModel.changeFont(delta) }
                .padding(horizontal = 8.dp)
                .wrapContentHeight(),
            lighten = !enabled,
        )
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
            BackToShelf()
        }
    }

    /**
     * A Book with no Page to show, still opening or unreadable, which has no top line and so no Contents:
     * [message], then "Back to Shelf" aligned with it (its tap padding hangs into the margin).
     */
    @Composable
    private fun NoPage(message: String) {
        Column {
            LightText(text = message, variant = LightTextVariant.Copy, lighten = true)
            BackToShelf(Modifier.offset(x = (-8).dp))
        }
    }

    /** "Back to Shelf", which leaves the Reader as system back does. */
    @Composable
    private fun BackToShelf(modifier: Modifier = Modifier) {
        LightText(
            text = BACK_TO_SHELF,
            variant = LightTextVariant.Copy,
            modifier = modifier.lightClickable { goBack() }.padding(8.dp),
            align = TextAlign.Center,
        )
    }
}

/** Whether changing the size from [step] by [delta] changes anything: not A− at the smallest size, nor A+ at the largest. */
fun canChangeFont(step: Int, delta: Int): Boolean = step + delta in FONT_SIZES.indices

/**
 * The footer's Progress line, one line in Detail and secondary text, centred: [line]'s full form when it fits
 * the width, measured as drawn at the system font scale, else its short form, ellipsised if even that
 * doesn't fit. The form not shown isn't placed, so a screen reader hears only the one on screen.
 */
@Composable
private fun ProgressText(line: ProgressLine?, modifier: Modifier) {
    @Composable
    fun Form(text: String) = LightText(
        text = text,
        variant = LightTextVariant.Detail,
        align = TextAlign.Center,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Layout(content = { Form(line?.full.orEmpty()); Form(line?.short.orEmpty()) }, modifier = modifier) { measurables, constraints ->
        val full = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val shown = if (full.width <= constraints.maxWidth) full else measurables[1].measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, shown.height) { shown.place((constraints.maxWidth - shown.width) / 2, 0) }
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
