package com.youngmin.signagekiosk

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Client side of the server's remote-log contract (SPEC §3, owned by
 * signage_nodejs_server/server/utils/clientLog.js). Pure Kotlin so it runs in JVM unit tests.
 */
object ClientLogContract {
    const val PATH = "/checkin/client-log"
    const val EVENT = "watchdog"
    const val MAX_FIELDS = 20
    const val MAX_STRING = 300
    /** Server rejects bodies longer than this (JS string length). */
    const val MAX_BODY_CHARS = 8 * 1024

    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")

    /** `<scheme>://<host>[:port]/checkin/client-log` from the page URL, or null if it is not http/https. */
    fun endpointFor(pageUrl: String): String? {
        val uri = try {
            URI(pageUrl.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host
        if (host.isNullOrEmpty()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port$PATH"
    }

    /**
     * URL safe to log: scheme, host, port, path and fragment only — userinfo and query, including a query
     * inside the fragment (which may carry credentials such as `?token=`), are dropped. Null when it does
     * not parse.
     */
    fun redactUrl(url: String?): String? {
        if (url == null) return null
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty()
        // Hash-SPA routes can carry their own query (`#checkin?token=…`): keep only the route.
        val fragment = uri.rawFragment?.let { "#" + it.substringBefore('?') }.orEmpty()
        return "$scheme://$host$port$path$fragment"
    }

    /**
     * JSON body. `kind` and `app` come first; extra fields with invalid keys or duplicates are skipped and the
     * total is capped at [MAX_FIELDS]. Values: null, Boolean, finite numbers (NaN/Infinity → null); anything
     * else is sent as a string cut to [MAX_STRING] chars.
     */
    fun body(kind: String, app: String, fields: Map<String, Any?>): String {
        val data = LinkedHashMap<String, Any?>()
        data["kind"] = kind
        data["app"] = app
        for ((k, v) in fields) {
            if (data.size >= MAX_FIELDS) break
            if (!KEY.matches(k) || data.containsKey(k)) continue
            data[k] = v
        }
        val sb = StringBuilder()
        sb.append("{\"event\":").append(quote(EVENT)).append(",\"data\":{")
        var first = true
        for ((k, v) in data) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k)).append(':').append(value(v))
        }
        return sb.append("}}").toString()
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Byte, is Short, is Int, is Long -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        else -> quote(truncate(v.toString()))
    }

    /** Cuts to [MAX_STRING] UTF-16 units without splitting a surrogate pair. */
    fun truncate(s: String): String {
        if (s.length <= MAX_STRING) return s
        val end = if (Character.isHighSurrogate(s[MAX_STRING - 1])) MAX_STRING - 1 else MAX_STRING
        return s.substring(0, end)
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
