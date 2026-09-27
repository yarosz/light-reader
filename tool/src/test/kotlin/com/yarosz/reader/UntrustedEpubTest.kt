package com.yarosz.reader

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.xml.sax.SAXException

/** A downloaded EPUB is untrusted: its XML never reads a local file or the network, and never grows without bound. */
class UntrustedEpubTest {

    private val dir: File = createTempDirectory("untrusted").toFile()
    private val secret = File(dir, "secret.txt").apply { writeText("SECRET") }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun epub(files: Map<String, String>) = File(dir, "book.epub").writeEpub(files)

    private val opf = "OEBPS/content.opf"
    private val container = "META-INF/container.xml"

    /** [files] with [doctype] put before the root element of [path]. */
    private fun Map<String, String>.withDoctype(path: String, doctype: String) =
        this + (path to getValue(path).replaceFirst("?>", "?>$doctype"))

    @Test
    fun `a file entity in the package puts no file content in the title`() {
        val files = epubFiles(title = "Before&s;After").withDoctype(opf, """<!DOCTYPE package [<!ENTITY s SYSTEM "${secret.toURI()}">]>""")
        val book = parseEpub(epub(files))
        assertEquals("BeforeAfter", book.title)
        assertFalse("SECRET" in book.chapters.single().text)
    }

    @Test
    fun `a file entity in the container reads nothing from disk, so even a missing file doesn't matter`() {
        val files = epubFiles().let { base -> base + (container to base.getValue(container).replace("<rootfiles>", "<rootfiles>&n;")) }
            .withDoctype(container, """<!DOCTYPE container [<!ENTITY n SYSTEM "${File(dir, "missing.txt").toURI()}">]>""")
        assertEquals("A Test Book", ZipFile(epub(files)).use { readPackage(it, "t") }.title)
    }

    @Test
    fun `a package with an external DTD, or the OEB DOCTYPE, parses offline`() {
        withNoNetwork {
            val local = epubFiles().withDoctype(opf, """<!DOCTYPE package SYSTEM "http://127.0.0.1:9/x.dtd">""")
            assertEquals("A Test Book", parseEpub(epub(local)).title)
            val oeb = epubFiles().withDoctype(
                opf,
                """<!DOCTYPE package PUBLIC "+//ISBN 0-9673008-1-9//DTD OEB 1.2 Package//EN" "http://openebook.org/dtds/oeb-1.2/oebpkg12.dtd">""",
            )
            assertEquals("A Test Book", parseEpub(epub(oeb)).title)
        }
    }

    @Test
    fun `an EPUB2 chapter with the XHTML 1_1 DOCTYPE parses offline`() {
        val files = epubFiles(chapters = listOf("Chapter text.")).withDoctype(
            "OEBPS/c0.xhtml",
            """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">""",
        )
        val book = withNoNetwork { parseEpub(epub(files)) }
        assertEquals("Chapter text.", book.chapters.single().text)
    }

    @Test
    fun `an entity-expansion bomb in the package fails`() {
        val files = epubFiles() + (opf to LAUGHS)
        assertFailsWith<SAXException> { ZipFile(epub(files)).use { readPackage(it, "t") } }
    }

    @Test
    fun `a package or chapter that decompresses past its cap fails`() {
        val padding = " ".repeat(MAX_PACKAGE_XML_BYTES.toInt())
        val bigPackage = epubFiles().let { it + (opf to it.getValue(opf).replace("<manifest>", "$padding<manifest>")) }
        assertFailsWith<TooLargeException> { ZipFile(epub(bigPackage)).use { readPackage(it, "t") } }

        val bigChapter = epubFiles(chapters = listOf("x")).let {
            it + ("OEBPS/c0.xhtml" to it.getValue("OEBPS/c0.xhtml").replace("<body>", "<body>" + " ".repeat(MAX_CHAPTER_BYTES.toInt())))
        }
        assertFailsWith<TooLargeException> { parseEpub(epub(bigChapter)) }
    }
}
