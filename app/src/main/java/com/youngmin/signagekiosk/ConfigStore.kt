package com.youngmin.signagekiosk

import android.content.Context

/** SharedPreferences for the config (SPEC R10) and the reason the previous process ended (SPEC R4). */
class ConfigStore(context: Context) {
    data class ExitRecord(val reason: String, val atWallMs: Long)

    private val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** Stored values go through the same validation as every other source; invalid → default. */
    fun load(): KioskConfig = KioskConfig(
        url = ConfigValidator.validateUrl(prefs.getString(K_URL, null)) ?: KioskConfig.DEFAULT_URL,
        restartMin = ConfigValidator.normalizeRestartMin(prefs.getInt(K_RESTART_MIN, KioskConfig.DEFAULT_RESTART_MIN))
            ?: KioskConfig.DEFAULT_RESTART_MIN,
        lowMemMb = ConfigValidator.normalizeLowMemMb(prefs.getInt(K_LOW_MEM_MB, KioskConfig.DEFAULT_LOW_MEM_MB))
            ?: KioskConfig.DEFAULT_LOW_MEM_MB,
    )

    /** commit(): a restart may follow right away and must see the new values. */
    fun save(c: KioskConfig): Boolean = prefs.edit()
        .putString(K_URL, c.url)
        .putInt(K_RESTART_MIN, c.restartMin)
        .putInt(K_LOW_MEM_MB, c.lowMemMb)
        .commit()

    /** Synchronous on purpose: the process exits right after this call. */
    fun saveExitReason(reason: String, atWallMs: Long): Boolean = prefs.edit()
        .putString(K_EXIT_REASON, reason)
        .putLong(K_EXIT_AT, atWallMs)
        .commit()

    /** Returns and clears the reason saved by the previous process, if any. */
    fun takeExitReason(): ExitRecord? {
        val reason = prefs.getString(K_EXIT_REASON, null) ?: return null
        val at = prefs.getLong(K_EXIT_AT, 0L)
        prefs.edit().remove(K_EXIT_REASON).remove(K_EXIT_AT).commit()
        return ExitRecord(reason, at)
    }

    private companion object {
        const val K_URL = "url"
        const val K_RESTART_MIN = "restart_min"
        const val K_LOW_MEM_MB = "low_mem_mb"
        const val K_EXIT_REASON = "exit_reason"
        const val K_EXIT_AT = "exit_at"
    }
}
