package com.yarosz.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton

/** What Contents hands the Reader: a Chapter to jump to, by index, or leaving the Book for the Shelf. */
sealed interface ContentsChoice {
    data class Chapter(val index: Int) : ContentsChoice
    data object Shelf : ContentsChoice
}

/**
 * Contents (DESIGN.md "Contents"): one row per Chapter, the current one marked "you're here". A tap hands
 * the Reader that Chapter, "Shelf" on the bar hands it leaving the Book, and back hands it nothing. It opens with the row before the current one at
 * the top, so the current row is second, or the current row at the top when it is first; the list scrolls
 * there as that row is first placed, so no frame shows it from the top. The volume keys stay LightOS's here.
 */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val contents: Contents,
) : SimpleLightScreen<ContentsChoice>(sealedActivity) {

    @Composable
    override fun Content() {
        val scroll = rememberScrollState()
        val top = contents.current?.let { maxOf(it - 1, 0) } ?: 0
        val scrolled = remember { booleanArrayOf(top == 0) }
        BackScreen(
            title = CONTENTS_TITLE,
            onBack = { goBack() },
            right = LightBarButton.Text(CONTENTS_SHELF, onClick = { goBack(ContentsChoice.Shelf) }),
            scrollState = scroll,
        ) {
            contents.titles.forEachIndexed { i, title ->
                val row = if (i != top) Modifier else Modifier.onPlaced { placed ->
                    if (!scrolled[0]) {
                        scrolled[0] = true
                        scroll.dispatchRawDelta(placed.positionInParent().y)
                    }
                }
                Box(row) {
                    ListRow(title, CONTENTS_HERE.takeIf { i == contents.current }, onClick = { goBack(ContentsChoice.Chapter(i)) })
                }
            }
        }
    }
}
