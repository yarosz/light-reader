package com.yarosz.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable

/**
 * Contents (DESIGN.md "Contents"): its rows in order, a Part's row a heading, the current row marked
 * "you’re here". A tap hands the Reader that row's start, a Chapter's or a Part's, and back hands it
 * nothing; there is no way straight to the Shelf. It opens with the row before the current one at the top,
 * so the current row is second, or the current row at the top when it is first; the list scrolls there as
 * that row is first placed, so no frame shows it from the top. The volume keys stay LightOS's here.
 */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val contents: Contents,
) : SimpleLightScreen<SpinePoint>(sealedActivity) {
    @Composable
    override fun Content() {
        val scroll = rememberScrollState()
        val top = contents.current?.let { maxOf(it - 1, 0) } ?: 0
        val scrolled = remember { booleanArrayOf(top == 0) }
        BackScreen(
            title = CONTENTS_TITLE,
            onBack = { goBack() },
            scrollState = scroll,
        ) {
            contents.rows.forEachIndexed { i, row ->
                val placed = if (i != top) Modifier else Modifier.onPlaced { placed ->
                    if (!scrolled[0]) {
                        scrolled[0] = true
                        scroll.dispatchRawDelta(placed.positionInParent().y)
                    }
                }
                val choose = { goBack(row.start) }
                Box(placed) {
                    if (row.isPart) {
                        Box(Modifier.lightClickable(onClick = choose).then(rowPadding()).semantics { heading() }) {
                            LightText(text = row.title, variant = LightTextVariant.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        ListRow(row.title, CONTENTS_HERE.takeIf { i == contents.current }, onClick = choose)
                    }
                }
            }
        }
    }
}
