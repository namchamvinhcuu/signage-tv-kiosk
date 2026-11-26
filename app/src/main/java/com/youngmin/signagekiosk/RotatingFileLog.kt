package com.youngmin.signagekiosk

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * SPEC R12 local log: appends lines to [file]; once it reaches [maxBytes] it is moved to `<file>.1`
 * (replacing the previous one) and a fresh file starts. Pure java.io so it runs in JVM unit tests.
 *
 * A line whose write failed (measured on a TCL TV: `EPERM` on the external app dir ~26 s after boot, gone a few
 * seconds later) is kept in a small in-memory queue and written, in order, before the next line that succeeds, so the
 * early-boot lines are not lost. The queue holds [MAX_PENDING] lines, the oldest is dropped beyond that. If a write
 * reached the file and only the close failed, the retry repeats that line once.
 */
class RotatingFileLog(private val file: File, private val maxBytes: Long = 512 * 1024L) {

    private val pending = ArrayDeque<String>()

    /** Still throws on failure: the caller reports it (KLog logs it to logcat). The line stays queued for the next call. */
    @Synchronized
    @Throws(IOException::class)
    fun append(line: String) {
        try {
            if (file.length() >= maxBytes) rotate()
            val text = StringBuilder()
            pending.forEach { text.append(it).append('\n') }
            text.append(line).append('\n')
            FileOutputStream(file, true).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
            pending.clear()
        } catch (e: IOException) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(line)
            throw e
        }
    }

    private fun rotate() {
        val old = File(file.path + ".1")
        if (old.exists() && !old.delete()) throw IOException("cannot delete ${old.name}")
        // A failed rename must not let the file grow without bound: drop it instead.
        if (!file.renameTo(old) && !file.delete()) throw IOException("cannot rotate ${file.name}")
    }

    companion object {
        const val MAX_PENDING = 64
    }
}
