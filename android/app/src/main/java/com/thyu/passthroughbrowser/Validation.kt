package com.thyu.passthroughbrowser

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

const val DEFAULT_PORT = 7157
const val FALLBACK_HOST = "192.168.240.1"
const val MAX_URL_LENGTH = 8192
const val SERVER_NAME = "passthrough-browser"

/** Returns the port if [s] is a decimal integer 1..65535 (surrounding whitespace trimmed), else null. */
fun parsePort(s: String): Int? {
    val t = s.trim()
    if (t.isEmpty() || t.length > 5 || !t.all { it in '0'..'9' }) return null
    val n = t.toInt()
    return if (n in 1..65535) n else null
}

/**
 * True if [url] starts (case-insensitively) with http:// or https://, has a non-empty host,
 * has length 1..MAX_URL_LENGTH and contains no chars <= 0x20 or 0x7f. Mirrors the server check;
 * the server stays authoritative.
 */
fun isForwardableUrl(url: String): Boolean {
    // Byte length, like the server's len(raw); a BMP-only char count would undercount non-ASCII.
    if (url.isEmpty() || url.toByteArray(Charsets.UTF_8).size > MAX_URL_LENGTH) return false
    if (url.any { it.code <= 0x20 || it.code == 0x7f }) return false
    val rest = when {
        url.startsWith("http://", ignoreCase = true) -> url.substring(7)
        url.startsWith("https://", ignoreCase = true) -> url.substring(8)
        else -> return false
    }
    val host = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }
    if (!host.isNullOrEmpty()) return true
    // URI could not extract a host (e.g. IDN or odd characters): accept when something
    // other than a path follows "://".
    return rest.isNotEmpty() && rest[0] != '/'
}

/** JSON string literal with quotes, escaping \" \\ \b \f \n \r \t and other < 0x20 as \u00XX. */
fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/** First candidate that is a non-null Inet4Address, not any-local, not loopback, and site-local. */
fun selectGateway(candidates: List<InetAddress?>): Inet4Address? =
    candidates.firstOrNull {
        it is Inet4Address && !it.isAnyLocalAddress && !it.isLoopbackAddress && it.isSiteLocalAddress
    } as Inet4Address?

/** True if [addr] is acceptable as server host (Inet4Address && isSiteLocalAddress). */
fun isAllowedServerHost(addr: InetAddress): Boolean =
    addr is Inet4Address && addr.isSiteLocalAddress
