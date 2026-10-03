package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevStartTest {

    @Test
    fun `a Spine item alone opens at its start with the default windows`() {
        assertEquals(DevStart(7), parseDevStart("7\n"))
    }

    @Test
    fun `an offset and a window size follow the Spine item`() {
        assertEquals(DevStart(7, 11_850), parseDevStart("7 11850"))
        assertEquals(DevStart(7, 0, 8_000), parseDevStart("  7\t0  8000 \n"))
    }

    @Test
    fun `a Spine item's id may follow, for a lazy open`() {
        assertEquals(DevStart(7, 0, null, "chapter-7"), parseDevStart("7 spine=chapter-7"))
        assertEquals(DevStart(7, 11_850, null, "c7"), parseDevStart("7 11850 spine=c7\n"))
        assertEquals(DevStart(7, 0, 8_000, "c7"), parseDevStart("7 0 8000 spine=c7"))
    }

    @Test
    fun `garbage opens the book normally`() {
        listOf(
            "", " ", "x", "7x", "7 x", "-1", "7 -5", "7 0 0", "7 0 -1", "7 0 8000 1", "99999999999", "7.5",
            "spine=c7", "7 spine=", "spine=c7 7", "7 spine=c7 0", "7 0 8000 1 spine=c7",
        ).forEach { assertNull(parseDevStart(it), "'$it'") }
    }
}
