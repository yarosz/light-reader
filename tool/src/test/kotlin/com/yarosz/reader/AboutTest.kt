package com.yarosz.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutTest {

    private val text = ABOUT_SECTIONS.flatMap { listOf(it.heading) + it.lines }
    private val licenses = ABOUT_SECTIONS.single { it.heading == ABOUT_LICENSES_HEADING }.lines

    @Test
    fun `About opens on the Tool's name and version`() {
        assertEquals("Reader $VERSION_NAME", ABOUT_NAME)
        assertEquals(ABOUT_NAME, ABOUT_SECTIONS.first().heading)
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
        (text + COPY_COPY_PROTECTED_DETAIL).forEach { assertTrue(!it.contains("DRM", ignoreCase = true), it) }
        assertTrue(COPY_COPY_PROTECTED_DETAIL.startsWith(COPY_COPY_PROTECTED))
    }

    @Test
    fun `About names Reader's license, Literata's and the SDK's`() {
        assertTrue(File("../LICENSE").readLines().first() == "MIT License" && ABOUT_LICENSE_READER in licenses)
        assertTrue("SIL Open Font License, Version 1.1" in File("../third_party/literata/OFL.txt").readText() && ABOUT_LICENSE_LITERATA in licenses)
        assertTrue(File("../light-sdk/LICENSE").readLines().first() == "MIT License" && ABOUT_LICENSE_SDK in licenses)
    }

    @Test
    fun `every component a license line names is in THIRD_PARTY_NOTICES, and the last line points there`() {
        val notices = File("../THIRD_PARTY_NOTICES.md").readText()
        val named = mapOf(
            ABOUT_LICENSE_READER to listOf("Reader", "MIT License", "Nicolas Yarosz"),
            ABOUT_LICENSE_LITERATA to listOf("Literata", "SIL Open Font License", "The Literata Project Authors"),
            ABOUT_LICENSE_SDK to listOf("Light", "keyboard", "The Light Phone"),
            ABOUT_LICENSE_APACHE to listOf(
                "AndroidX", "Jetpack Compose", "Material Components", "Kotlin", "kotlinx.coroutines", "kotlinx.serialization",
                "Ktor", "OkHttp", "UnifiedPush", "Tink", "Guava", "Apache License",
            ),
            ABOUT_NOTICE_KOTLIN to listOf("Copyright 2010-2024 JetBrains s.r.o and respective authors and developers"),
            ABOUT_NOTICE_COROUTINES to listOf("Copyright 2016-2025 JetBrains s.r.o and contributors"),
            ABOUT_NOTICE_SERIALIZATION to listOf("Copyright 2017-2019 JetBrains s.r.o and respective authors and developers"),
            ABOUT_LICENSE_PROTOBUF to listOf("Protocol Buffers", "BSD 3-Clause", "2008 Google Inc."),
            ABOUT_LICENSE_PUBLIC_SUFFIX to listOf("Public Suffix List", "Mozilla Public License", "publicsuffix.org"),
        )
        assertEquals(licenses.dropLast(1), named.keys.toList())
        named.forEach { (line, components) ->
            components.forEach { assertTrue(it in line && it in notices, "$it: $line") }
        }
        assertEquals("Full license texts: $REPO_URL, THIRD_PARTY_NOTICES.md", licenses.last())
    }
}
