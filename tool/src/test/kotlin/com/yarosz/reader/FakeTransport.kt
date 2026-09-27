package com.yarosz.reader

import java.io.IOException
import java.io.InputStream

/** One canned answer: a status and body, or a failure to connect. [landsAt] is where redirects ended. */
class Answer(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val length: Long? = body.size.toLong(),
    val landsAt: String? = null,
    val connectFailure: IOException? = null,
    val readFailureAfter: Int? = null,
)

/** Answers GETs from [answers] by URL; an unknown URL can't be reached. Records what was asked and closed. */
class FakeTransport(private val answers: Map<String, Answer>) : Transport {
    val asked = mutableListOf<HttpsUrl>()
    var closed = 0

    override fun get(url: HttpsUrl): Response {
        asked += url
        val answer = answers[url.value] ?: throw java.net.UnknownHostException(url.value)
        answer.connectFailure?.let { throw it }
        val body = answer.body.inputStream().let { stream -> answer.readFailureAfter?.let { FailingStream(stream, it) } ?: stream }
        return Response(answer.status, answer.landsAt?.let { HttpsUrl.parse(it) } ?: url, answer.length, body) { closed++ }
    }
}

/** Reads [limit] bytes of [inner], then fails as a dropped connection does. */
private class FailingStream(private val inner: InputStream, private var limit: Int) : InputStream() {
    override fun read(): Int = if (limit-- <= 0) throw IOException("connection reset") else inner.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) throw IOException("connection reset")
        val n = inner.read(b, off, minOf(len, limit))
        if (n > 0) limit -= n
        return n
    }
}
