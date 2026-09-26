package com.yarosz.reader

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A chapter of random blocks, laid out by a simulated measurer: each block broken into lines by a
 * font-dependent width, some lines ending mid-word (hyphenated), and a mid-word line tending to be
 * followed by another (cascades). That is all the packer consumes; the real Compose layout feeds it the
 * same shape of data. Pixel values are whole numbers unless asked otherwise, so heights summed across
 * seams stay exact.
 */
internal class FakeChapter(val chapter: Chapter) {
    val length get() = chapter.text.length

    /**
     * The whole chapter as one layout, y from 0. Line breaking restarts at each block, so a window's lines
     * are a slice of this. [wholePixels] rounds every line height, as the default fixture expects.
     */
    fun layout(fontSize: Float, rnd: Random, wholePixels: Boolean = true): List<LineMetrics> {
        fun px(value: Float) = if (wholePixels) value.roundToInt().toFloat() else value
        val charsPerLine = (1000f / (fontSize * 0.55f)).toInt().coerceAtLeast(1)
        val lineHeight = px(fontSize * 1.35f)
        val hyphenRate = rnd.nextDouble(0.0, 0.5)
        val lines = mutableListOf<LineMetrics>()
        var y = 0f
        var midWord = false
        chapter.blocks.forEachIndexed { i, block ->
            var offset = chapter.blockStarts[i]
            var remaining = block.text.length
            val heading = block.kind == BlockKind.Heading
            do {
                // Real line breaking wraps at word boundaries, so lines run a bit short.
                val take = minOf(remaining, (charsPerLine * rnd.nextDouble(0.75, 1.0)).toInt().coerceAtLeast(1))
                val height = if (heading) px(lineHeight * 1.25f) else lineHeight
                remaining -= take
                midWord = remaining > 0 && rnd.nextDouble() < if (midWord) 0.7 else hyphenRate
                lines += LineMetrics(offset, y, y + height, endsAtBreak = !midWord, heading = heading)
                offset += take
                y += height
            } while (remaining > 0)
        }
        return lines
    }

    /** [lines] cut at [windows]' seams, each window's pixels re-based to its own random origin, as separate layouts report them. */
    fun cut(lines: List<LineMetrics>, windows: List<Window>, rnd: Random): List<List<LineMetrics>> {
        val byWindow = lines.groupBy { windowIndexFor(windows, it.start) }
        return windows.indices.map { w ->
            val own = byWindow.getValue(w)
            val shift = rnd.nextInt(0, 500).toFloat() - own.first().top
            own.map { it.copy(top = it.top + shift, bottom = it.bottom + shift) }
        }
    }

    companion object {
        /** Up to 60 blocks of 1 to 1,500 characters (the parser drops blank ones); the first is a heading and about one in ten of the rest. */
        fun random(rnd: Random) = FakeChapter(Chapter("", List(rnd.nextInt(1, 60)) { i ->
            val kind = if (i == 0 || rnd.nextDouble() < 0.1) BlockKind.Heading else BlockKind.Paragraph
            Block(kind, "x".repeat(rnd.nextInt(1, 1_500)))
        }))
    }
}

internal val Page.height: Float get() = bands.fold(0f) { acc, band -> acc + (band.bottom - band.top) }

internal fun randomFont(rnd: Random) = rnd.nextDouble(16.0, 48.0).toFloat()

internal fun randomPageHeight(rnd: Random) = rnd.nextInt(200, 1_200).toFloat()

internal fun forAll(runs: Int = 3_000, body: (Random) -> Unit) = repeat(runs) { seed -> body(Random(seed)) }

/** One window over all of a text; the block range is 0..0 because [pack] only reads a window's extent. */
internal fun wholeWindow(textLength: Int) = Window(0, 0, 0, textLength)

/** Line indices [first, last] of each Page, recovered from its text range. */
internal fun lineRanges(lines: List<LineMetrics>, pages: List<Page>): List<IntRange> = pages.map { page ->
    val first = lines.indexOfFirst { it.start == page.start }
    val next = lines.indexOfFirst { it.start >= page.end }
    first..(if (next < 0) lines.lastIndex else next - 1)
}

/** The last line from [first] that still fits [height], with [first] always counted as fitting. */
internal fun greedyLast(lines: List<LineMetrics>, first: Int, height: Float): Int {
    var last = first
    while (last < lines.lastIndex && lines[last + 1].bottom - lines[first].top <= height) last++
    return last
}

/** The earliest line that, together with everything down to [last], still fits [height]; [last] always counts as fitting. */
internal fun greedyFirst(lines: List<LineMetrics>, last: Int, height: Float): Int {
    var first = last
    while (first > 0 && lines[last].bottom - lines[first - 1].top <= height) first--
    return first
}

internal fun LineMetrics.legal() = endsAtBreak && !heading

/** A Page may start at line [s] when the line above it may end a Page; the chapter's first line always may. */
internal fun List<LineMetrics>.legalStart(s: Int) = s == 0 || this[s - 1].legal()

internal fun LineMetrics.filled(top: Float, height: Float) = bottom - top >= MIN_PAGE_FILL * height
