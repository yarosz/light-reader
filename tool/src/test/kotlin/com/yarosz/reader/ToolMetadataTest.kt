package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals("com.lightos", value("serverPackage"))
    }

    @Test
    fun `tool id never changes once published`() {
        assertEquals("com.yarosz.reader", value("id"))
    }
}
