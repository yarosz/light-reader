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

    private fun protected(extra: Map<String, String>, manifest: String = ""): Boolean =
        ZipFile(File(dir, "book.epub").writeEpub(epubFiles(extraManifest = manifest) + extra)).use(::isCopyProtected)

    private fun encryption(vararg uris: String, algorithm: String = "http://www.idpf.org/2008/embedding", keyInfo: String = "") =
        "META-INF/encryption.xml" to """<?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            ${uris.joinToString("") { """<enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>$keyInfo<enc:CipherData><enc:CipherReference URI="$it"/></enc:CipherData></enc:EncryptedData>""" }}
            </encryption>"""

    private val aes = "http://www.w3.org/2001/04/xmlenc#aes128-cbc"

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
        assertTrue(protected(mapOf(encryption("OEBPS/c0.xhtml", algorithm = aes))))
        val both = """<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><enc:CipherData><enc:CipherReference URI="OEBPS/fonts/Body.ttf"/></enc:CipherData></enc:EncryptedData>
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="$aes"/><enc:CipherData><enc:CipherReference URI="OEBPS/images/cover.jpg"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""
        assertTrue(protected(mapOf("META-INF/encryption.xml" to both)))
    }

    @Test
    fun `a font obfuscation algorithm means a font, whatever the file is called`() {
        assertFalse(protected(mapOf(encryption("OEBPS/fonts/Body", "OEBPS/f/Title.bin"))))
        assertFalse(protected(mapOf(encryption("OEBPS/f/Body.dat", algorithm = "http://ns.adobe.com/pdf/enc#RC"))))
    }

    @Test
    fun `a file the manifest calls a font is a font, whatever its extension or algorithm`() {
        listOf("font/otf", "font/woff2", "application/font-sfnt", "application/vnd.ms-opentype", "application/x-font-ttf", "FONT/TTF").forEach { type ->
            val manifest = """<item id="f" href="fonts/Body+Bold%20Italic" media-type="$type"/>"""
            assertFalse(protected(mapOf(encryption("OEBPS/fonts/Body+Bold%20Italic", algorithm = aes)), manifest), type)
        }
        val image = """<item id="i" href="images/cover" media-type="image/jpeg"/>"""
        assertTrue(protected(mapOf(encryption("OEBPS/images/cover", algorithm = aes)), image))
    }

    @Test
    fun `the extension is the last signal, and only the data's own algorithm counts, not its key's`() {
        assertFalse(protected(mapOf(encryption("OEBPS/fonts/Body.otf", algorithm = aes))))
        assertTrue(protected(mapOf(encryption("OEBPS/images/cover.jpg", algorithm = aes))))
        val key = """<ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#"><enc:EncryptedKey><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/></enc:EncryptedKey></ds:KeyInfo>"""
        assertTrue(protected(mapOf(encryption("OEBPS/c0.xhtml", algorithm = aes, keyInfo = key))))
    }

    @Test
    fun `an encryption file that doesn't parse counts as copy-protected`() {
        assertTrue(protected(mapOf("META-INF/encryption.xml" to "<encryption><unclosed>")))
    }
}
