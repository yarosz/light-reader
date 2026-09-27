package com.yarosz.reader

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyProtectionTest {

    private val dir: File = createTempDirectory("copy-protection").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun protected(extra: Map<String, String>): Boolean =
        ZipFile(File(dir, "book.epub").writeEpub(epubFiles() + extra)).use(::isCopyProtected)

    private fun encryption(vararg uris: String, algorithm: String = "http://www.idpf.org/2008/embedding") =
        "META-INF/encryption.xml" to """<?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            ${uris.joinToString("") { """<enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/><enc:CipherData><enc:CipherReference URI="$it"/></enc:CipherData></enc:EncryptedData>""" }}
            </encryption>"""

    @Test
    fun `a plain EPUB and the checked-in Alice are not copy-protected`() {
        assertFalse(protected(emptyMap()))
        assertFalse(ZipFile(File("src/test/fixtures/alice.epub")).use(::isCopyProtected))
    }

    @Test
    fun `an Adobe, Apple, or Readium LCP rights file means copy-protected`() {
        assertTrue(protected(mapOf("META-INF/rights.xml" to "<rights/>")))
        assertTrue(protected(mapOf("META-INF/sinf.xml" to "<sinf/>")))
        assertTrue(protected(mapOf("META-INF/license.lcpl" to "{}")))
    }

    @Test
    fun `obfuscated fonts alone still open, whatever the algorithm or case`() {
        assertFalse(protected(mapOf(encryption("OEBPS/fonts/Body.ttf", "OEBPS/fonts/Title.OTF", "OEBPS/f/a.woff", "OEBPS/f/b.woff2"))))
        assertFalse(protected(mapOf(encryption("OEBPS/fonts/Body%20Italic.otf", algorithm = "http://ns.adobe.com/pdf/enc#RC"))))
        assertFalse(protected(mapOf("META-INF/encryption.xml" to """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>""")))
    }

    @Test
    fun `an encrypted document is copy-protected, even beside obfuscated fonts`() {
        assertTrue(protected(mapOf(encryption("OEBPS/c0.xhtml", algorithm = "http://www.w3.org/2001/04/xmlenc#aes128-cbc"))))
        assertTrue(protected(mapOf(encryption("OEBPS/fonts/Body.ttf", "OEBPS/images/cover.jpg"))))
    }

    @Test
    fun `an encryption file that doesn't parse counts as copy-protected`() {
        assertTrue(protected(mapOf("META-INF/encryption.xml" to "<encryption><unclosed>")))
    }
}
