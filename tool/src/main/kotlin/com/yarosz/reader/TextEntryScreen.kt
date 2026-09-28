package com.yarosz.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Text entry the LightOS way: the SDK's full-screen editor with the LP3's own keyboard, which
 * returns the text to the screen that opened it (as LightOS's Directions does). [submitLabel] is
 * the keyboard bar's button; back returns nothing.
 */
class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val submitLabel: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                keyboardOptionsFlow = keyboardOptions,
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = submitLabel,
                singleLine = true,
            )
        }
    }
}

/**
 * A search field as text: its placeholder in secondary text over a rule, like the SDK's
 * LightTextField without a label. Tapping it opens [TextEntryScreen].
 */
@Composable
fun SearchField(placeholder: String, onClick: () -> Unit) {
    Column(Modifier.lightClickable(onClick = onClick).then(rowPadding())) {
        SecondaryLine(placeholder)
        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        Spacer(Modifier.fillMaxWidth().height(3f.designVerticalPxToDp()).background(LightThemeTokens.colors.content))
    }
}
