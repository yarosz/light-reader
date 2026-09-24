# DRM-free EPUB only

The Tool opens DRM-free EPUBs only. A copy-protected file is detected and explained ("This Book is
copy-protected and can't be opened here", with a pointer to DRM-free sources) instead of failing as a parse
error. The Tool will never attempt to remove or bypass DRM. Kobo `.kepub.epub` files are ordinary EPUBs
and open.

Detection: a Book is copy-protected if `META-INF/rights.xml` (Adobe), `META-INF/sinf.xml` (Apple
FairPlay) or `META-INF/license.lcpl` (Readium LCP) exists, or if `META-INF/encryption.xml` has an
`EncryptedData` whose `CipherReference` is not a font resource. Font obfuscation alone (algorithms
`http://www.idpf.org/2008/embedding` or `http://ns.adobe.com/pdf/enc#RC` on fonts) is common in DRM-free
EPUBs and must still open. The Tool ignores embedded fonts anyway, so obfuscated fonts need no code.
User-facing copy says "copy-protected", never "DRM".

This gives up most commercial ebooks (Kindle, Apple Books, Libby, and most store EPUBs are DRM-protected),
which will surprise people arriving from Kindle. The README and the listing say "DRM-free EPUB" in their
first sentence.
