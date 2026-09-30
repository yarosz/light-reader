package com.yarosz.reader

/** About copy, verbatim from DESIGN.md "About". */
const val ABOUT_TITLE = "About"
const val REPO_URL = "github.com/yarosz/light-reader"
const val ABOUT_NO_NETWORK =
    "Reader has no accounts, no service of its own and no tracking. It uses the network only to fetch a Catalogue and download a Book, and reading works fully offline."
const val ABOUT_KEEP_AWAKE =
    "While you read, the screen stays on until 10 minutes pass without a tap or key press, then follows your phone’s own timeout."
const val ABOUT_COPY_PROTECTION_HEADING = "Books without copy protection"
const val ABOUT_COPY_PROTECTION =
    "Reader opens only Books without copy protection. Copy-protected Books, such as those from Kindle, Apple Books or Libby, can’t be opened."
const val ABOUT_WHERE = "Places to find them:"
const val ABOUT_SOURCE_HEADING = "Source code"
const val ABOUT_LICENSES_HEADING = "Licenses"
const val ABOUT_LICENSE_READER = "Reader: MIT License, © 2026 Nicolas Yarosz."
const val ABOUT_LICENSE_LITERATA = "Literata, the typeface of Book text: SIL Open Font License 1.1, © 2017 The Literata Project Authors."
const val ABOUT_LICENSE_SDK = "Light’s SDK and keyboard: MIT License, © 2026 The Light Phone."
const val ABOUT_LICENSE_APACHE =
    "AndroidX, Jetpack Compose, Material Components, Kotlin, kotlinx.coroutines, kotlinx.serialization, Ktor, OkHttp, the UnifiedPush connector, Tink and Guava: Apache License 2.0."
const val ABOUT_NOTICE_KOTLIN = "Kotlin: Copyright 2010-2024 JetBrains s.r.o and respective authors and developers."
const val ABOUT_NOTICE_COROUTINES = "kotlinx.coroutines: Copyright 2016-2025 JetBrains s.r.o and contributors."
const val ABOUT_NOTICE_SERIALIZATION = "kotlinx.serialization: Copyright 2017-2019 JetBrains s.r.o and respective authors and developers."
const val ABOUT_LICENSE_PROTOBUF = "Protocol Buffers: BSD 3-Clause License, © 2008 Google Inc."
const val ABOUT_LICENSE_PUBLIC_SUFFIX = "Public Suffix List: Mozilla Public License 2.0, source publicsuffix.org."
const val ABOUT_LICENSE_TEXTS = "Full license texts: github.com/yarosz/light-reader, THIRD_PARTY_NOTICES.md"

/** Where to find Books that open: only what Reader can reach today, the shipped Catalogues and any the reader adds. */
val ABOUT_PLACES = listOf(
    "Project Gutenberg (built in)",
    "Standard Ebooks (built in)",
    "Any Catalogue you add over https",
)

/** The first heading: the Tool's name and version. */
const val ABOUT_NAME = SHELF_TITLE + " " + VERSION_NAME

/** One block of About: its [heading], then its [lines], each a paragraph. */
data class AboutSection(val heading: String, val lines: List<String>)

/** About, top to bottom. Static: nothing in it is fetched, stored or changed. */
val ABOUT_SECTIONS = listOf(
    AboutSection(ABOUT_NAME, listOf(ABOUT_NO_NETWORK, ABOUT_KEEP_AWAKE)),
    AboutSection(ABOUT_COPY_PROTECTION_HEADING, listOf(ABOUT_COPY_PROTECTION, ABOUT_WHERE) + ABOUT_PLACES),
    AboutSection(ABOUT_SOURCE_HEADING, listOf(REPO_URL)),
    AboutSection(
        ABOUT_LICENSES_HEADING,
        listOf(
            ABOUT_LICENSE_READER, ABOUT_LICENSE_LITERATA, ABOUT_LICENSE_SDK, ABOUT_LICENSE_APACHE, ABOUT_NOTICE_KOTLIN,
            ABOUT_NOTICE_COROUTINES, ABOUT_NOTICE_SERIALIZATION, ABOUT_LICENSE_PROTOBUF, ABOUT_LICENSE_PUBLIC_SUFFIX, ABOUT_LICENSE_TEXTS,
        ),
    ),
)
