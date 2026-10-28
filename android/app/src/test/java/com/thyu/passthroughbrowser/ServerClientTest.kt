package com.thyu.passthroughbrowser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The spec suggests the JDK's com.sun.net.httpserver.HttpServer, but it cannot be used here:
 * AGP compiles unit tests (both kotlinc with -no-jdk and javac, verified) against android.jar
 * only, so the jdk.httpserver module does not resolve (tried a Java wrapper and
 * -Xadd-modules). The fake server is therefore a minimal HTTP/1.1 responder on a ServerSocket.
 */
class ServerClientTest {
    private lateinit var listener: ServerSocket
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Volatile private var lastRequestLine = ""
    @Volatile private var lastContentType: String? = null
    @Volatile private var lastBody = ""
    @Volatile private var responseStatus = 200
    @Volatile private var responseBody = """{"ok":true}"""
    @Volatile private var sleepMs = 0L

    @Before fun start() {
        listener = ServerSocket(0, 5, loopback)
        Thread {
            while (true) {
                val s = try {
                    listener.accept()
                } catch (e: SocketException) {
                    return@Thread
                }
                serve(s)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @After fun stop() {
        listener.close()
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun serve(s: Socket) {
        try {
            s.use {
                val input = it.getInputStream()
                lastRequestLine = readLine(input) ?: return
                var length = 0
                lastContentType = null
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val p = h.split(":", limit = 2)
                    val k = p[0].lowercase()
                    val v = p.getOrElse(1) { "" }.trim()
                    if (k == "content-length") length = v.toInt()
                    if (k == "content-type") lastContentType = v
                }
                val body = ByteArrayOutputStream()
                for (n in 0 until length) {
                    val b = input.read()
                    if (b < 0) break
                    body.write(b)
                }
                lastBody = body.toString("UTF-8")
                if (sleepMs > 0) Thread.sleep(sleepMs)
                val payload = responseBody.toByteArray()
                val head = "HTTP/1.1 $responseStatus X\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                it.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
            }
        } catch (e: IOException) {
            // client went away (timeout test)
        }
    }

    private fun client(readTimeoutMs: Int = 3000) =
        ServerClient(loopback, listener.localPort, readTimeoutMs = readTimeoutMs, allowHost = { true })

    @Test fun openPostsJson() {
        val r = client().open("https://example.com/?a=1&b=\"x\"")
        assertEquals(CallResult.Http(200, """{"ok":true}"""), r)
        assertEquals("POST /open HTTP/1.1", lastRequestLine)
        assertEquals("application/json; charset=utf-8", lastContentType)
        assertEquals("""{"url":"https://example.com/?a=1&b=\"x\""}""", lastBody)
    }

    @Test fun errorBodyIsRead() {
        responseStatus = 422
        responseBody = """{"ok":false,"error":"invalid_url","message":"nope"}"""
        assertEquals(CallResult.Http(422, responseBody), client().open("https://x"))
    }

    @Test fun healthGet() {
        responseBody = """{"ok":true,"name":"passthrough-browser"}"""
        assertEquals(CallResult.Http(200, responseBody), client().health())
        assertEquals("GET /health HTTP/1.1", lastRequestLine)
    }

    @Test fun closedPortFails() {
        // Bind an ephemeral port, then close it: the kernel picked a free port and nothing
        // listens on it afterwards, so a loopback connect gets an immediate RST (refused).
        // The only flake window is another process grabbing that exact port in between.
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val r = ServerClient(loopback, port, allowHost = { true }).health()
        assertTrue(r.toString(), r is CallResult.Failure && r.error is ConnectException)
    }

    @Test fun defaultAllowHostRejectsNonPrivate() {
        for (h in listOf("127.0.0.1", "8.8.8.8")) {
            val r = ServerClient(InetAddress.getByName(h), 7157).open("https://x")
            assertTrue(r.toString(), r is CallResult.Failure && r.error is IllegalArgumentException)
        }
    }

    @Test fun readTimeout() {
        sleepMs = 1000
        val r = client(readTimeoutMs = 200).health()
        assertTrue(r.toString(), r is CallResult.Failure && r.error is SocketTimeoutException)
    }
}
