package com.yarosz.reader

import androidx.compose.ui.unit.dp

// Tunables from DESIGN.md, expected to move with hardware measurements.

/** Reading sizes in sp, the type scale for the LP3's 480 dpi. */
val FONT_SIZES = listOf(17f, 20f, 24.5f, 30f, 36f)

/** 20 sp: the first step above 30 characters per line, where hyphenation stops being constant. */
const val DEFAULT_FONT_STEP = 1

/** Line height as a multiple of the font size; fall back to 1.4 if 1.35 leaks descenders on hardware. */
const val LINE_HEIGHT = 1.35f

val SIDE_MARGIN = 20.dp
val TOP_BOTTOM_MARGIN = 14.dp

/** Below this fill a Page ignores the page-break rules and breaks greedily. */
const val MIN_PAGE_FILL = 0.7f
