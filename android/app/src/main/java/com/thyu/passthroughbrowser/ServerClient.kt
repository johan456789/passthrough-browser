package com.thyu.passthroughbrowser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

sealed class CallResult {
    /** Any HTTP response. */
    data class Http(val status: Int, val body: String) : CallResult()

    /** Connect / IO / timeout failure. */
    data class Failure(val error: Exception) : CallResult()
}

/**
 * Talks to the host server. Only connects to hosts accepted by [allowHost]
 * (private IPv4 by default). Blocking: MUST NOT be called on the main thread.
 */
class ServerClient(
    private val host: InetAddress,
    private val port: Int,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 3000,
    private val allowHost: (InetAddress) -> Boolean = ::isAllowedServerHost,
) {
    fun baseUrl(): String = "http://${host.hostAddress}:$port"

    fun health(): CallResult = call("GET", "/health", null)

    fun open(url: String): CallResult = call("POST", "/open", """{"url":${jsonString(url)}}""")

    private fun call(method: String, path: String, json: String?): CallResult {
        if (!allowHost(host) || port !in 1..65535) {
            return CallResult.Failure(IllegalArgumentException("refusing non-private host"))
        }
        var conn: HttpURLConnection? = null
        try {
            conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            if (json != null) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status < 400) conn.inputStream else conn.errorStream
            return CallResult.Http(status, readCapped(stream))
        } catch (e: IOException) {
            return CallResult.Failure(e)
        } catch (e: RuntimeException) {
            return CallResult.Failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCapped(stream: InputStream?): String {
        if (stream == null) return ""
        stream.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < MAX_BODY) {
                val n = s.read(buf, 0, minOf(buf.size, MAX_BODY - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    private companion object {
        const val MAX_BODY = 64 * 1024
    }
}
