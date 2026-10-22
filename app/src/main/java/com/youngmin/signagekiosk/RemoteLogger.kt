package com.youngmin.signagekiosk

import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Remote log (SPEC §3): `POST <origin>/checkin/client-log` on one background thread, 5 s timeouts.
 * Every event is also written to the local log; a failed POST is logged locally only.
 * [event] must be called on the main thread (the WebView cookie is read there).
 */
class RemoteLogger(private val appVersion: String) {
    // Bounded queue: while the server is unreachable each post can block up to ~10 s.
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) { _, _ ->
        KLog.w("remote log queue full, event dropped")
    }

    // A third of the server's 60 req / 5 min per-IP budget, which the page itself also uses.
    private val limiter = RateLimiter(20, 5 * 60_000L)

    private var endpoint: String? = null

    fun setPageUrl(url: String) {
        endpoint = ClientLogContract.endpointFor(url)
    }

    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RemoteLogger.event off the main thread" }
        KLog.i("$kind $fields")
        val ep = endpoint ?: run {
            KLog.w("remote log skipped ($kind): no endpoint")
            return
        }
        val body = ClientLogContract.body(kind, appVersion, fields)
        if (body.length > ClientLogContract.MAX_BODY_CHARS) {
            KLog.w("remote log skipped ($kind): body ${body.length} chars")
            return
        }
        if (!limiter.tryAcquire(SystemClock.elapsedRealtime())) {
            KLog.w("remote log rate-limited locally: $kind")
            return
        }
        // The cookie lets the server resolve user_id/user_name. Never log it.
        val cookie = try {
            CookieManager.getInstance().getCookie(ep)
        } catch (e: RuntimeException) {
            KLog.w("remote log ($kind): cookie read failed", e)
            null
        }
        executor.execute { post(ep, kind, body, cookie) }
    }

    /** Lets queued posts finish on their own thread; no new events are accepted. */
    fun shutdown() {
        executor.shutdown()
    }

    private fun post(ep: String, kind: String, body: String, cookie: String?) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(ep).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) KLog.w("remote log $kind -> HTTP $code")
        } catch (e: Exception) {
            // Not only IOException: an unexpected RuntimeException here would kill the unattended kiosk.
            KLog.w("remote log $kind failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
