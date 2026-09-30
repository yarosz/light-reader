package com.yarosz.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import java.io.File
import kotlin.math.sign

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
        val controls by viewModel.controls.collectAsState()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val source = MeasurerSource(LocalDensity.current, LocalFontFamilyResolver.current, LocalLayoutDirection.current)
        LaunchedEffect(measurer) { viewModel.warmUp(measurer) }

        LightTheme(colors = themeColors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                val opened = book
                val reading = opened != null && opened.spineItems.isNotEmpty()
                if (reading) TapZones()
                Box(Modifier.fillMaxSize().padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)) {
                    when {
                        opened == null -> NoPage(status)
                        !reading -> NoPage(READING_NO_TEXT)
                        else -> Reader(measurer, source)
                    }
                }
                if (reading && controls) Controls()
            }
        }
    }

    /**
     * Opens Contents over the Reader; a Chapter or Part chosen there is jumped to, "Shelf" leaves the Reader as system
     * back does, and back changes nothing. Leaving pops the Reader as Contents' result arrives, before a frame
     * shows the Page again. That relies on LightActivity.goBack delivering the result after the Reader is
     * current again, and on ReaderViewModel not overriding onBackPressed, which system back never asks.
     */
    private fun openContents() {
        val contents = viewModel.openContents() ?: return
        navigateTo({ ContentsScreen(it, contents) }) { choice ->
            when (choice) {
                is ContentsChoice.Row -> viewModel.jumpTo(choice.start)
                ContentsChoice.Shelf -> goBack()
            }
        }
    }

    /**
     * The tap zones (DESIGN.md "Reading"): full-height columns across the whole screen, margins included, the
     * left [TAP_BACK_WIDTH] turning back, the next [TAP_CONTROLS_WIDTH] showing the controls, the rest turning
     * forward. They lie under the Page, which takes no taps, so the end page's "Back to Shelf" still does.
     */
    @Composable
    private fun TapZones() {
        Row(Modifier.fillMaxSize()) {
            TapZone(READING_PREVIOUS_PAGE, TAP_BACK_WIDTH) { viewModel.previousPage() }
            TapZone(READING_SHOW_CONTROLS, TAP_CONTROLS_WIDTH) { viewModel.controls.value = true }
            TapZone(READING_NEXT_PAGE, 1f - TAP_BACK_WIDTH - TAP_CONTROLS_WIDTH) { viewModel.nextPage() }
        }
    }

    /** One tap zone, [width] of the screen's, a button called [label] to a screen reader. */
    @Composable
    private fun RowScope.TapZone(label: String, width: Float, onTap: () -> Unit) {
        Box(
            Modifier
                .weight(width)
                .fillMaxHeight()
                .semantics { contentDescription = label }
                .lightClickable(hapticsEnabled = false, role = Role.Button, onClick = onTap)
        )
    }

    /**
     * The Page, or the end page, filling the reading view inside its margins; it never moves, and the controls
     * draw over it. The Pages re-pack at the Place when its size changes.
     */
    @Composable
    private fun Reader(measurer: TextMeasurer, source: MeasurerSource) {
        val frame by viewModel.frame.collectAsState()
        val atEnd by viewModel.atEnd.collectAsState()
        val colors = LightThemeTokens.colors

        BoxWithConstraints(Modifier.fillMaxSize()) {
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
    }

    /**
     * The controls (DESIGN.md "Reading"), over the Page: the top bar, back to the Shelf and the running head,
     * and at the bottom the Progress line over "A−" and "A+" at the left and "Contents" at the right, each
     * block ruled off from the Page. A tap anywhere else hides them.
     */
    @Composable
    private fun Controls() {
        val runningHead by viewModel.runningHead.collectAsState()
        val progressLine by viewModel.progressLine.collectAsState()
        val fontStep by viewModel.fontStep.collectAsState()
        val background = LightThemeTokens.colors.background
        val rule = LightThemeTokens.colors.contentSecondary
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = READING_HIDE_CONTROLS }
                    .lightClickable(hapticsEnabled = false, role = Role.Button) { viewModel.controls.value = false }
            )
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(runningHead),
                modifier = Modifier.align(Alignment.TopCenter).background(background).drawBehind {
                    drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), CONTROLS_RULE.toPx())
                },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(background)
                    .drawBehind { drawLine(rule, Offset.Zero, Offset(size.width, 0f), CONTROLS_RULE.toPx()) }
                    .padding(start = SIDE_MARGIN, end = SIDE_MARGIN, top = 8.dp, bottom = TOP_BOTTOM_MARGIN),
            ) {
                ProgressText(progressLine, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    FontButton("A−", -1, fontStep)
                    FontButton("A+", +1, fontStep)
                    Spacer(Modifier.weight(1f))
                    ControlButton(CONTENTS_TITLE, enabled = true) { openContents() }
                }
            }
        }
    }

    /**
     * "A−" or "A+", changing the size by [delta]. Where that would do nothing (the smallest or largest size)
     * it is drawn in secondary text, ignores taps, and a screen reader hears it as disabled.
     */
    @Composable
    private fun FontButton(label: String, delta: Int, fontStep: Int) =
        ControlButton(label, canChangeFont(fontStep, delta)) { viewModel.changeFont(delta) }

    /** A text button of the controls' bottom row, filling its height, with 8 dp of padding at the sides. */
    @Composable
    private fun ControlButton(label: String, enabled: Boolean, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxHeight()
                .lightClickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
     * A Book with no Page to show, still opening or unreadable, which has no controls and so no Contents:
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

/**
 * Whether there is a size in [delta]'s direction from [step]: not for A− at the smallest size, nor A+ at the
 * largest. A larger step than one is clamped by [ReaderViewModel.changeFont], so only its sign counts here.
 */
fun canChangeFont(step: Int, delta: Int): Boolean = step + delta.sign in FONT_SIZES.indices

/** Which of a [ProgressLine]'s forms the footer shows. */
enum class ProgressForm { Full, Short }

/** The full form when its one line, [fullWidthPx] wide as drawn, fits in [availableWidthPx], else the short. */
fun progressForm(fullWidthPx: Int, availableWidthPx: Int): ProgressForm =
    if (fullWidthPx <= availableWidthPx) ProgressForm.Full else ProgressForm.Short

/**
 * The controls' Progress line, one line in Detail and secondary text, centred: [line]'s full form when it fits
 * the width, measured as drawn at the system font scale, else its short form, ellipsised if even that
 * doesn't fit. The form not shown isn't placed, so a screen reader hears only the one on screen. Its
 * intrinsic sizes come from Compose's default (measuring at unbounded width), which gives the full form's
 * width: fine for its one caller, a full-width slot, which never asks.
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
        val shown = when (progressForm(full.width, constraints.maxWidth)) {
            ProgressForm.Full -> full
            ProgressForm.Short -> measurables[1].measure(constraints.copy(minWidth = 0))
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(shown.width)
        layout(width, shown.height) { shown.place((width - shown.width) / 2, 0) }
    }
}

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

/** The rule between the controls and the Page. */
private val CONTROLS_RULE = 1.dp
