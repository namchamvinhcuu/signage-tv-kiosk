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
        updateChannel = ConfigValidator.normalizeChannel(prefs.getString(K_UPDATE_CHANNEL, null))
            ?: UpdateChannel.STABLE,
    )

    /** commit(): a restart may follow right away and must see the new values. */
    fun save(c: KioskConfig): Boolean = prefs.edit()
        .putString(K_URL, c.url)
        .putInt(K_RESTART_MIN, c.restartMin)
        .putInt(K_LOW_MEM_MB, c.lowMemMb)
        .putString(K_UPDATE_CHANNEL, c.updateChannel.wire)
        .commit()

    fun loadUpdateState(): UpdateState = UpdateState(
        backoffUntilMs = prefs.getLong(K_UPDATE_BACKOFF_UNTIL, 0L),
        verifyFailedCode = prefs.getInt(K_UPDATE_VERIFY_FAILED_CODE, 0),
        verifyFailedAtMs = prefs.getLong(K_UPDATE_VERIFY_FAILED_AT, 0L),
    )

    /** commit(): read again by the next process after an install kills this one. */
    fun saveUpdateState(s: UpdateState): Boolean = prefs.edit()
        .putLong(K_UPDATE_BACKOFF_UNTIL, s.backoffUntilMs)
        .putInt(K_UPDATE_VERIFY_FAILED_CODE, s.verifyFailedCode)
        .putLong(K_UPDATE_VERIFY_FAILED_AT, s.verifyFailedAtMs)
        .commit()

    /** Version being installed; the next process compares it with its own versionCode (start=update). */
    fun savePendingUpdateCode(code: Int): Boolean = prefs.edit().putInt(K_PENDING_UPDATE_CODE, code).commit()

    data class UpdateResultRecord(val status: String, val code: Int, val message: String?)

    /** Written by UpdateResultReceiver (possibly in a process without an activity), reported by MainActivity. */
    fun saveUpdateResult(status: String, code: Int, message: String?): Boolean = prefs.edit()
        .putString(K_UPDATE_RESULT_STATUS, status)
        .putInt(K_UPDATE_RESULT_CODE, code)
        .putString(K_UPDATE_RESULT_MESSAGE, message?.take(200))
        .commit()

    /** The stored, not yet reported result, left in place. */
    fun peekUpdateResult(): UpdateResultRecord? {
        val status = prefs.getString(K_UPDATE_RESULT_STATUS, null) ?: return null
        return UpdateResultRecord(status, prefs.getInt(K_UPDATE_RESULT_CODE, 0), prefs.getString(K_UPDATE_RESULT_MESSAGE, null))
    }

    fun takeUpdateResult(): UpdateResultRecord? {
        val record = peekUpdateResult() ?: return null
        prefs.edit().remove(K_UPDATE_RESULT_STATUS).remove(K_UPDATE_RESULT_CODE).remove(K_UPDATE_RESULT_MESSAGE).commit()
        return record
    }

    /** Returns and clears the pending update code, 0 if none. */
    fun takePendingUpdateCode(): Int {
        val code = prefs.getInt(K_PENDING_UPDATE_CODE, 0)
        if (code != 0) prefs.edit().remove(K_PENDING_UPDATE_CODE).commit()
        return code
    }

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
        const val K_UPDATE_CHANNEL = "update_channel"
        const val K_UPDATE_BACKOFF_UNTIL = "update_backoff_until"
        const val K_UPDATE_VERIFY_FAILED_CODE = "update_verify_failed_code"
        const val K_UPDATE_VERIFY_FAILED_AT = "update_verify_failed_at"
        const val K_PENDING_UPDATE_CODE = "pending_update_code"
        const val K_UPDATE_RESULT_STATUS = "update_result_status"
        const val K_UPDATE_RESULT_CODE = "update_result_code"
        const val K_UPDATE_RESULT_MESSAGE = "update_result_message"
    }
}
