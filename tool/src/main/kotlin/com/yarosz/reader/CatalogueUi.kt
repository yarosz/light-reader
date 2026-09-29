package com.yarosz.reader

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** A title's or a failure's line height in ems: tighter than Copy's 1.5, so a wrapped second line sits close to its first. */
private const val TIGHT_LINE_HEIGHT = 1.2f

/** The padding of every list row: the Shelf's, the Catalogue list's and a Catalogue page's. */
@Composable
fun rowPadding(): Modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE_MARGIN, vertical = 0.75f.gridUnitsAsDp())

/**
 * A screen above the Shelf: the theme, a top bar with back, [title] in the centre and an optional
 * [right] button, then [content] scrolling below, by [scrollState].
 */
@Composable
fun BackScreen(
    title: String,
    onBack: () -> Unit,
    right: LightBarButton? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                center = LightTopBarCenter.Text(title),
                rightButton = right,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(Modifier.weight(1f).fillMaxWidth(), scrollState = scrollState, content = content)
        }
    }
}

/** A Book's (or a Catalogue's) title, verbatim, on at most [maxLines] lines. */
@Composable
fun BookTitle(text: String, maxLines: Int = 2) {
    BasicText(text = text, style = tight(LightThemeTokens.typography.copy), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Why adding a Catalogue failed: body text (the SDK's Paragraph size), set tight, so it reads as a message rather than a row. */
@Composable
fun FailureText(text: String, modifier: Modifier = Modifier) {
    BasicText(text = text, style = tight(LightThemeTokens.typography.paragraph), modifier = modifier)
}

/** [style] in the content colour at [TIGHT_LINE_HEIGHT], its design px sizes scaled as LightText scales them. */
@Composable
private fun tight(style: TextStyle): TextStyle = style.copy(
    color = LightThemeTokens.colors.content,
    fontSize = style.fontSize.value.designVerticalPxToSp(),
    lineHeight = (style.fontSize.value * TIGHT_LINE_HEIGHT).designVerticalPxToSp(),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** One line of secondary text. */
@Composable
fun SecondaryLine(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = true, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A row with a title and an optional second line; tappable when [onClick] isn't null. [trailing] sits at its end. */
@Composable
fun ListRow(title: String, detail: String?, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    Row((if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier).then(rowPadding()), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BookTitle(title)
            detail?.let { SecondaryLine(it) }
        }
        trailing?.invoke()
    }
}

/** A text button, like the Shelf's "Remove" and "Cancel". */
@Composable
fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, lighten: Boolean = false) {
    Box(modifier.lightClickable(onClick = onClick)) {
        LightText(text = label, variant = LightTextVariant.Copy, lighten = lighten)
    }
}

/**
 * The inline confirmation a row turns into in Edit: its [title] stays on the first line, so the
 * reader can see what is about to go, then [question], then the [remove] and [cancel] buttons.
 */
@Composable
fun ConfirmRemoval(title: String, question: String, remove: String, cancel: String, onRemove: () -> Unit, onCancel: () -> Unit) {
    Column(rowPadding()) {
        BookTitle(title)
        LightText(text = question, variant = LightTextVariant.Detail)
        Row(Modifier.padding(top = 0.5f.gridUnitsAsDp())) {
            TextAction(remove, onRemove, Modifier.padding(end = 2f.gridUnitsAsDp()))
            TextAction(cancel, onCancel)
        }
    }
}

/** A failure's one plain line, with "Retry" below it only when retrying can help. */
@Composable
fun FailureLine(copy: FailureCopy, onRetry: () -> Unit) {
    Column(rowPadding()) {
        LightText(text = copy.text, variant = LightTextVariant.Copy)
        if (copy.retry) TextAction(RETRY, onRetry, Modifier.padding(top = 0.5f.gridUnitsAsDp()))
    }
}
