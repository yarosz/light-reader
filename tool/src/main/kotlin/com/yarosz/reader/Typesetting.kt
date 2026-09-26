package com.yarosz.reader

import androidx.compose.ui.unit.dp

// Tunables from DESIGN.md, expected to move with hardware measurements.

/** Reading sizes in sp, the type scale for the LP3's 480 dpi. */
val FONT_SIZES = listOf(17f, 20f, 24.5f, 30f, 36f)

/** 20 sp: the first step above 30 characters per line, where hyphenation stops being constant. */
const val DEFAULT_FONT_STEP = 1

/** Line height as a multiple of the font size; leak-free on the LP3 with LineHeightStyle(Center, Trim.None). */
const val LINE_HEIGHT = 1.35f

val SIDE_MARGIN = 20.dp
val TOP_BOTTOM_MARGIN = 14.dp
