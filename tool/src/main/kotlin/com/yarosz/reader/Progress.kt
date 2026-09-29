package com.yarosz.reader

import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Reading copy, verbatim from DESIGN.md "Reading". */
const val READING_OPENING = "Opening…"
const val READING_NO_TEXT = "This Book has no text."
const val READING_COULDNT_OPEN = "Couldn't open this Book."
const val END_PAGE_TEXT = "The end."
const val BACK_TO_SHELF = "Back to Shelf"
const val MINUTES_ALMOST_DONE = "almost done with this chapter"
const val MINUTES_UNDER_ONE_SHORT = "under 1 min left"
const val CONTENTS_TITLE = "Contents"
const val CONTENTS_HERE = "you're here"
const val CONTENTS_SHELF = "Shelf"

/** The reading speed, in words per minute, until the reader has given [MEASURED_AFTER] samples. */
const val PRIOR_WPM = 230.0

/** Samples needed before the reader's own speed replaces [PRIOR_WPM]. */
const val MEASURED_AFTER = 5

/** The speed is the median of at most this many of the newest samples. */
const val SPEED_SAMPLES = 20

/** A Page with fewer words gives no sample: a heading or a Chapter's last lines say little about speed. */
const val SAMPLE_MIN_WORDS = 20

/** Time on a Page outside [SAMPLE_MIN_MS]..[SAMPLE_MAX_MS] gives no sample: a skim, or the reader looked away. */
const val SAMPLE_MIN_MS = 2_000L
const val SAMPLE_MAX_MS = 180_000L

/**
 * A Page read faster than this gives no sample: it was skimmed, or turned past while hunting for a passage,
 * and a few minutes of that would otherwise drag the median, and every minutes line, down for the session.
 */
const val SAMPLE_MAX_WPM = 600.0

/**
 * Where every word of a Book's [spineItems] starts, found once when the Book opens (off the main thread),
 * so counting words on a turn reads no text (ADR 0007). A word is a whitespace-separated run of a Spine
 * item's text; it counts where it starts.
 */
class WordIndex(spineItems: List<SpineItem>) {
    private val starts: List<IntArray> = spineItems.map { wordStarts(it.text) }
    private val before: IntArray = starts.runningFold(0) { acc, item -> acc + item.size }.toIntArray()

    /** Words starting in [from, until). */
    fun between(from: SpinePoint, until: SpinePoint): Int = (wordsBefore(until) - wordsBefore(from)).coerceAtLeast(0)

    private fun wordsBefore(point: SpinePoint): Int {
        if (point.item < 0) return 0
        if (point.item >= starts.size) return before.last()
        val found = Arrays.binarySearch(starts[point.item], point.char)
        return before[point.item] + if (found >= 0) found else -found - 1
    }
}

private fun wordStarts(text: String): IntArray {
    var starts = IntArray(16)
    var count = 0
    for (i in text.indices) {
        if (!text[i].isWhitespace() && (i == 0 || text[i - 1].isWhitespace())) {
            if (count == starts.size) starts = starts.copyOf(count * 2)
            starts[count++] = i
        }
    }
    return starts.copyOf(count)
}

/**
 * The share (0–1) of the Book's text characters before [point], rounded to 4 decimals: a prefix sum over
 * the Spine items' lengths ([OpenBook.charsBefore]), front and back matter included. It is [Place.progress].
 */
fun OpenBook.progressAt(point: SpinePoint): Double {
    val total = charsBefore.last()
    if (total == 0L) return 0.0
    val before = charsBefore[point.item.coerceIn(0, spineItems.size)] + point.char
    return (before.toDouble() / total * 10_000).roundToLong() / 10_000.0
}

/** The Place at [point], with its [Place.progress]: what every Place write stores. */
fun OpenBook.placeAt(point: SpinePoint, now: Long): Place =
    spineItems[point.item].placeOf(point.char, now).copy(progress = progressAt(point))

/** Where Chapter [index] ends: at the next Chapter's start, or at [OpenBook.textEnd] if that comes first. */
fun OpenBook.chapterEnd(index: Int): SpinePoint = chapters.getOrNull(index + 1)?.start?.let { minOf(it, textEnd) } ?: textEnd

/**
 * A Progress line: [full], and the [short] form the footer shows instead when [full] doesn't fit on its one
 * line between "A−" and "A+" (large system text).
 */
data class ProgressLine(val full: String, val short: String)

/**
 * The Progress line for a Page starting at [point]: the minutes left in its Chapter at [wpm]
 * ([minutesLeftCopy]). Null, for no line, in front matter, from [OpenBook.textEnd] on, and in a Chapter
 * whose whole text reads in under a minute.
 */
fun OpenBook.minutesLine(words: WordIndex, point: SpinePoint, wpm: Double): ProgressLine? {
    val index = chapterAt(point) ?: return null
    val end = chapterEnd(index)
    if (point >= end || words.between(chapters[index].start, end) / wpm < 1) return null
    return minutesLeftCopy(words.between(point, end) / wpm)
}

/**
 * The copy for [raw] minutes left, full and short: whole minutes up to 15, rounded up; 5-minute steps from
 * 15, rounded up. [raw] is first rounded to 1e-9, so a quotient that should be whole doesn't round up a step.
 */
fun minutesLeftCopy(raw: Double): ProgressLine {
    val minutes = (raw * 1e9).roundToLong() / 1e9
    val shown = when {
        minutes < 1 -> return ProgressLine(MINUTES_ALMOST_DONE, MINUTES_UNDER_ONE_SHORT)
        minutes < 15 -> ceil(minutes).toInt()
        else -> ceil(minutes / 5).toInt() * 5
    }
    return ProgressLine("about $shown min left in this chapter", "about $shown min left")
}

/** The words per minute of [words] read in [ms], or null when that doesn't count as a sample. */
fun sampleWpm(words: Int, ms: Long): Double? {
    if (words < SAMPLE_MIN_WORDS || ms !in SAMPLE_MIN_MS..SAMPLE_MAX_MS) return null
    return (words * 60_000.0 / ms).takeIf { it <= SAMPLE_MAX_WPM }
}

/**
 * The reader's reading speed: [PRIOR_WPM] until there are [MEASURED_AFTER] samples, then the median of
 * the newest [SPEED_SAMPLES]. It belongs to the reader, not a Book: [ShelfOwner.speed] keeps one for the
 * process, and it is never saved.
 */
class ReadingSpeed {
    private val samples = ArrayDeque<Double>()

    val wpm: Double
        get() {
            if (samples.size < MEASURED_AFTER) return PRIOR_WPM
            val sorted = samples.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }

    /** Keeps [words] read in [ms] as a sample when it counts ([sampleWpm]). */
    fun record(words: Int, ms: Long) {
        samples.addLast(sampleWpm(words, ms) ?: return)
        if (samples.size > SPEED_SAMPLES) samples.removeFirst()
    }
}

/**
 * Times the Page on screen for a [speed] sample. Only a Page reached by a forward turn of one Page is
 * timed ([start]), and only leaving it the same way gives a sample ([finish]); any other way of leaving
 * it, or a relayout under it, drops the timing ([discard]). Times are monotonic millis.
 */
class PageTimer(private val speed: ReadingSpeed) {
    private var startedAt: Long? = null
    private var words = 0

    fun start(now: Long, words: Int) {
        startedAt = now
        this.words = words
    }

    fun finish(now: Long) {
        startedAt?.let { speed.record(words, now - it) }
        startedAt = null
    }

    fun discard() {
        startedAt = null
    }
}
