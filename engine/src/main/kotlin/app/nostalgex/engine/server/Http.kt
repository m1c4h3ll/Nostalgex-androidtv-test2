package app.nostalgex.engine.server

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The one thing the server clients need from the network. Tests swap in a fake. */
interface Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 15_000,
    ): HttpResponse

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000) =
        request("GET", url, headers, null, timeoutMs)

    fun post(url: String, headers: Map<String, String> = emptyMap(), body: String? = null, timeoutMs: Int = 15_000) =
        request("POST", url, headers, body, timeoutMs)
}

class HttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** Plain HttpURLConnection: on the JVM for tests and on Android for the app. */
class JavaHttp : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 10_000)
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { readUtf8Lenient(it) } ?: ""
            return HttpResponse(status, text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * Reads a body as UTF-8, replacing a malformed byte rather than failing: Plex can send a
         * title with a stray non-UTF-8 byte (upstream `nostalgex-utf8-repair.js`), and one bad
         * byte must not lose a whole page of the library.
         */
        fun readUtf8Lenient(input: InputStream): String {
            val bytes = input.readBytes()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
}

class ServerException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Base URL without a trailing slash. */
fun trimBase(url: String): String = url.trim().trimEnd('/')
