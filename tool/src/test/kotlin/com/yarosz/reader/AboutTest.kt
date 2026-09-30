package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutTest {

    private val text = ABOUT_SECTIONS.flatMap { listOf(it.heading) + it.lines }

    @Test
    fun `About opens on the Tool's name and the committed version`() {
        val versionName = File("lighttool.toml").readLines().map { it.trim() }.first { it.startsWith("versionName") }
            .substringAfter('=').trim().trim('"')
        assertEquals("Reader $versionName", ABOUT_SECTIONS.first().heading)
    }

    @Test
    fun `About says what the network is used for, and shows the source as text`() {
        assertTrue(ABOUT_NO_NETWORK in ABOUT_SECTIONS.first().lines)
        assertTrue("github.com/yarosz/light-reader" in text)
        assertTrue(text.none { "://" in it }, "an address to read, not a link")
    }

    @Test
    fun `the list of places to find Books without copy protection names every shipped Catalogue`() {
        val section = ABOUT_SECTIONS.single { it.heading == ABOUT_COPY_PROTECTION_HEADING }
        SHIPPED_CATALOGUES.forEach { catalogue ->
            val name = catalogue.name.substringBefore(':')
            assertTrue(section.lines.any { it.startsWith(name) }, name)
        }
    }

    @Test
    fun `Tool copy says copy-protected, never DRM`() {
        (text + COPY_COPY_PROTECTED_DETAIL).forEach { assertTrue("DRM" !in it, it) }
        assertTrue(COPY_COPY_PROTECTED_DETAIL.startsWith(COPY_COPY_PROTECTED))
    }

    @Test
    fun `About names Reader's license, Literata's and the SDK's`() {
        val licenses = ABOUT_SECTIONS.single { it.heading == ABOUT_LICENSES_HEADING }.lines
        assertTrue(File("../LICENSE").readLines().first() == "MIT License" && ABOUT_LICENSE_READER in licenses)
        assertTrue("SIL Open Font License, Version 1.1" in File("../third_party/literata/OFL.txt").readText() && ABOUT_LICENSE_LITERATA in licenses)
        assertTrue(File("../light-sdk/LICENSE").readLines().first() == "MIT License" && ABOUT_LICENSE_SDK in licenses)
    }
}
