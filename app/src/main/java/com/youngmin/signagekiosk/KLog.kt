package com.youngmin.signagekiosk

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Process-wide logger (SPEC R12): logcat + local rotating file
 * `getExternalFilesDir(null)/kiosk.log` (pull with `adb shell cat /sdcard/Android/data/<pkg>/files/kiosk.log`).
 * Never pass cookies, tokens or credentials in a message.
 */
object KLog {
    private const val TAG = "SignageKiosk"
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

    @Volatile
    private var file: RotatingFileLog? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            file = RotatingFileLog(File(dir, "kiosk.log"))
        }
    }

    fun i(msg: String) = write(Log.INFO, 'I', msg, null)
    fun w(msg: String, t: Throwable? = null) = write(Log.WARN, 'W', msg, t)
    fun e(msg: String, t: Throwable? = null) = write(Log.ERROR, 'E', msg, t)

    private fun write(priority: Int, level: Char, msg: String, t: Throwable?) {
        // stackTraceToString, not Log.getStackTraceString: the latter prints nothing for UnknownHostException.
        val full = if (t == null) msg else "$msg\n${t.stackTraceToString()}"
        Log.println(priority, TAG, full)
        val f = file ?: return
        try {
            f.append("${ZonedDateTime.now().format(TIME)} ${Process.myPid()} $level $full")
        } catch (e: IOException) {
            Log.e(TAG, "local log write failed", e)
        }
    }
}
