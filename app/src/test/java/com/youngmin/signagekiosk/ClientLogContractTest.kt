package com.youngmin.signagekiosk

import com.youngmin.signagekiosk.MiniJson.Num
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientLogContractEndpointTest {

    @Test
    fun `endpoint keeps scheme host and explicit port and drops path query fragment`() {
        assertEquals(
            "http://192.168.0.254:13000/checkin/client-log",
            ClientLogContract.endpointFor("http://192.168.0.254:13000/#checkin"),
        )
        assertEquals(
            "https://signage.example.com/checkin/client-log",
            ClientLogContract.endpointFor("https://signage.example.com/viewer/index.html?x=1&y=2#frag"),
        )
    }

    @Test
    fun `explicit default port is kept`() {
        assertEquals("http://host:80/checkin/client-log", ClientLogContract.endpointFor("http://host:80/"))
    }

    @Test
    fun `scheme is lowercased and input trimmed`() {
        assertEquals("http://host:8080/checkin/client-log", ClientLogContract.endpointFor("  HTTP://host:8080/a \n"))
    }

    @Test
    fun `userinfo never reaches the endpoint`() {
        assertEquals("http://host:1/checkin/client-log", ClientLogContract.endpointFor("http://user:secret@host:1/x"))
    }

    @Test
    fun `non http or hostless or garbage input gives null`() {
        for (raw in listOf(
            "ftp://host/",
            "javascript:alert(1)",
            "file:///sdcard/x.html",
            "",
            "   ",
            "not a url",
            "http:///x",
            "http://",
            "/checkin",
        )) {
            assertNull("expected null for '$raw'", ClientLogContract.endpointFor(raw))
        }
    }
}

class ClientLogContractOriginTest {

    @Test
    fun `origin keeps scheme host and port only`() {
        assertEquals("http://192.168.0.254:13000", ClientLogContract.originOf("http://192.168.0.254:13000/#checkin"))
        assertEquals("https://signage.example.com", ClientLogContract.originOf("https://signage.example.com/a/b.html?x=1#f"))
        assertEquals("http://host", ClientLogContract.originOf("http://host"))
        assertEquals("http://[::1]:8080", ClientLogContract.originOf("http://[::1]:8080/x"))
    }

    @Test
    fun `origin drops userinfo lowercases the scheme and trims`() {
        assertEquals("http://host:8080", ClientLogContract.originOf("  HTTP://user:secret@host:8080/p?token=secret \n"))
    }

    @Test
    fun `origin has no trailing slash and endpoint is origin plus path`() {
        for (url in listOf("http://h:1/", "https://h/x/y?z#w", "http://u@h:9/")) {
            val origin = ClientLogContract.originOf(url)!!
            assertFalse(origin.endsWith("/"))
            assertEquals(origin + ClientLogContract.PATH, ClientLogContract.endpointFor(url))
        }
    }

    @Test
    fun `non http or hostless or garbage input gives null origin`() {
        for (raw in listOf("ftp://host/", "javascript:alert(1)", "file:///sdcard/x.html", "", "   ", "not a url", "http:///x", "http://", "/checkin")) {
            assertNull("expected null for '$raw'", ClientLogContract.originOf(raw))
        }
    }
}

class ClientLogContractRedactUrlTest {

    private fun assertNoSecrets(out: String?) {
        for (bad in listOf("token", "secret", "user", "pass", "@", "?")) {
            assertFalse("'$bad' leaked into $out", out!!.contains(bad))
        }
    }

    @Test
    fun `plain page url is kept including the hash route`() {
        assertEquals("http://192.168.0.254:13000/#checkin", ClientLogContract.redactUrl("http://192.168.0.254:13000/#checkin"))
    }

    @Test
    fun `userinfo and query are dropped while port path and fragment stay`() {
        val out = ClientLogContract.redactUrl("https://user:pass@host:8443/a/b?token=secret&x=1#frag")
        assertEquals("https://host:8443/a/b#frag", out)
        assertNoSecrets(out)
    }

    @Test
    fun `query without a path is dropped`() {
        val out = ClientLogContract.redactUrl("http://host?token=secret")
        assertEquals("http://host", out)
        assertNoSecrets(out)
    }

    @Test
    fun `userinfo without password is dropped`() {
        val out = ClientLogContract.redactUrl("http://user@host/p")
        assertEquals("http://host/p", out)
        assertNoSecrets(out)
    }

    @Test
    fun `no port means no colon after the host`() {
        assertEquals("http://host/p", ClientLogContract.redactUrl("http://host/p"))
    }

    @Test
    fun `input is trimmed`() {
        assertEquals("http://host/p", ClientLogContract.redactUrl("  http://host/p?token=secret \n"))
    }

    @Test
    fun `path keeps its percent encoding`() {
        assertEquals("http://host/a%20b", ClientLogContract.redactUrl("http://host/a%20b?token=secret"))
    }

    @Test
    fun `other schemes with a host are redacted the same way`() {
        val out = ClientLogContract.redactUrl("ftp://user:pass@host/x?token=secret")
        assertEquals("ftp://host/x", out)
        assertNoSecrets(out)
    }

