package com.yarosz.reader

import android.util.Log
import com.thelightphone.sdk.LightConnectivity
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import org.xml.sax.SAXException

private val HTTP_SCHEMES = setOf("http", "https")

/**
 * A URL the Tool may fetch: always https, because there is no cleartext path (the manifest forbids
 * it too), and always ASCII. [upgraded] marks a URL written as http:// and tried as https://, so that
 * failing to reach it can say the server has no HTTPS. It is context of the request, not part of the
 * URL: two HttpsUrls with the same [value] are equal.
 */
class HttpsUrl private constructor(val value: String, val upgraded: Boolean) {
    override fun equals(other: Any?) = other is HttpsUrl && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = value

    /**
     * This URL no longer marked [upgraded]: what a stored Catalogue keeps. Having been typed as
     * http:// describes the request that added it; a later request isn't an upgrade, and failing to
     * reach it is Unreachable, with Retry.
     */
    val plain: HttpsUrl get() = if (upgraded) HttpsUrl(value, upgraded = false) else this

    companion object {
        /**
         * [raw] resolved against [base] when relative, with http:// rewritten to https://. Null for
         * any other scheme (data:, mailto:, ftp:), a URL with no host, or one that doesn't parse. A
         * scheme is checked before parsing, so a large inline data: URL costs nothing.
         */
        fun parse(raw: String, base: HttpsUrl? = null): HttpsUrl? = try {
            val start = raw.trimStart()
            val schemeEnd = start.indexOfFirst { it == ':' || it == '/' || it == '?' || it == '#' }
            if (schemeEnd > 0 && start[schemeEnd] == ':' && start.substring(0, schemeEnd).lowercase() !in HTTP_SCHEMES) return null
            val spec = start.trimEnd().replace(" ", "%20")
            val uri = when {
                base == null -> URI(spec)
                spec.startsWith("?") -> URI(base.value.substringBefore('?').substringBefore('#') + spec)
                else -> URI(base.value).let { if (it.rawPath.isNullOrEmpty()) it.resolve("/") else it }.resolve(spec)
            }
            val scheme = uri.scheme?.lowercase()
            if (uri.host.isNullOrEmpty() || scheme !in HTTP_SCHEMES) null
            else HttpsUrl("https" + uri.toASCIIString().substring(uri.scheme.length), scheme == "http")
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Why a Catalogue page or its search couldn't be fetched. */
sealed interface FeedFailure

/** Why a Book couldn't be downloaded. */
sealed interface DownloadFailure

/** A failure of the request itself, the same for a feed and a Book. */
sealed interface NetworkFailure : FeedFailure, DownloadFailure

/** Offline, or the server can't be reached. */
data object Unreachable : NetworkFailure

/**
 * The server offers no HTTPS for this URL: it was written as http:// and its https:// version can't
 * be reached, or the server redirected to http://. The Tool never uses http.
 */
data object NoHttps : NetworkFailure

/** The server's TLS certificate isn't trusted: expired, self-signed, or issued for another name. */
data object UntrustedCertificate : NetworkFailure

/** The server answered with a status outside 2xx, such as 401 for a Catalogue that needs a login. */
data class HttpError(val status: Int) : NetworkFailure

/**
 * The address's host has no DNS record, as a mistyped address has. Offline, every lookup fails the
 * same way, so only an address the reader just typed, on a phone that reports a connection, reads as
 * this (see [feedFailureCopy]); anywhere else it reads as Unreachable. Downloads never tell it apart.
 */
data object NoSuchHost : FeedFailure

/** The response isn't an Atom feed (or an OpenSearch description with an Atom template). */
data object Unreadable : FeedFailure

/** The download isn't an EPUB the Tool can open. */
data object NotAnEpub : DownloadFailure

/** The download is a copy-protected EPUB (ADR 0005); it was deleted. */
data object CopyProtected : DownloadFailure

/** The Book couldn't be written to the phone's storage, usually because it is full. */
data object DiskError : DownloadFailure

sealed interface Fetched<out T> {
    data class Ok<T>(val value: T) : Fetched<T>
    data class Failed(val reason: FeedFailure) : Fetched<Nothing>
}

/** A GET's answer. [url] is where redirects ended, which relative links resolve against. */
class Response(
    val status: Int,
    val url: HttpsUrl,
    val length: Long?,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        body.close()
        onClose()
    }
}

/** The network, behind one call so tests can replace it. */
fun interface Transport {
    /** Starts a GET of [url]. Throws IOException when the server can't be reached. */
    fun get(url: HttpsUrl): Response
}

/** A redirect to http://, which the Tool never follows. */
class InsecureRedirectException(location: String) : IOException("redirected to $location")

/**
 * The Tool's version: `versionName` in lighttool.toml (a test keeps them equal). A constant, since
 * the Tool can't read it at run time: PackageManager needs a Context, which a Light Tool can't hold,
 * and the tool module generates no BuildConfig.
 */
const val VERSION_NAME = "0.3.0"

/** Sent on every request, so a server's logs can tell the Tool apart and link to its code. */
const val USER_AGENT = "Reader/$VERSION_NAME (+https://$REPO_URL)"

/** Redirects followed before the last 3xx is returned as it is. */
const val MAX_REDIRECTS = 5

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * The network over HttpURLConnection. It follows redirects itself, up to [MAX_REDIRECTS], and only
 * to https: one to http:// throws [InsecureRedirectException], and one to any other scheme is
 * returned as its 3xx. Every hop sends [USER_AGENT]. [open] exists so a test can point it at a local
 * server.
 */
class HttpsTransport(
    private val open: (HttpsUrl) -> HttpURLConnection = { URL(it.value).openConnection() as HttpURLConnection },
) : Transport {
    override fun get(url: HttpsUrl): Response {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            val connection = open(current)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                connection.connect()
                val status = connection.responseCode
                val next = if (status in REDIRECT_STATUSES && hop < MAX_REDIRECTS) redirectTarget(current, connection.getHeaderField("Location")) else null
                if (next != null) {
                    connection.disconnect()
                    current = next
                    continue
                }
                val body = if (status in 200..299) connection.inputStream else connection.errorStream ?: InputStream.nullInputStream()
                return Response(status, current, connection.contentLengthLong.takeIf { it >= 0 }, body, connection::disconnect)
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
        }
        error("unreachable: the last hop returns")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

/** Where a redirect from [from] to [location] leads: null when it can't be followed, and a throw when it leads to http. */
internal fun redirectTarget(from: HttpsUrl, location: String?): HttpsUrl? {
    val target = try {
        URI(from.value).resolve(location?.trim()?.replace(" ", "%20") ?: return null)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return when (target.scheme?.lowercase()) {
        "http" -> throw InsecureRedirectException(target.toASCIIString())
        "https" -> HttpsUrl.parse(target.toASCIIString())
        else -> null
    }
}

/**
 * Why a request failed to connect. A certificate that isn't trusted says so, on any URL. Failing to
 * connect to an upgraded URL means its server has no HTTPS: a refused connection, or a TLS failure
 * that isn't about the certificate (a plain-http server on port 443). So does a redirect to http, on
 * any URL. Every hop of a redirected request is judged by [url], the one first asked for: a refused
 * connection to the https:// server a typed http:// address redirected to still reads as NoHttps.
 *
 * A connect timeout is Unreachable, upgraded or not. A server that drops connections to port 443
 * times out like that, but so does a phone whose network has no internet, so a timeout could only
 * mean no HTTPS on a network Android has VALIDATED. The SDK's LightConnectivity reports only
 * NET_CAPABILITY_INTERNET, which a captive or dead network also has, and asking ConnectivityManager
 * needs a Context, which a Light Tool can't hold. Android's connect() includes the TLS handshake, so
 * a handshake that stalls is a connect timeout too. Any other failure, such as an unknown host, would
 * have failed over http too.
 */
fun unreachable(url: HttpsUrl, e: IOException): NetworkFailure = when {
    e is InsecureRedirectException -> NoHttps
    isCertificateFailure(e) -> UntrustedCertificate
    url.upgraded && (e is ConnectException || e is SSLException) -> NoHttps
    else -> Unreachable
}

/**
 * A TLS failure caused by the certificate: Android reports a name mismatch as
 * SSLPeerUnverifiedException, and an untrusted, expired, or mismatched certificate as an
 * SSLHandshakeException caused by a CertificateException or a certificate path failure. A handshake
 * failure without such a cause (a server that doesn't speak TLS) isn't one.
 */
private fun isCertificateFailure(e: IOException): Boolean =
    e is SSLPeerUnverifiedException || generateSequence<Throwable>(e) { it.cause }.take(8).any {
        it is CertificateException || it is CertPathValidatorException || it is CertPathBuilderException
    }

/**
 * Asks whether the phone reports an internet connection, answering null when it can't say (the
 * permission missing, say), so a caller never claims more than the phone did. The function holds
 * only this connectivity, not the screen that made it, since a download keeps it until it lands.
 */
fun LightConnectivity.reporter(): () -> Boolean? = {
    try {
        currentStatus.isConnected
    } catch (e: RuntimeException) {
        null
    }
}

private const val TAG = "Reader"

/** Logs why a Catalogue fetch failed; "Couldn't open" logs its reason the same way. */
internal fun logFeedFailure(line: String) {
    Log.w(TAG, line)
}

/**
 * This URL as a log may keep it: no userinfo, no fragment, and its query replaced by "?…", so a
 * search's terms and any credentials stay out of logcat. A [search]'s URL keeps only its host, the
 * rest shown as "/…" (or "?…" when only a query follows the host), since a search template may put
 * the terms in the path (Calibre's `/opds/search/{searchTerms}`).
 */
internal fun HttpsUrl.forLog(search: Boolean = false): String = urlForLog(value, search)

private fun urlForLog(url: String, search: Boolean): String {
    val bare = url.substringBefore('#')
    val rest = bare.substringAfter("://")
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
    val tail = rest.substring(authorityEnd)
    return bare.substringBefore("://") + "://" + rest.substring(0, authorityEnd).substringAfterLast('@') + when {
        search && tail.startsWith('?') -> "?…"
        search && tail.isNotEmpty() -> "/…"
        '?' in tail -> tail.substringBefore('?') + "?…"
        else -> tail
    }
}

/** [e] for a log line, each URL its message names (a redirect to http:// names its target) cut as [forLog] cuts one. */
private fun exceptionForLog(e: Exception, search: Boolean): String = URL_IN_TEXT.replace(e.toString()) { urlForLog(it.value, search) }

private val URL_IN_TEXT = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://\S*[^\s.,;:)\]"'>]""")

/**
 * Fetches a Catalogue page. Each failure is logged through [log], with the URL (as [forLog] cuts it)
 * and the status or exception behind it, since the copy the reader sees can't say which server
 * answered or how. [search] marks a search's results or their "More", whose URLs are logged without
 * their path.
 */
fun fetchPage(transport: Transport, url: HttpsUrl, log: (String) -> Unit = ::logFeedFailure, search: Boolean = false): Fetched<CataloguePage> =
    fetch(transport, url, log, search, ::parseFeed)

/** The search template from an OpenSearch description, the document a [CataloguePage.search] names; logs a failure as [fetchPage] does. */
fun fetchSearch(transport: Transport, description: HttpsUrl, log: (String) -> Unit = ::logFeedFailure): Fetched<SearchTemplate> =
    fetch(transport, description, log, search = false, ::parseOpenSearch)

private fun <T> fetch(transport: Transport, url: HttpsUrl, log: (String) -> Unit, search: Boolean, read: (InputStream, HttpsUrl) -> T?): Fetched<T> {
    fun failed(reason: FeedFailure, cause: String): Fetched.Failed {
        log("catalogue fetch failed: ${url.forLog(search)}: $cause -> $reason")
        return Fetched.Failed(reason)
    }
    val response = try {
        transport.get(url)
    } catch (e: UnknownHostException) {
        return failed(NoSuchHost, exceptionForLog(e, search))
    } catch (e: IOException) {
        return failed(unreachable(url, e), exceptionForLog(e, search))
    }
    return response.use {
        val at = if (it.url == url) "" else " at ${it.url.forLog(search)}"
        if (it.status !in 200..299) return failed(HttpError(it.status), "HTTP ${it.status}$at")
        try {
            read(it.body, it.url)?.let { value -> Fetched.Ok(value) } ?: failed(Unreadable, "not a feed$at")
        } catch (e: SAXException) {
            failed(Unreadable, exceptionForLog(e, search) + at)
        } catch (e: IOException) {
            failed(Unreachable, exceptionForLog(e, search) + at)
        }
    }
}
