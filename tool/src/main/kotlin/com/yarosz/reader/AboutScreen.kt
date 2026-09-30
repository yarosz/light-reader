package com.yarosz.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * About (DESIGN.md "About"): [ABOUT_SECTIONS] as plain text that scrolls, each heading a heading to a
 * screen reader. "Reader" on the Shelf opens it, and back returns there. Nothing on it is tappable.
 */
class AboutScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        BackScreen(title = ABOUT_TITLE, onBack = { goBack() }) {
            ABOUT_SECTIONS.forEach { section ->
                Column(rowPadding()) {
                    LightText(text = section.heading, variant = LightTextVariant.Heading, modifier = Modifier.semantics { heading() })
                    section.lines.forEach { line ->
                        LightText(text = line, variant = LightTextVariant.Paragraph, modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                    }
                }
            }
        }
    }
}
