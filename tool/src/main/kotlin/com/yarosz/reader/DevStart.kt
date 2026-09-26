package com.yarosz.reader

/**
 * Where `scripts/perf.sh` asks the book to open: a chapter, a character offset into it, and optionally
 * a window size that overrides [WINDOW_CHARS] for that run. The caller clamps both positions to the book.
 */
data class DevStart(val chapter: Int, val offset: Int = 0, val windowChars: Int? = null)

/**
 * Parses filesDir/dev-start: "<chapter>", "<chapter> <offset>" or "<chapter> <offset> <windowChars>",
 * whitespace-separated. Anything else, including negative values or a zero window size, is null.
 */
fun parseDevStart(text: String): DevStart? {
    val fields = text.trim().split(Regex("\\s+"))
    if (fields.size !in 1..3) return null
    val numbers = fields.map { field -> field.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    val windowChars = numbers.getOrNull(2)?.let { chars -> chars.takeIf { it > 0 } ?: return null }
    return DevStart(numbers[0], numbers.getOrNull(1) ?: 0, windowChars)
}
