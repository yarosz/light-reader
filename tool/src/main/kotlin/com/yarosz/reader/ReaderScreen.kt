package com.yarosz.reader

import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import java.io.File
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BOOK_URL = "https://standardebooks.org/ebooks/lewis-carroll/alices-adventures-in-wonderland/" +
    "john-tenniel/downloads/lewis-carroll_alices-adventures-in-wonderland_john-tenniel.epub?source=download"

/** Book text face (ADR 0006); chrome keeps Light's typeface. */
private val Literata = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.literata_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

/** Logcat tag for layout timings; `scripts/perf.sh` parses these lines. */
private const val PERF_TAG = "ReaderPerf"

/** Where the reader is: a chapter and a character offset into [Chapter.text]. */
data class Position(val chapter: Int, val offset: Int)

class ReaderViewModel(private val filesDir: File) : LightViewModel<Unit>() {
    val book = MutableStateFlow<Book?>(null)
    val status = MutableStateFlow("Opening…")

    /** The top of the page being read. Relayouts never rewrite it, so font changes can't drift. */
    val position = MutableStateFlow(Position(0, 0))
    val fontStep = MutableStateFlow(DEFAULT_FONT_STEP)

    /** Pages of the current chapter at the current size, published by the UI after layout. */
    var pages: List<Page> = emptyList()

