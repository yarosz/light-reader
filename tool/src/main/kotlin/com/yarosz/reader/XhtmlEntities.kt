package com.yarosz.reader

import java.io.InputStream

/**
 * XHTML 1.0's named character entities (its lat1, symbol, and special sets: 253 names) and the code
 * point each names. An EPUB2 chapter uses them under an XHTML DOCTYPE whose DTD is never read, so no
 * parser knows them: [XhtmlEntityStream] rewrites each as its numeric reference. Generated from
 * Python's `html.entities.name2codepoint` (HTML 4.01's same 252) plus `apos`.
 */
internal val XHTML_ENTITIES: Map<String, Int> = (
    "quot 34 amp 38 apos 39 lt 60 gt 62 nbsp 160 iexcl 161 cent 162 pound 163 curren 164 yen 165 " +
    "brvbar 166 sect 167 uml 168 copy 169 ordf 170 laquo 171 not 172 shy 173 reg 174 macr 175 deg 176 " +
    "plusmn 177 sup2 178 sup3 179 acute 180 micro 181 para 182 middot 183 cedil 184 sup1 185 ordm 186 " +
    "raquo 187 frac14 188 frac12 189 frac34 190 iquest 191 Agrave 192 Aacute 193 Acirc 194 Atilde 195 " +
    "Auml 196 Aring 197 AElig 198 Ccedil 199 Egrave 200 Eacute 201 Ecirc 202 Euml 203 Igrave 204 " +
    "Iacute 205 Icirc 206 Iuml 207 ETH 208 Ntilde 209 Ograve 210 Oacute 211 Ocirc 212 Otilde 213 Ouml 214 " +
    "times 215 Oslash 216 Ugrave 217 Uacute 218 Ucirc 219 Uuml 220 Yacute 221 THORN 222 szlig 223 " +
    "agrave 224 aacute 225 acirc 226 atilde 227 auml 228 aring 229 aelig 230 ccedil 231 egrave 232 " +
    "eacute 233 ecirc 234 euml 235 igrave 236 iacute 237 icirc 238 iuml 239 eth 240 ntilde 241 ograve 242 " +
    "oacute 243 ocirc 244 otilde 245 ouml 246 divide 247 oslash 248 ugrave 249 uacute 250 ucirc 251 " +
    "uuml 252 yacute 253 thorn 254 yuml 255 OElig 338 oelig 339 Scaron 352 scaron 353 Yuml 376 fnof 402 " +
    "circ 710 tilde 732 Alpha 913 Beta 914 Gamma 915 Delta 916 Epsilon 917 Zeta 918 Eta 919 Theta 920 " +
    "Iota 921 Kappa 922 Lambda 923 Mu 924 Nu 925 Xi 926 Omicron 927 Pi 928 Rho 929 Sigma 931 Tau 932 " +
    "Upsilon 933 Phi 934 Chi 935 Psi 936 Omega 937 alpha 945 beta 946 gamma 947 delta 948 epsilon 949 " +
    "zeta 950 eta 951 theta 952 iota 953 kappa 954 lambda 955 mu 956 nu 957 xi 958 omicron 959 pi 960 " +
    "rho 961 sigmaf 962 sigma 963 tau 964 upsilon 965 phi 966 chi 967 psi 968 omega 969 thetasym 977 " +
    "upsih 978 piv 982 ensp 8194 emsp 8195 thinsp 8201 zwnj 8204 zwj 8205 lrm 8206 rlm 8207 ndash 8211 " +
    "mdash 8212 lsquo 8216 rsquo 8217 sbquo 8218 ldquo 8220 rdquo 8221 bdquo 8222 dagger 8224 Dagger 8225 " +
    "bull 8226 hellip 8230 permil 8240 prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250 oline 8254 " +
    "frasl 8260 euro 8364 image 8465 weierp 8472 real 8476 trade 8482 alefsym 8501 larr 8592 uarr 8593 " +
    "rarr 8594 darr 8595 harr 8596 crarr 8629 lArr 8656 uArr 8657 rArr 8658 dArr 8659 hArr 8660 " +
    "forall 8704 part 8706 exist 8707 empty 8709 nabla 8711 isin 8712 notin 8713 ni 8715 prod 8719 " +
    "sum 8721 minus 8722 lowast 8727 radic 8730 prop 8733 infin 8734 ang 8736 and 8743 or 8744 cap 8745 " +
    "cup 8746 int 8747 there4 8756 sim 8764 cong 8773 asymp 8776 ne 8800 equiv 8801 le 8804 ge 8805 " +
    "sub 8834 sup 8835 nsub 8836 sube 8838 supe 8839 oplus 8853 otimes 8855 perp 8869 sdot 8901 " +
    "lceil 8968 rceil 8969 lfloor 8970 rfloor 8971 lang 9001 rang 9002 loz 9674 spades 9824 clubs 9827 " +
    "hearts 9829 diams 9830"
    ).split(' ').chunked(2).associate { (name, code) -> name to code.toInt() }

/** The numeric reference each entity becomes. XML's own five are left out: every parser knows them. */
private val REWRITES: Map<String, ByteArray> = XHTML_ENTITIES
    .filterKeys { it !in setOf("amp", "lt", "gt", "quot", "apos") }
    .mapValues { (_, code) -> "&#$code;".toByteArray(Charsets.US_ASCII) }

private const val LONGEST_NAME = 8

