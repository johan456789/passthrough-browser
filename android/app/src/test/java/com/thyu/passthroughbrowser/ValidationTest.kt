package com.thyu.passthroughbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ValidationTest {
    @Test fun parsePortValid() {
        assertEquals(7157, parsePort("7157"))
        assertEquals(80, parsePort(" 80 "))
        assertEquals(1, parsePort("1"))
        assertEquals(65535, parsePort("65535"))
    }

    @Test fun parsePortInvalid() {
        for (s in listOf("", "0", "65536", "-1", "abc", "7157x", "99999999999")) {
            assertNull(s, parsePort(s))
        }
    }

    @Test fun forwardableUrls() {
        assertTrue(isForwardableUrl("https://example.com"))
        assertTrue(isForwardableUrl("HTTP://x.org/a?b=c"))
        for (s in listOf("javascript:alert(1)", "file:///x", "intent://x", "https://", "https://x/a b", "")) {
            assertFalse(s, isForwardableUrl(s))
        }
        assertFalse(isForwardableUrl("https://x/" + "a".repeat(MAX_URL_LENGTH)))
        // 3000 chars but 9000 UTF-8 bytes: over the byte limit the server enforces.
        assertFalse(isForwardableUrl("https://x/" + "例".repeat(3000)))
        assertTrue(isForwardableUrl("https://x/" + "例".repeat(2000)))
    }

    @Test fun jsonStrings() {
        assertEquals("\"a\\\"b\\\\c\"", jsonString("a\"b\\c"))
        assertEquals("\"a\\nb\\tc\"", jsonString("a\nb\tc"))
        assertEquals("\"\\u0001\"", jsonString("\u0001"))
        assertEquals("\"例え\"", jsonString("例え"))
    }

    @Test fun gatewaySelection() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertNull(selectGateway(emptyList()))
        assertNull(selectGateway(listOf(null, ip("0.0.0.0"), ip("127.0.0.1"), ip("fe80::1"), ip("8.8.8.8"))))
        val picked = selectGateway(
            listOf(null, ip("0.0.0.0"), ip("127.0.0.1"), ip("fe80::1"), ip("8.8.8.8"), ip("192.168.240.1")),
        )
        assertEquals("192.168.240.1", picked?.hostAddress)
    }
}
