package com.youngmin.signagekiosk

import java.net.URI
import java.net.URISyntaxException

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.

/** Runtime configuration (SPEC R10). */
data class KioskConfig(val url: String, val restartMin: Int, val lowMemMb: Int) {
    companion object {
        const val DEFAULT_URL = "http://192.168.0.254:13000/"
        const val DEFAULT_RESTART_MIN = 240
        const val DEFAULT_LOW_MEM_MB = 0
        val DEFAULT = KioskConfig(DEFAULT_URL, DEFAULT_RESTART_MIN, DEFAULT_LOW_MEM_MB)
    }
}

/** Validation + clamping for every config source (SharedPreferences, intent extras, settings dialog). */
object ConfigValidator {
    const val RESTART_MIN_LOW = 15
    const val RESTART_MIN_HIGH = 1440
    const val LOW_MEM_MB_LOW = 50
    const val LOW_MEM_MB_HIGH = 4096

    /** Returns the trimmed URL when it is http/https with a host, else null (rejected). */
    fun validateUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val uri = try {
            URI(s)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return s
    }

    /** 0 = off; negative = rejected (null); otherwise clamped to 15..1440. */
    fun normalizeRestartMin(n: Int): Int? = normalize(n, RESTART_MIN_LOW, RESTART_MIN_HIGH)

    /** 0 = off; negative = rejected (null); otherwise clamped to 50..4096. */
    fun normalizeLowMemMb(n: Int): Int? = normalize(n, LOW_MEM_MB_LOW, LOW_MEM_MB_HIGH)

    private fun normalize(n: Int, low: Int, high: Int): Int? = when {
        n == 0 -> 0
        n < 0 -> null
        else -> n.coerceIn(low, high)
    }
}
