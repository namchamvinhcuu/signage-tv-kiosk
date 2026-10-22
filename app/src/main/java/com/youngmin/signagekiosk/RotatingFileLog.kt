package com.youngmin.signagekiosk

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * SPEC R12 local log: appends lines to [file]; once it reaches [maxBytes] it is moved to `<file>.1`
 * (replacing the previous one) and a fresh file starts. Pure java.io so it runs in JVM unit tests.
 */
class RotatingFileLog(private val file: File, private val maxBytes: Long = 512 * 1024L) {

    @Synchronized
    @Throws(IOException::class)
    fun append(line: String) {
        if (file.length() >= maxBytes) rotate()
        FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
    }

    private fun rotate() {
        val old = File(file.path + ".1")
        if (old.exists() && !old.delete()) throw IOException("cannot delete ${old.name}")
        // A failed rename must not let the file grow without bound: drop it instead.
        if (!file.renameTo(old) && !file.delete()) throw IOException("cannot rotate ${file.name}")
    }
}
