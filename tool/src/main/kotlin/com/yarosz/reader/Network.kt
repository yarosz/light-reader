package com.yarosz.reader

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
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

/** Redirects followed before the last 3xx is returned as it is. */
const val MAX_REDIRECTS = 5

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * The network over HttpURLConnection. It follows redirects itself, up to [MAX_REDIRECTS], and only
 * to https: one to http:// throws [InsecureRedirectException], and one to any other scheme is
 * returned as its 3xx. [open] exists so a test can point it at a local server.
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
            try {
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
 * connect to an upgraded URL means its server has no HTTPS: a refused connection or a TLS failure
 * that isn't about the certificate (a plain-http server on port 443). So does a redirect to http.
 * Any other failure, such as an unknown host, would have failed over http too.
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

fun fetchPage(transport: Transport, url: HttpsUrl): Fetched<CataloguePage> = fetch(transport, url, ::parseFeed)

/** The search template from an OpenSearch description, the document a [CataloguePage.search] names. */
fun fetchSearch(transport: Transport, description: HttpsUrl): Fetched<SearchTemplate> = fetch(transport, description, ::parseOpenSearch)

private fun <T> fetch(transport: Transport, url: HttpsUrl, read: (InputStream, HttpsUrl) -> T?): Fetched<T> {
    val response = try {
        transport.get(url)
    } catch (e: IOException) {
        return Fetched.Failed(unreachable(url, e))
    }
    return response.use {
        if (it.status !in 200..299) return Fetched.Failed(HttpError(it.status))
        try {
            read(it.body, it.url)?.let { value -> Fetched.Ok(value) } ?: Fetched.Failed(Unreadable)
        } catch (e: SAXException) {
            Fetched.Failed(Unreadable)
        } catch (e: IOException) {
            Fetched.Failed(Unreachable)
        }
    }
}
