package com.yarosz.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The files of a small valid EPUB: a package in OEBPS/ with one Spine item per text. */
fun epubFiles(
    identifier: String? = "urn:uuid:test-book",
    title: String? = "A Test Book",
    spineItems: List<String> = listOf("It was a dark and stormy night."),
    extraManifest: String = "",
): Map<String, String> {
    val metadata = listOfNotNull(
        identifier?.let { "<dc:identifier id=\"uid\">$it</dc:identifier>" },
        title?.let { "<dc:title>$it</dc:title>" },
    ).joinToString("")
    val manifest = spineItems.indices.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }
    val spine = spineItems.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
    return mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata><manifest>$manifest$extraManifest</manifest><spine>$spine</spine></package>""",
    ) + spineItems.mapIndexed { i, text ->
        "OEBPS/c$i.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c$i</title></head><body><p>$text</p></body></html>"""
    }
}

/**
 * The files of an EPUB of [bodies], one Spine item each, in OEBPS/text/ under [names]; [nav] sits in OEBPS/
 * under [navName], [ncx] in OEBPS/. [bodyAttributes] gives a Spine item's `<body>` its attributes, by index.
 */
fun tocEpubFiles(
    bodies: List<String>,
    nav: String? = null,
    ncx: String? = null,
    names: List<String> = bodies.indices.map { "c$it.xhtml" },
    navName: String = "nav.xhtml",
    bodyAttributes: Map<Int, String> = emptyMap(),
): Map<String, String> {
    val manifest = bodies.indices.joinToString("") {
        """<item id="c$it" href="text/${names[it].replace(" ", "%20")}" media-type="application/xhtml+xml"/>"""
    } + (if (nav != null) """<item id="nav" href="$navName" media-type="application/xhtml+xml" properties="nav"/>""" else "") +
        (if (ncx != null) """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" else "")
    val spine = bodies.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
    return mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:chapters</dc:identifier><dc:title>Chapters</dc:title></metadata><manifest>$manifest</manifest><spine${if (ncx != null) " toc=\"ncx\"" else ""}>$spine</spine></package>""",
    ) + bodies.mapIndexed { i, body ->
        val attributes = bodyAttributes[i]?.let { " $it" }.orEmpty()
        "OEBPS/text/${names[i]}" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>c$i</title></head><body$attributes>$body</body></html>"""
    } + listOfNotNull(nav?.let { "OEBPS/$navName" to it }, ncx?.let { "OEBPS/toc.ncx" to it })
}

fun ncx(vararg points: String) =
    """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Chapters</text></docTitle><navMap>${points.joinToString("")}</navMap></ncx>"""

fun navPoint(label: String, src: String, vararg children: String) =
    "<navPoint><navLabel><text>$label</text></navLabel><content src=\"$src\"/>${children.joinToString("")}</navPoint>"

fun zipBytes(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
        files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
}.toByteArray()

fun File.writeEpub(files: Map<String, String>): File = apply { writeBytes(zipBytes(files)) }
