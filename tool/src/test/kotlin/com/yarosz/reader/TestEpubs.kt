package com.yarosz.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The files of a small valid EPUB: a package in OEBPS/ with one Spine item per chapter text. */
fun epubFiles(
    identifier: String? = "urn:uuid:test-book",
    title: String? = "A Test Book",
    chapters: List<String> = listOf("It was a dark and stormy night."),
    extraManifest: String = "",
): Map<String, String> {
    val metadata = listOfNotNull(
        identifier?.let { "<dc:identifier id=\"uid\">$it</dc:identifier>" },
        title?.let { "<dc:title>$it</dc:title>" },
    ).joinToString("")
    val manifest = chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }
    val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
    return mapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata><manifest>$manifest$extraManifest</manifest><spine>$spine</spine></package>""",
    ) + chapters.mapIndexed { i, text ->
        "OEBPS/c$i.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c$i</title></head><body><p>$text</p></body></html>"""
    }
}

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