    @Test
    fun `null unparsable and hostless input give null`() {
        for (raw in listOf(null, "", "   ", "not a url", "javascript:alert(1)", "about:blank", "file:///sdcard/x", "http:///x", "/relative?token=secret")) {
            assertNull("expected null for '$raw'", ClientLogContract.redactUrl(raw))
        }
    }

    // Regression test: a query inside a hash-SPA route leaked verbatim
    // (redactUrl("http://h:1/#checkin?token=secret") returned the token, round-2 finding, ClientLogContract.kt fragment).
    @Test
    fun `query inside the fragment is dropped Fix_hash_route_token_leak`() {
        val out = ClientLogContract.redactUrl("http://h:1/#checkin?token=secret")
        assertEquals("http://h:1/#checkin", out)
        assertNoSecrets(out)
    }

    @Test
    fun `fragment that is only a query leaves a bare hash`() {
        assertEquals("http://h:1/#", ClientLogContract.redactUrl("http://h:1/#?x=1"))
    }

    @Test
    fun `query in the url and in the fragment are both dropped`() {
        val out = ClientLogContract.redactUrl("http://user:pass@h:1/p?token=secret#checkin?token=secret&y=2")
        assertEquals("http://h:1/p#checkin", out)
        assertNoSecrets(out)
    }

    @Test
    fun `unparsable url with credentials is not echoed back`() {
        // A space makes java.net.URI throw: the raw text (with its secrets) must not be logged.
        assertNull(ClientLogContract.redactUrl("http://user:pass@host/a b?token=secret"))
    }
}

class ClientLogContractBodyTest {

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Pair<Map<String, Any?>, Map<String, Any?>> {
        val root = MiniJson.parse(body) as Map<String, Any?>
        assertEquals(listOf("event", "data"), root.keys.toList())
        return root to (root["data"] as Map<String, Any?>)
    }

    @Test
    fun `simple body matches the server contract exactly`() {
        val body = ClientLogContract.body(
            "app_start",
            "1.2",
            linkedMapOf("n" to 5, "ok" to true, "z" to null, "s" to "x"),
        )
        assertEquals(
            "{\"event\":\"watchdog\",\"data\":{\"kind\":\"app_start\",\"app\":\"1.2\",\"n\":5,\"ok\":true,\"z\":null,\"s\":\"x\"}}",
            body,
        )
        val (root, _) = parse(body)
        assertEquals("watchdog", root["event"])
    }

    @Test
    fun `kind and app come first in data`() {
        val (_, data) = parse(ClientLogContract.body("app_x", "9", linkedMapOf("a" to 1, "b" to 2)))
        assertEquals(listOf("kind", "app", "a", "b"), data.keys.toList())
        assertEquals("app_x", data["kind"])
        assertEquals("9", data["app"])
    }

    @Test
    fun `duplicate kind and app in fields are skipped`() {
        val (_, data) = parse(ClientLogContract.body("app_x", "9", linkedMapOf("kind" to "evil", "app" to "evil", "a" to 1)))
        assertEquals(listOf("kind", "app", "a"), data.keys.toList())
        assertEquals("app_x", data["kind"])
        assertEquals("9", data["app"])
    }

    @Test
    fun `invalid keys are skipped`() {
        val ok32 = "a" + "b".repeat(31)
        val fields = linkedMapOf<String, Any?>(
            "1abc" to 1, "" to 1, "a-b" to 1, "a b" to 1, "a\"b" to 1, "é" to 1,
            "a" + "b".repeat(32) to 1, // 33 chars
            "_x" to 1, "A9_z" to 1, ok32 to 1,
        )
        val (_, data) = parse(ClientLogContract.body("k", "v", fields))
        assertEquals(listOf("kind", "app", "_x", "A9_z", ok32), data.keys.toList())
    }

    @Test
    fun `total fields are capped at 20`() {
        val fields = LinkedHashMap<String, Any?>()
        for (n in 0 until 30) fields["f$n"] = n
        val (_, data) = parse(ClientLogContract.body("k", "v", fields))
        assertEquals(ClientLogContract.MAX_FIELDS, data.size)
        assertEquals(listOf("kind", "app") + (0 until 18).map { "f$it" }, data.keys.toList())
    }

    @Test
    fun `skipped keys do not consume the field cap`() {
        val fields = LinkedHashMap<String, Any?>()
        for (n in 0 until 10) fields["bad-$n"] = n
        fields["kind"] = "dup"
        for (n in 0 until 18) fields["f$n"] = n
        val (_, data) = parse(ClientLogContract.body("k", "v", fields))
        assertEquals(20, data.size)
        assertEquals("f17", data.keys.last())
    }

