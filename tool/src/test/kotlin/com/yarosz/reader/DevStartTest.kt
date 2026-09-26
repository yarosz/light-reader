package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevStartTest {

    @Test
    fun `a chapter alone opens at its start with the default windows`() {
        assertEquals(DevStart(7), parseDevStart("7\n"))
    }

    @Test
    fun `an offset and a window size follow the chapter`() {
        assertEquals(DevStart(7, 11_850), parseDevStart("7 11850"))
        assertEquals(DevStart(7, 0, 8_000), parseDevStart("  7\t0  8000 \n"))
    }

    @Test
    fun `garbage opens the book normally`() {
        listOf("", " ", "x", "7x", "7 x", "-1", "7 -5", "7 0 0", "7 0 -1", "7 0 8000 1", "99999999999", "7.5")
            .forEach { assertNull(parseDevStart(it), "'$it'") }
    }
}
