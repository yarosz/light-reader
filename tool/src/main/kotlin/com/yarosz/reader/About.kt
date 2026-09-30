package com.yarosz.reader

/** About copy, verbatim from DESIGN.md "About". */
const val ABOUT_TITLE = "About"
const val REPO_URL = "github.com/yarosz/light-reader"
const val ABOUT_NO_NETWORK =
    "Reader has no accounts, no hosted service and no tracking. It uses the network only to fetch a Catalogue and download a Book, and reading works fully offline."
const val ABOUT_KEEP_AWAKE =
    "While you read, the screen stays on until 10 minutes pass without a tap or key press, then follows your phone’s own timeout."
const val ABOUT_COPY_PROTECTION_HEADING = "Books without copy protection"
const val ABOUT_COPY_PROTECTION =
    "Reader opens only Books without copy protection. Copy-protected Books, such as those from Kindle, Apple Books or Libby, can’t be opened."
const val ABOUT_WHERE = "Places to find them:"
const val ABOUT_SOURCE_HEADING = "Source code"
const val ABOUT_LICENSES_HEADING = "Licenses"
const val ABOUT_LICENSE_READER = "Reader: MIT License."
const val ABOUT_LICENSE_LITERATA = "Literata, the typeface of Book text: SIL Open Font License 1.1."
const val ABOUT_LICENSE_SDK = "Light’s SDK: MIT License."
const val ABOUT_LICENSE_LIBRARIES =
    "AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization, Ktor and OkHttp: Apache License 2.0."
const val ABOUT_LICENSE_TEXTS = "The full texts of Reader’s license and Literata’s are in its source code, above."

/** Where to find Books that open: the shipped Catalogues first, then stores and publishers that sell without copy protection. */
val ABOUT_PLACES = listOf(
    "Project Gutenberg (built in)",
    "Standard Ebooks (built in)",
    "Smashwords",
    "Humble Bundle",
    "Tor Publishing",
    "many independent presses",
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
    AboutSection(ABOUT_LICENSES_HEADING, listOf(ABOUT_LICENSE_READER, ABOUT_LICENSE_LITERATA, ABOUT_LICENSE_SDK, ABOUT_LICENSE_LIBRARIES, ABOUT_LICENSE_TEXTS)),
)
