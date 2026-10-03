package com.yarosz.reader

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
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
import kotlin.math.roundToInt
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
        val frame by viewModel.frame.collectAsState()
        val keepAwake by viewModel.keepAwake.collectAsState()
        val hint by viewModel.readingHint.collectAsState()
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val source = MeasurerSource(LocalDensity.current, LocalFontFamilyResolver.current, LocalLayoutDirection.current)
        LaunchedEffect(measurer) { viewModel.warmUp(measurer) }

        LightTheme(colors = themeColors) {
            val opened = book
            val reading = opened != null && opened.spineItems.isNotEmpty()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .then(staysAwake)
                    .then(if (keepAwake && reading && frame != null) Modifier.keepScreenOn() else Modifier)
            ) {
                if (reading && frame != null && !controls) TapZones(hint)
                Box(Modifier.fillMaxSize().padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)) {
                    when {
                        opened == null -> NoPage(status)
                        !reading -> NoPage(READING_NO_TEXT)
                        else -> Reader(measurer, source)
                    }
                }
                hint?.takeIf { reading && frame != null }?.let { step -> key(step) { Guide(step) } }
                if (reading && controls) Controls()
            }
        }
    }

    /**
     * Every pointer press anywhere on the reading view, seen before whatever takes it, keeps the screen on
     * ([ReaderViewModel.stayAwake]), a block's blank space included. A screen reader's click never passes here;
     * the view model's actions keep the screen on for it.
     */
    private val staysAwake = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) viewModel.stayAwake()
            }
        }
    }

    /**
     * Opens Contents over the Reader, once the Book is in ([ReaderViewModel.requestContents]); a Chapter or Part
     * chosen there is jumped to, and back changes nothing.
     */
    private fun openContents() {
        viewModel.requestContents { contents -> navigateTo({ ContentsScreen(it, contents) }) { start -> viewModel.jumpTo(start) } }
    }

    /**
     * The tap zones (DESIGN.md "Reading"): full-height columns across the whole screen, margins included, the
     * left [TAP_BACK_WIDTH] turning back, the next [TAP_CONTROLS_WIDTH] showing the controls, the rest turning
     * forward. They lie under the Page, which takes no taps, so the end page's "Back to Shelf" still does.
     * They are there once a Page or the end page shows and while the controls don't, so a screen reader can't
     * turn under the controls. While the first-run hint's guide shows [hint], the two it doesn't point at are
     * disabled to a screen reader, as a tap there does nothing.
     */
    @Composable
    private fun TapZones(hint: HintStep?) {
        Row(Modifier.fillMaxSize()) {
            TapZone(READING_PREVIOUS_PAGE, TAP_BACK_WIDTH, hint, HintStep.Back) { viewModel.tapBack() }
            TapZone(READING_SHOW_CONTROLS, TAP_CONTROLS_WIDTH, hint, HintStep.Controls) { viewModel.tapMiddle() }
            TapZone(READING_NEXT_PAGE, 1f - TAP_BACK_WIDTH - TAP_CONTROLS_WIDTH, hint, HintStep.Next) { viewModel.tapNext() }
        }
    }

    /**
     * The first-run hint's guide at [step] (DESIGN.md "Reading"), over the Page, from the moment
     * [ReaderViewModel.readingHint] has it, which is already [HINT_DELAY_MS] after the step can show: a touch dot
     * centred in the step's tap zone at mid-height, pressing, and the step's line of copy just below it in a box
     * inverted from the Page (the content colour its fill, the background colour its text), centred on the dot
     * but kept inside the side margins. It takes no taps, so each reaches the tap zone under it, which acts only
     * when it is the step's ([ReaderViewModel.tapNext]); to a screen reader only the copy is there, as plain text
     * announced as it appears, and it is too small to cover a tap zone and drop it from the accessibility tree.
     */
    @Composable
    private fun Guide(step: HintStep) {
        val press = rememberInfiniteTransition(label = "hint")
        val alpha by press.animateFloat(0f, HINT_REST_ALPHA, infiniteRepeatable(keyframes {
            durationMillis = HINT_CYCLE_MS
            1f at HINT_CYCLE_MS * 12 / 100
            1f at HINT_CYCLE_MS * 36 / 100
            HINT_REST_ALPHA at HINT_CYCLE_MS * 70 / 100
        }), label = "alpha")
        val scale by press.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
            durationMillis = HINT_CYCLE_MS
            1f at HINT_CYCLE_MS * 12 / 100
            HINT_PRESS_SCALE at HINT_CYCLE_MS * 24 / 100
            1f at HINT_CYCLE_MS * 36 / 100
        }), label = "scale")
        val colors = LightThemeTokens.colors
        val (centre, copy) = when (step) {
            HintStep.Next -> (1f + TAP_BACK_WIDTH + TAP_CONTROLS_WIDTH) / 2 to HINT_NEXT
            HintStep.Back -> TAP_BACK_WIDTH / 2 to HINT_BACK
            HintStep.Controls -> TAP_BACK_WIDTH + TAP_CONTROLS_WIDTH / 2 to HINT_CONTROLS
        }
        Layout(
            content = {
                Canvas(Modifier.size(HINT_DOT_SIZE).graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }) {
                    val ring = HINT_RING.toPx()
                    drawCircle(colors.content.copy(alpha = HINT_DOT_ALPHA))
                    drawCircle(colors.content.copy(alpha = HINT_RING_ALPHA), radius = size.minDimension / 2 + ring / 2, style = Stroke(ring))
                }
                LightText(
                    text = copy,
                    variant = LightTextVariant.Detail,
                    maxLines = 1,
                    color = colors.background,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(colors.content)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val margin = SIDE_MARGIN.roundToPx()
            val dot = measurables[0].measure(Constraints())
            val box = measurables[1].measure(Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0)))
            val x = (width * centre).roundToInt()
            val y = constraints.maxHeight / 2
            layout(width, constraints.maxHeight) {
                dot.place(x - dot.width / 2, y - dot.height / 2)
                box.place((x - box.width / 2).coerceIn(margin, maxOf(margin, width - margin - box.width)), y + dot.height / 2 + HINT_COPY_GAP.roundToPx())
            }
        }
    }

    /**
     * One tap zone, [width] of the screen's, a button called [label] to a screen reader, disabled while the guide
     * shows a [hint] other than its own [zone].
     */
    @Composable
    private fun RowScope.TapZone(label: String, width: Float, hint: HintStep?, zone: HintStep, onTap: () -> Unit) {
        Box(
            Modifier
                .weight(width)
                .fillMaxHeight()
                .semantics {
                    contentDescription = label
                    if (hint != null && hint != zone) disabled()
                }
                .lightClickable(hapticsEnabled = false, role = Role.Button, onClick = onTap)
        )
    }

    /**
     * The Page, or the end page, filling the reading view inside its margins; showing or hiding the controls,
     * which draw over it, leaves it as it is. The Pages re-pack at the Place when its size changes.
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
                viewModel.drawn(shown)
            }
        }
    }

    /**
     * The controls (DESIGN.md "Reading"), over the Page: the top bar, back to the Shelf, the running head (the
     * Part on a line over the Chapter when there is one) and the list icon that opens Contents, and at the
     * bottom one row, "A−", the Progress line (blank when there is none) and "A+", each block ruled off from
     * the Page. A tap on a block's blank space does nothing; a tap anywhere else hides them.
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
                    .lightClickable(hapticsEnabled = false, role = Role.Button) { viewModel.hideControls() }
            )
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = BACK_TO_SHELF),
                center = runningHead.part?.let { LightTopBarCenter.TwoLineDetail(it, runningHead.title) } ?: LightTopBarCenter.Text(runningHead.title),
                rightButton = LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = { openContents() }, contentDescription = CONTENTS_TITLE),
                modifier = Modifier.align(Alignment.TopCenter).background(background).then(TAKES_TAPS).drawBehind {
                    val y = size.height - CONTROLS_RULE.toPx() / 2
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), CONTROLS_RULE.toPx())
                },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(background)
                    .then(TAKES_TAPS)
                    .drawBehind {
                        val y = CONTROLS_RULE.toPx() / 2
                        drawLine(rule, Offset(0f, y), Offset(size.width, y), CONTROLS_RULE.toPx())
                    }
                    .padding(start = SIDE_MARGIN, end = SIDE_MARGIN, bottom = TOP_BOTTOM_MARGIN),
            ) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    FontButton("A−", -1, fontStep)
                    progressLine?.let { ProgressText(it, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                    FontButton("A+", +1, fontStep)
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

    /** A text button of the controls' bottom row, filling its height, [CONTROL_PADDING] at each side. */
    @Composable
    private fun ControlButton(label: String, enabled: Boolean, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxHeight()
                .lightClickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = CONTROL_PADDING)
                .wrapContentHeight(),
            lighten = !enabled,
        )
    }

    /**
     * "The end." centred, and at the bottom "Back to Shelf", which leaves the Reader as system back does, kept
     * out of the middle so a centre tap shows the controls. Taps elsewhere reach the tap zones, as on a Page.
     */
    @Composable
    private fun EndPage() {
        Box(Modifier.fillMaxSize()) {
            LightText(text = END_PAGE_TEXT, variant = LightTextVariant.Copy, align = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
            BackToShelf(Modifier.align(Alignment.BottomCenter))
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

/** Which of a [ProgressLine]'s forms the controls show. */
enum class ProgressForm { Full, Short }

/** The full form when its one line, [fullWidthPx] wide as drawn, fits in [availableWidthPx], else the short. */
fun progressForm(fullWidthPx: Int, availableWidthPx: Int): ProgressForm =
    if (fullWidthPx <= availableWidthPx) ProgressForm.Full else ProgressForm.Short

/**
 * The controls' Progress line, one line in Detail and secondary text, centred: [line]'s full form when it fits
 * the width, measured as drawn at the system font scale, else its short form, ellipsised if even that
 * doesn't fit. The form not shown isn't placed, so a screen reader hears only the one on screen. Its
 * intrinsic sizes come from Compose's default (measuring at unbounded width), which gives the full form's
 * width: fine for its one caller, a weighted Row slot, which never asks.
 */
@Composable
private fun ProgressText(line: ProgressLine, modifier: Modifier) {
    @Composable
    fun Form(text: String) = LightText(
        text = text,
        variant = LightTextVariant.Detail,
        align = TextAlign.Center,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Layout(content = { Form(line.full); Form(line.short) }, modifier = modifier) { measurables, constraints ->
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

/** Takes the taps on the controls' blocks, so one missing a button does nothing rather than hiding the controls. */
private val TAKES_TAPS = Modifier.pointerInput(Unit) { detectTapGestures { } }
