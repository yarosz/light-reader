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

/** Zero-width space: stands in for the '\n' between blocks in a window's laid-out text (see [windowText]). */
const val BLOCK_SEPARATOR = '​'

/**
 * A window's text for layout: its blocks with [BLOCK_SEPARATOR] wherever [Chapter.text] has a '\n'
 * between blocks, including the one after the window's last block unless it ends the chapter. A '\n'
 * there would lay out an extra blank line; the paragraph styles the caller adds break the paragraphs
 * instead. Same length as the window, so a layout offset plus [Window.start] is a [Chapter.text] offset.
 */
fun Chapter.windowText(window: Window): String = buildString(window.end - window.start) {
    for (i in window.firstBlock..window.lastBlock) {
        append(blocks[i].text)
        if (i < blocks.lastIndex) append(BLOCK_SEPARATOR)
    }
}

/**
 * Whether block [i] takes a first-line indent: a paragraph following another paragraph (Standard
 * Ebooks' "p + p" convention, DESIGN.md). Judged on block kinds alone, so a paragraph that opens a
 * window indents when the block before it in the chapter is a paragraph.
 */
fun indentsFirstLine(blocks: List<Block>, i: Int): Boolean =
    blocks[i].kind == BlockKind.Paragraph && i > 0 && blocks[i - 1].kind == BlockKind.Paragraph