    @Test
    fun `non finite numbers become null`() {
        val fields = linkedMapOf<String, Any?>(
            "a" to Double.NaN, "b" to Double.POSITIVE_INFINITY, "c" to Double.NEGATIVE_INFINITY,
            "d" to Float.NaN, "e" to Float.POSITIVE_INFINITY, "f" to Float.NEGATIVE_INFINITY,
        )
        val (_, data) = parse(ClientLogContract.body("k", "v", fields))
        for (key in listOf("a", "b", "c", "d", "e", "f")) {
            assertTrue("$key present", data.containsKey(key))
            assertNull("$key should be null", data[key])
        }
    }

    @Test
    fun `finite numbers are valid JSON numbers with their value`() {
        val fields = linkedMapOf<String, Any?>(
            "d" to 1.5, "neg" to -0.25, "big" to 1e20, "tiny" to 1e-7, "fl" to 2.5f,
            "l" to Long.MAX_VALUE, "i" to Int.MIN_VALUE, "s" to 7.toShort(), "b" to 3.toByte(),
        )
        val (_, data) = parse(ClientLogContract.body("k", "v", fields))
        assertEquals(1.5, (data["d"] as Num).toDouble(), 0.0)
        assertEquals(-0.25, (data["neg"] as Num).toDouble(), 0.0)
        assertEquals(1e20, (data["big"] as Num).toDouble(), 0.0)
        assertEquals(1e-7, (data["tiny"] as Num).toDouble(), 0.0)
        assertEquals(2.5, (data["fl"] as Num).toDouble(), 0.0)
        assertEquals(Long.MAX_VALUE.toString(), (data["l"] as Num).raw)
        assertEquals(Int.MIN_VALUE.toString(), (data["i"] as Num).raw)
        assertEquals("7", (data["s"] as Num).raw)
        assertEquals("3", (data["b"] as Num).raw)
    }

    @Test
    fun `booleans and null are JSON literals`() {
        val (_, data) = parse(ClientLogContract.body("k", "v", linkedMapOf("t" to true, "f" to false, "n" to null)))
        assertEquals(true, data["t"])
        assertEquals(false, data["f"])
        assertTrue(data.containsKey("n"))
        assertNull(data["n"])
    }

    @Test
    fun `other values are sent as their string form`() {
        val (_, data) = parse(ClientLogContract.body("k", "v", linkedMapOf("list" to listOf(1, 2), "c" to 'x')))
        assertEquals("[1, 2]", data["list"])
        assertEquals("x", data["c"])
    }

    @Test
    fun `long strings are cut to 300 chars`() {
        val (_, data) = parse(ClientLogContract.body("k", "v", linkedMapOf("s" to "a".repeat(500), "e" to "b".repeat(300))))
        assertEquals("a".repeat(300), data["s"])
        assertEquals("b".repeat(300), data["e"])
    }

    @Test
    fun `kind and app values are cut too`() {
        val (_, data) = parse(ClientLogContract.body("k".repeat(400), "v".repeat(301), emptyMap()))
        assertEquals(300, (data["kind"] as String).length)
        assertEquals(300, (data["app"] as String).length)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "😀"
        // High surrogate at index 299: the pair would straddle the cut -> drop the whole pair.
        val straddle = "a".repeat(299) + emoji + "zzz"
        assertEquals("a".repeat(299), ClientLogContract.truncate(straddle))
        // Pair at 298..299 fits exactly -> kept.
        val fits = "a".repeat(298) + emoji + "zzz"
        assertEquals("a".repeat(298) + emoji, ClientLogContract.truncate(fits))
        val (_, data) = parse(ClientLogContract.body("k", "v", linkedMapOf("s" to straddle)))
        assertEquals("a".repeat(299), data["s"])
    }

    @Test
    fun `short strings are not touched by truncate`() {
        assertEquals("", ClientLogContract.truncate(""))
        val s = "x".repeat(300)
        assertEquals(s, ClientLogContract.truncate(s))
    }

    @Test
    fun `quotes backslashes and control chars are escaped and round trip`() {
        val nasty = "q\"b\\s/n\nr\rt\tz\u0000o\u0001u\u001f end ế 😀"
        val body = ClientLogContract.body("k", "v", linkedMapOf("s" to nasty))
        // No raw control character may appear in the body.
        assertTrue(body.none { it < ' ' })
        assertTrue(body.contains("\\u0000") && body.contains("\\u0001") && body.contains("\\u001f"))
        assertTrue(body.contains("\\n") && body.contains("\\r") && body.contains("\\t"))
        val (_, data) = parse(body)
        assertEquals(nasty, data["s"])
    }

    @Test
    fun `quote escapes exactly`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0007\"", ClientLogContract.quote("a\"b\\c\n\u0007"))
    }

    @Test
    fun `worst case escaped body still parses`() {
        val fields = LinkedHashMap<String, Any?>()
        for (n in 0 until 18) fields["f$n"] = "\"\\\n".repeat(200)
        val body = ClientLogContract.body("k", "v", fields)
        val (_, data) = parse(body)
        assertEquals(20, data.size)
        assertEquals(300, (data["f0"] as String).length)
        // Escaping can push the body past the server's 8 KiB cap; RemoteLogger must check it (it does, by length).
        assertTrue(body.length > ClientLogContract.MAX_BODY_CHARS)
    }
}