    /** Chapter and font step of the last layout pass, so the next pass can log why it ran. */
    private var lastPass: Pair<Int, Int>? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (book.value != null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { parseEpub(downloadIfMissing()) to devStartChapter() } }
                .onSuccess { (opened, start) ->
                    val largest = opened.chapters.indices.maxBy { opened.chapters[it].text.length }
                    Log.i(PERF_TAG, "book chapters=${opened.chapters.size} largest=$largest " +
                        "largestChars=${opened.chapters[largest].text.length}")
                    start?.let { position.value = Position(it.coerceIn(opened.chapters.indices), 0) }
                    book.value = opened
                }
                .onFailure { status.value = "Couldn't open the book: ${it.message}" }
        }
    }

    private fun downloadIfMissing(): File {
        val file = File(filesDir, "alice.epub")
        if (!file.exists()) {
            status.value = "Downloading Alice…"
            val partial = File(filesDir, "alice.epub.part")
            URL(BOOK_URL).openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
        }
        return file
    }

    /**
     * Dev hook for `scripts/perf.sh`: a chapter index in filesDir/dev-start opens the book there. Only
     * `adb shell run-as` can write that file, and run-as works on debuggable builds only.
     */
    private fun devStartChapter(): Int? =
        File(filesDir, "dev-start").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    /** One logcat line per layout pass: what it laid out, why, and how long measure and paginate took. */
    fun logLayoutPass(chapter: Int, fontStep: Int, chars: Int, measureNs: Long, paginateNs: Long) {
        val last = lastPass
        val reason = when {
            last == null -> "open"
            last.first != chapter -> "chapter"
            last.second != fontStep -> "font"
            else -> "relayout"
        }
        lastPass = chapter to fontStep
        fun ms(ns: Long) = "%.1f".format(Locale.ROOT, ns / 1e6)
        Log.i(PERF_TAG, "layout reason=$reason chapter=$chapter chars=$chars font=${FONT_SIZES[fontStep]} " +
            "measureMs=${ms(measureNs)} paginateMs=${ms(paginateNs)}")
    }

    fun nextPage() {
        val chapters = book.value?.chapters ?: return
        val (chapter, offset) = position.value
        val index = pageIndexFor(pages, offset)
        position.value = when {
            index + 1 < pages.size -> Position(chapter, pages[index + 1].start)
            chapter + 1 < chapters.size -> Position(chapter + 1, 0)
            else -> return
        }
    }

    fun previousPage() {
        val chapters = book.value?.chapters ?: return
        val (chapter, offset) = position.value
        val index = pageIndexFor(pages, offset)
        position.value = when {
            index > 0 -> Position(chapter, pages[index - 1].start)
            chapter > 0 -> Position(chapter - 1, chapters[chapter - 1].text.length) // lands on its last page
            else -> return
        }
    }

    fun changeFont(delta: Int) {
        fontStep.value = (fontStep.value + delta).coerceIn(FONT_SIZES.indices)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> true.also { nextPage() }
        KeyEvent.KEYCODE_VOLUME_UP -> true.also { previousPage() }
        else -> false
    }
}

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

        LightTheme(colors = themeColors) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = SIDE_MARGIN, vertical = TOP_BOTTOM_MARGIN)
            ) {
                book?.let { Reader(it) } ?: LightText(text = status, variant = LightTextVariant.Copy, lighten = true)
            }
        }
    }

    @Composable
    private fun Reader(book: Book) {
        val position by viewModel.position.collectAsState()
        val fontStep by viewModel.fontStep.collectAsState()
        val chapter = book.chapters[position.chapter]
        val colors = LightThemeTokens.colors
        val fontSize = FONT_SIZES[fontStep]
        val style = LightThemeTokens.typography.paragraph.copy(
            fontFamily = Literata,
            color = colors.content,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * LINE_HEIGHT).sp,
            // Centre each glyph in its line box, untrimmed, so ink never crosses a line boundary:
            // pages are clipped bands on line boundaries, and Literata's descenders otherwise
            // leak a sliver of the previous page's last line onto the next.
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            lineBreak = LineBreak.Paragraph,
            hyphens = Hyphens.Auto,
        )
        val secondary = colors.contentSecondary
        val text = remember(chapter, style, secondary) { chapter.toAnnotatedString(style, secondary) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val footerHeight = 64.dp
        val footerPx = with(LocalDensity.current) { footerHeight.toPx() }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth
            val pageHeightPx = constraints.maxHeight - footerPx
            val (layout, pages) = remember(text, style, widthPx, pageHeightPx) {
                val measureStart = System.nanoTime()
                val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
                val paginateStart = System.nanoTime()
                val lines = List(layout.lineCount) { i ->
                    val start = layout.getLineStart(i)
                    val next = if (i < layout.lineCount - 1) layout.getLineStart(i + 1) else chapter.text.length
                    LineMetrics(
                        start = start,
                        top = layout.getLineTop(i),
                        bottom = layout.getLineBottom(i),
                        endsAtBreak = endsAtBreak(chapter.text, next),
                        heading = chapter.kindAt(start) == BlockKind.Heading,
                    )
                }
                // Until the windowed layout lands, the whole chapter is one window packed from its start.
                val whole = windows(chapter, Int.MAX_VALUE).single()
                val pages = pack(listOf(whole), listOf(lines), anchor = 0, pageHeightPx).pages
                val end = System.nanoTime()
                viewModel.logLayoutPass(
                    position.chapter, fontStep, chapter.text.length, paginateStart - measureStart, end - paginateStart,
                )
                layout to pages
            }
            SideEffect { viewModel.pages = pages }
            val index = pageIndexFor(pages, position.offset)
            val page = pages[index]
            val band = page.bands.single()

            Column(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics { contentDescription = chapter.text.substring(page.start, page.end) }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                if (tap.x < size.width / 3f) viewModel.previousPage() else viewModel.nextPage()
                            }
                        }
                ) {
                    clipRect(bottom = band.bottom - band.top) {
                        translate(top = -band.top) { drawText(layout) }
                    }
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
                        text = "${position.chapter + 1}/${book.chapters.size} · p ${index + 1}/${pages.size}",
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

/**
 * Styles each block as its own paragraph. Blocks are separated by a zero-width space rather than
 * [Chapter.text]'s '\n': a paragraph ending in '\n' lays out an extra blank line, while the
 * paragraph-style boundary alone already breaks the paragraph. Same length, so offsets stay
 * identical to [Chapter.text].
 */
private fun Chapter.toAnnotatedString(base: TextStyle, secondary: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        blocks.forEachIndexed { i, block ->
            append(block.text)
            if (i < blocks.lastIndex) append('​')
        }
        blocks.forEachIndexed { i, block ->
            val start = blockStarts[i]
            val end = start + block.text.length
            val paragraph = when (block.kind) {
                BlockKind.Paragraph -> ParagraphStyle(textIndent = TextIndent(firstLine = 1.2.em))
                BlockKind.Heading -> ParagraphStyle(textAlign = TextAlign.Center, lineHeight = base.lineHeight * 1.6f)
                BlockKind.Verse -> ParagraphStyle(textIndent = TextIndent(firstLine = 1.em, restLine = 1.em))
                BlockKind.Caption -> ParagraphStyle(textAlign = TextAlign.Center)
            }
            addStyle(paragraph, start, if (i < blocks.lastIndex) end + 1 else end)
            when (block.kind) {
                BlockKind.Heading -> addStyle(SpanStyle(fontSize = base.fontSize * 1.2f, fontWeight = FontWeight.Medium), start, end)
                BlockKind.Caption -> addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = secondary, fontSize = base.fontSize * 0.85f), start, end)
                else -> Unit
            }
            block.spans.forEach { span ->
                val spanStyle = when (span.emphasis) {
                    Emphasis.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    Emphasis.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                addStyle(spanStyle, start + span.start, start + span.end)
            }
        }
    }
