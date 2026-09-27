package com.yarosz.reader

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import javax.net.ssl.SSLException
import org.xml.sax.SAXException

/**
 * A URL the Tool may fetch: always https, because there is no cleartext path (the manifest forbids
 * it too). [upgraded] marks a URL written as http:// and tried as https://, so that failing to reach
 * it can say the server has no HTTPS.
 */
class HttpsUrl private constructor(val value: String, val upgraded: Boolean) {
    override fun equals(other: Any?) = other is HttpsUrl && other.value == value && other.upgraded == upgraded
    override fun hashCode() = value.hashCode() * 31 + upgraded.hashCode()
    override fun toString() = value

    companion object {
        /**
         * [raw] resolved against [base] when relative, with http:// rewritten to https://. Null for
         * any other scheme (data:, mailto:, ftp:), a URL with no host, or one that doesn't parse.
         */
        fun parse(raw: String, base: HttpsUrl? = null): HttpsUrl? = try {
            val spec = raw.trim().replace(" ", "%20")
            val uri = when {
                base == null -> URI(spec)
                spec.startsWith("?") -> URI(base.value.substringBefore('?').substringBefore('#') + spec)
                else -> URI(base.value).let { if (it.rawPath.isNullOrEmpty()) it.resolve("/") else it }.resolve(spec)
            }
            val scheme = uri.scheme?.lowercase()
            if (uri.host.isNullOrEmpty() || scheme !in setOf("http", "https")) null
            else HttpsUrl("https" + uri.toString().substring(uri.scheme.length), scheme == "http")
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

/** The URL was written as http://, and its https:// version can't be reached; the Tool never uses http. */
data object NoHttps : NetworkFailure

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

object HttpsTransport : Transport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** HttpURLConnection follows a redirect only within one scheme, so https never lands on http. */
    override fun get(url: HttpsUrl): Response {
        val connection = URL(url.value).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream else connection.errorStream ?: InputStream.nullInputStream()
            return Response(
                status = status,
                url = HttpsUrl.parse(connection.url.toString()) ?: url,
                length = connection.contentLengthLong.takeIf { it >= 0 },
                body = body,
                onClose = connection::disconnect,
            )
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}

/**
 * Failing to connect to an upgraded URL means its server has no HTTPS: a refused connection or a
 * failed TLS handshake. Any other failure, such as an unknown host, would have failed over http too.
 */
fun unreachable(url: HttpsUrl, e: IOException): NetworkFailure =
    if (url.upgraded && (e is ConnectException || e is SSLException)) NoHttps else Unreachable

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
