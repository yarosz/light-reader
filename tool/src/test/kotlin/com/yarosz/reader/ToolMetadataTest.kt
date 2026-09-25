package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the committed tool metadata that Light builds releases from. */
class ToolMetadataTest {

    private fun value(key: String): String? =
        File("lighttool.toml").readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"')

    @Test
    fun `committed server package is LightOS on the phone, not the emulator`() {
        // Light's builder uses this value as-is. Emulator builds swap it at build time instead
        // (scripts/emulator-build.sh), so the committed file must never point at the emulator.
        // Exact line: the wrapper's sed and light-build.sh's grep match this form verbatim.
        assertTrue("serverPackage = \"com.lightos\"" in File("lighttool.toml").readLines())
    }

    @Test
    fun `reader sets no orientation lock`() {
        // A portrait lock letterboxes Reader when the app area is shorter than it is wide (DESIGN.md).
        assertEquals(null, value("orientation"))
    }

    @Test
    fun `tool id never changes once published`() {
        assertEquals("com.yarosz.reader", value("id"))
    }
}
