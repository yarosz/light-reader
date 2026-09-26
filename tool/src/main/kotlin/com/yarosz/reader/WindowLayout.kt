package com.yarosz.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Book text face (ADR 0006); chrome keeps Light's typeface. */
private val Literata = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.literata_bolditalic, FontWeight.Bold, FontStyle.Italic),
)

/** Narrow enough at the default size that the warm-up string hyphenates, so the hyphenator loads too. */
private const val WARM_UP_WIDTH_PX = 240

/** A window measured at one [LayoutKey]: its layout, for drawing, and its lines, for packing. */
class WindowLayout(val layout: TextLayoutResult, val lines: List<LineMetrics>)

/**
 * The body style at [FONT_SIZES] step [fontStep] (DESIGN.md). Body colour is left to drawing, but
 * captions bake [Typesetter]'s caption colour into the layout, so a theme change relays the book out.
 */
fun readingStyle(fontStep: Int): TextStyle {
    val size = FONT_SIZES[fontStep]
    return TextStyle(
        fontFamily = Literata,
        fontSize = size.sp,
        lineHeight = (size * LINE_HEIGHT).sp,
        // Centre each glyph in its line box, untrimmed, so ink never crosses a line boundary: pages
        // are clipped bands on line boundaries, and Literata's descenders otherwise leak a sliver of
        // the previous page's last line onto the next.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        lineBreak = LineBreak.Paragraph,
        hyphens = Hyphens.Auto,
    )
}

/**
 * Measures windows at one column width and page height, for the passes. Usable from any thread: the
 * measurer wraps the composition's font resolver, which Compose documents for "creating Paragraph
 * objects on background thread" and whose typeface caches are lock-guarded; with no layout cache the
 * measurer holds no state of its own, and the platform layout it returns is immutable once built.
 */
class Typesetter(private val measurer: TextMeasurer, private val captionColor: Color, val widthPx: Int, val pageHeightPx: Int) {

    fun key(fontStep: Int) = LayoutKey(fontStep, widthPx, pageHeightPx)

    fun measure(chapter: Chapter, window: Window, fontStep: Int): WindowLayout {
        val style = readingStyle(fontStep)
        val layout = measurer.measure(chapter.annotate(window, style, captionColor), style, constraints = Constraints(maxWidth = widthPx))
        val lines = List(layout.lineCount) { i ->
            val start = window.start + layout.getLineStart(i)
            val next = if (i < layout.lineCount - 1) window.start + layout.getLineStart(i + 1) else window.end
            LineMetrics(
                start = start,
                top = layout.getLineTop(i),
                bottom = layout.getLineBottom(i),
                endsAtBreak = endsAtBreak(chapter.text, next),
                heading = chapter.kindAt(start) == BlockKind.Heading,
            )
        }
        return WindowLayout(layout, lines)
    }
}

/** One short measure in every face the book uses, hyphenated, so the first window's measure pays for neither typefaces nor the hyphenator (ADR 0007). */
fun warmUpMeasurer(measurer: TextMeasurer) {
    val text = buildAnnotatedString {
        append("Antidisestablishmentarianism, ")
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("uncharacteristically ") }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("incomprehensibilities, ") }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append("counterrevolutionaries.") }
    }
    measurer.measure(text, readingStyle(DEFAULT_FONT_STEP), constraints = Constraints(maxWidth = WARM_UP_WIDTH_PX))
}

/**
 * A window's blocks as styled paragraphs over [Chapter.windowText]: each block its own paragraph with
 * its kind's style, emphasis spans, and a first-line indent only where [indentsFirstLine] says. A
 * paragraph style covers its block's trailing separator, so the paragraph break falls exactly there.
 */
private fun Chapter.annotate(window: Window, base: TextStyle, captionColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(windowText(window))
        for (i in window.firstBlock..window.lastBlock) {
            val block = blocks[i]
            val start = blockStarts[i] - window.start
            val end = start + block.text.length
            val paragraph = when (block.kind) {
                BlockKind.Paragraph -> ParagraphStyle(textIndent = if (indentsFirstLine(blocks, i)) TextIndent(firstLine = 1.2.em) else null)
                BlockKind.Heading -> ParagraphStyle(textAlign = TextAlign.Center, lineHeight = base.lineHeight * 1.6f)
                BlockKind.Verse -> ParagraphStyle(textIndent = TextIndent(firstLine = 1.em, restLine = 1.em))
                BlockKind.Caption -> ParagraphStyle(textAlign = TextAlign.Center)
            }
            addStyle(paragraph, start, if (i < blocks.lastIndex) end + 1 else end)
            when (block.kind) {
                BlockKind.Heading -> addStyle(SpanStyle(fontSize = base.fontSize * 1.2f, fontWeight = FontWeight.Medium), start, end)
                BlockKind.Caption -> addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = captionColor, fontSize = base.fontSize * 0.85f), start, end)
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
