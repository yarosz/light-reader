package com.yarosz.reader

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives the real HttpURLConnection against a local server. The server speaks plain http, so the
 * transport's https URLs are sent to it by path; the redirect rules only look at the Location header.
 */
class HttpsTransportTest {

    private class Canned(val status: Int, val location: String?, val body: String)

    /** Written by the test thread, read by the server's. */
    private val routes = ConcurrentHashMap<String, Canned>()
    private val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { socket.use(::answer) }
            }
        }
    }

    /** Answers one connection. A client that hangs up early fails only that connection, never the server. */
    private fun answer(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val path = reader.readLine().split(' ')[1]
        while (reader.readLine().orEmpty().isNotEmpty()) Unit
        requested += path
        val canned = routes[path] ?: Canned(404, null, "")
        val body = canned.body.toByteArray()
        val head = "HTTP/1.1 ${canned.status} X\r\n" + (canned.location?.let { l -> "Location: $l\r\n" } ?: "") +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    private val transport = HttpsTransport { url ->
        URL("http://127.0.0.1:${server.localPort}" + url.value.substringAfter("books.example.org")).openConnection() as HttpURLConnection
    }

    @AfterTest
    fun stop() = server.close()

    private fun serve(path: String, status: Int, location: String? = null, body: String = "") {
        routes[path] = Canned(status, location, body)
    }

    private fun url(path: String) = HttpsUrl.parse("https://books.example.org$path")!!

    @Test
    fun `a redirect within https is followed, and the response knows where it landed`() {
        serve("/a", 302, location = "/b")
        serve("/b", 200, body = "<feed/>")
        transport.get(url("/a")).use { response ->
            assertEquals(200, response.status)
            assertEquals(url("/b"), response.url)
            assertEquals("<feed/>", response.body.readBytes().decodeToString())
        }
    }

    @Test
    fun `a redirect to http is never followed, and fetching through it means no HTTPS`() {
        serve("/insecure", 301, location = "http://books.example.org/plain")
        serve("/plain", 200, body = "<feed/>")
        assertFailsWith<InsecureRedirectException> { transport.get(url("/insecure")) }
        assertEquals(Fetched.Failed(NoHttps), fetchPage(transport, url("/insecure")))
        assertEquals(listOf("/insecure", "/insecure"), requested.toList())
    }

    @Test
    fun `redirects stop after the cap, and one to another scheme is returned as it is`() {
        serve("/loop", 302, location = "/loop")
        serve("/ftp", 302, location = "ftp://books.example.org/b.epub")
        assertEquals(Fetched.Failed(HttpError(302)), fetchPage(transport, url("/loop")))
        assertEquals(MAX_REDIRECTS + 1, requested.size)
        assertEquals(Fetched.Failed(HttpError(302)), fetchPage(transport, url("/ftp")))
    }

    @Test
    fun `a timeout while connecting is a connect timeout, and one while reading stays a read timeout`() {
        class Stalled(private val connecting: Boolean) : HttpURLConnection(URL("https://books.example.org/")) {
            override fun connect() {
                if (connecting) throw SocketTimeoutException("failed to connect after 15000ms")
            }

            override fun getResponseCode(): Int = throw SocketTimeoutException("Read timed out")

            override fun disconnect() = Unit

            override fun usingProxy() = false
        }
        assertFailsWith<ConnectTimeoutException> { HttpsTransport { Stalled(connecting = true) }.get(url("/a")) }
        assertFailsWith<SocketTimeoutException> { HttpsTransport { Stalled(connecting = false) }.get(url("/a")) }
    }
}