/** The bytes an entity can span: '&', the name, ';'. */
private const val LOOKAHEAD = LONGEST_NAME + 2

private const val BLOCK = 8192

private const val AMP = '&'.code.toByte()
private const val SEMICOLON = ';'.code.toByte()
private val CDATA_START = "<![CDATA[".toByteArray(Charsets.US_ASCII)
private val CDATA_END = "]]>".toByteArray(Charsets.US_ASCII)

/**
 * [input] with each of [XHTML_ENTITIES] (`&nbsp;`) rewritten as its numeric reference (`&#160;`), so
 * it parses without the DTD that declares it. The JVM's parser reports such an entity as skipped,
 * but Android's Expat drops it silently, so the rewrite happens before either sees it. XML's own
 * five entities, unknown names, and CDATA sections pass through as they are. The rewrite is on
 * bytes, which is exact for UTF-8 and other ASCII-compatible encodings; a UTF-16 document (EPUB
 * allows one) passes through untouched.
 *
 * It rewrites a block at a time. An `&` too near the end of the input read so far waits for the next
 * read, so an entity split between two reads is still seen whole.
 */
internal class XhtmlEntityStream(private val input: InputStream) : InputStream() {
    private val inBuf = ByteArray(BLOCK)
    private var inPos = 0
    private var inEnd = 0
    private var eof = false

    /** A rewrite adds fewer than [LOOKAHEAD] bytes, so one always fits past [BLOCK]. */
    private val outBuf = ByteArray(BLOCK + LOOKAHEAD)
    private var outPos = 0
    private var outEnd = 0

    private var utf16: Boolean? = null
    private var inCdata = false

    /** How many bytes of [CDATA_START] (outside a CDATA section) or [CDATA_END] (inside) were just passed. */
    private var matched = 0

    override fun read(): Int {
        if (outPos == outEnd && !fill()) return -1
        return outBuf[outPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (outPos == outEnd && !fill()) return -1
        val n = minOf(len, outEnd - outPos)
        outBuf.copyInto(b, off, outPos, outPos + n)
        outPos += n
        return n
    }

    override fun close() = input.close()

    /** Moves the unread input to the front and reads more behind it; false when none is left. */
    private fun refill(): Boolean {
        if (!eof) {
            inBuf.copyInto(inBuf, 0, inPos, inEnd)
            inEnd -= inPos
            inPos = 0
            val n = input.read(inBuf, inEnd, inBuf.size - inEnd)
            if (n < 0) eof = true else inEnd += n
        }
        return inPos < inEnd
    }

    /** Rewrites the next block of input into [outBuf]; false at the end of the input. */
    private fun fill(): Boolean {
        outPos = 0
        outEnd = 0
        if (inPos == inEnd && !refill()) return false
        val utf16 = utf16 ?: startsUtf16().also { utf16 = it }
        if (utf16) {
            outEnd = inEnd - inPos
            inBuf.copyInto(outBuf, 0, inPos, inEnd)
            inPos = inEnd
            return true
        }
        while (outEnd < BLOCK) {
            if (inPos == inEnd && !refill()) break
            val c = inBuf[inPos]
            if (c != AMP || inCdata) {
                track(c)
                outBuf[outEnd++] = c
                inPos++
            } else if (inEnd - inPos < LOOKAHEAD && !eof) {
                if (outEnd > 0) break
                refill()
            } else {
                matched = 0
                val end = entityEnd(inPos)
                val rewrite = end?.let { REWRITES[String(inBuf, inPos + 1, it - inPos - 1, Charsets.US_ASCII)] }
                if (rewrite == null) {
                    outBuf[outEnd++] = c
                    inPos++
                } else {
                    rewrite.copyInto(outBuf, outEnd)
                    outEnd += rewrite.size
                    inPos = end + 1
                }
            }
        }
        return outEnd > 0
    }

    /** Where the ';' ending a name of 1 to [LONGEST_NAME] ASCII letters and digits after [amp] is, if one does. */
    private fun entityEnd(amp: Int): Int? {
        var end = amp + 1
        val limit = minOf(inEnd, amp + 1 + LONGEST_NAME)
        while (end < limit && inBuf[end].isNameByte()) end++
        return end.takeIf { it > amp + 1 && it < inEnd && inBuf[it] == SEMICOLON }
    }

    private fun Byte.isNameByte() = this in 'a'.code..'z'.code || this in 'A'.code..'Z'.code || this in '0'.code..'9'.code

    /** Follows whether the stream is inside a CDATA section, from the bytes passed. */
    private fun track(c: Byte) {
        val pattern = if (inCdata) CDATA_END else CDATA_START
        matched = when {
            c == pattern[matched] -> matched + 1
            c != pattern[0] -> 0
            inCdata -> 2 // a third ']' in a row: the last two can still end the section
            else -> 1
        }
        if (matched == pattern.size) {
            inCdata = !inCdata
            matched = 0
        }
    }

    private fun startsUtf16(): Boolean {
        while (inEnd - inPos < 2 && !eof) refill()
        val first = inBuf[inPos].toInt() and 0xFF
        val second = if (inEnd - inPos > 1) inBuf[inPos + 1].toInt() and 0xFF else -1
        return (first == 0xFE && second == 0xFF) || (first == 0xFF && second == 0xFE) ||
            (first == 0 && second == '<'.code) || (first == '<'.code && second == 0)
    }
}
