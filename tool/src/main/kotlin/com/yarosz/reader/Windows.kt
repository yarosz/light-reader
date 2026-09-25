package com.yarosz.reader

/**
 * A run of whole blocks laid out together (ADR 0007): blocks [firstBlock]..[lastBlock] of a Chapter,
 * whose text is [start, end) in [Chapter.text]. A window's index is its position in [windows]' result.
 */
data class Window(val firstBlock: Int, val lastBlock: Int, val start: Int, val end: Int)

/** Characters per window (ADR 0007): ~60–80 ms to lay out on the LP3, inside the 300 ms first-Page bar. */
const val WINDOW_CHARS = 20_000

/**
 * Cuts [chapter] into windows of at most [maxChars] characters that tile its text. Blocks are packed
 * greedily; a single block longer than [maxChars] gets a window to itself. A heading starts a new window
 * once the current one is at least half full, so windows tend to begin where Chapters do.
 */
fun windows(chapter: Chapter, maxChars: Int = WINDOW_CHARS): List<Window> {
    val blocks = chapter.blocks
    if (blocks.isEmpty()) return emptyList()
    val starts = chapter.blockStarts
    fun endOf(block: Int) = if (block < blocks.lastIndex) starts[block + 1] else chapter.text.length
    val out = mutableListOf<Window>()
    var first = 0
    for (next in 1 until blocks.size) {
        val overflows = endOf(next) - starts[first] > maxChars
        val headingCut = blocks[next].kind == BlockKind.Heading && starts[next] - starts[first] >= maxChars / 2
        if (overflows || headingCut) {
            out += Window(first, next - 1, starts[first], starts[next])
            first = next
        }
    }
    out += Window(first, blocks.lastIndex, starts[first], chapter.text.length)
    return out
}

/** Index of the window containing [offset]; offsets past the end land on the last window. */
fun windowIndexFor(windows: List<Window>, offset: Int): Int = windows.indexContaining(offset) { it.start }
