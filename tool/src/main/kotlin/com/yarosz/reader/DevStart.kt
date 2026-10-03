package com.yarosz.reader

/**
 * Where `scripts/perf.sh` asks the book to open: a Spine item, a character offset into it, and optionally
 * a window size that overrides [WINDOW_CHARS] for that run. The caller clamps both positions to the book.
 * [item] counts the whole Book's Spine items, which only a whole parse knows; with [spineId], that Spine item's
 * id, the open is lazy, as a reader's is (ADR 0009), else it parses the whole Book first.
 */
data class DevStart(val item: Int, val char: Int = 0, val windowChars: Int? = null, val spineId: String? = null)

/**
 * Parses filesDir/dev-start: "<item>", "<item> <char>" or "<item> <char> <windowChars>", whitespace-separated,
 * optionally followed by "spine=<spineId>". Anything else, including negative values, a zero window size or an
 * empty id, is null.
 */
fun parseDevStart(text: String): DevStart? {
    val fields = text.trim().split(Regex("\\s+"))
    val spineId = fields.last().takeIf { it.startsWith(SPINE_FIELD) }?.removePrefix(SPINE_FIELD)
    if (spineId?.isEmpty() == true) return null
    val numberFields = if (spineId != null) fields.dropLast(1) else fields
    if (numberFields.size !in 1..3) return null
    val numbers = numberFields.map { field -> field.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    val windowChars = numbers.getOrNull(2)?.let { chars -> chars.takeIf { it > 0 } ?: return null }
    return DevStart(numbers[0], numbers.getOrNull(1) ?: 0, windowChars, spineId)
}

private const val SPINE_FIELD = "spine="
