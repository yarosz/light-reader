package com.yarosz.reader

import androidx.compose.ui.unit.dp
import kotlin.math.abs

// Tunables from DESIGN.md, expected to move with hardware measurements.

/** Reading font sizes in sp, smallest first: the type scale for the LP3's 480 dpi. A font step indexes it. */
val FONT_SIZES = listOf(15f, 17f, 20f, 24.5f, 30f, 36f)

/** 20 sp: the first size above 30 characters per line, where hyphenation stops being constant. */
const val DEFAULT_FONT_STEP = 2

/** The font step of the size in [sizes] nearest [size] sp, the smaller at a tie. */
fun nearestFontStep(size: Float, sizes: List<Float> = FONT_SIZES): Int = sizes.indices.minBy { abs(sizes[it] - size) }

/** Line height as a multiple of the font size; leak-free on the LP3 with LineHeightStyle(Center, Trim.None). */
const val LINE_HEIGHT = 1.35f

/**
 * The reading view's tap zones, full-height columns (DESIGN.md "Reading"): the left 30% turns back, the next
 * 25% shows the controls, and the rest turns forward, the likeliest tap getting the widest zone.
 */
const val TAP_BACK_WIDTH = 0.30f
const val TAP_CONTROLS_WIDTH = 0.25f

/** The rule between the controls and the Page. */
val CONTROLS_RULE = 1.dp

/** Each side of "A−" and "A+" in the controls' bottom row, so each target is 48 dp or wider. */
val CONTROL_PADDING = 12.dp

val SIDE_MARGIN = 20.dp
val TOP_BOTTOM_MARGIN = 14.dp
