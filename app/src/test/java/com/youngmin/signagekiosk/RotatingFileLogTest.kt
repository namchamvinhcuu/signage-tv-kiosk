package com.youngmin.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RotatingFileLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun logFile() = File(tmp.root, "kiosk.log")
    private fun rotated() = File(tmp.root, "kiosk.log.1")

    @Test
    fun `appends lines with newline as UTF-8 and creates the file`() {
        val f = logFile()
        val log = RotatingFileLog(f, maxBytes = 1024)
        log.append("một")
        log.append("two")
        assertEquals("một\ntwo\n", f.readText(Charsets.UTF_8))
        assertFalse(rotated().exists())
    }

    @Test
    fun `no rotation while size is below maxBytes`() {
        val f = logFile()
        val log = RotatingFileLog(f, maxBytes = 11)
        log.append("123456789") // 10 bytes
        log.append("x") // 10 < 11 -> same file
        assertEquals("123456789\nx\n", f.readText())
        assertFalse(rotated().exists())
    }

    @Test
    fun `rotates to dot1 once size reaches maxBytes`() {
        val f = logFile()
        val log = RotatingFileLog(f, maxBytes = 10)
        log.append("123456789") // exactly 10 bytes
        log.append("x")
        assertEquals("123456789\n", rotated().readText())
        assertEquals("x\n", f.readText())
    }

    @Test
    fun `size is measured in bytes not chars`() {
        val f = logFile()
        val log = RotatingFileLog(f, maxBytes = 7)
        log.append("ếế") // 2 chars, 6 bytes + newline = 7 bytes
        log.append("y")
        assertEquals("ếế\n", rotated().readText(Charsets.UTF_8))
        assertEquals("y\n", f.readText())
    }

    @Test
    fun `rotation replaces an existing dot1`() {
        val f = logFile()
        rotated().writeText("old\n")
        val log = RotatingFileLog(f, maxBytes = 4)
        log.append("new")
        log.append("next")
        assertEquals("new\n", rotated().readText())
        assertEquals("next\n", f.readText())
    }

    @Test
    fun `repeated rotation keeps only one backup`() {
        val f = logFile()
        val log = RotatingFileLog(f, maxBytes = 2)
        for (n in 1..5) log.append("$n")
        assertEquals("4\n", rotated().readText())
        assertEquals("5\n", f.readText())
        assertEquals(setOf("kiosk.log", "kiosk.log.1"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `an existing log from a previous run is rotated on the first append`() {
        val f = logFile()
        f.writeText("previous run\n")
        val log = RotatingFileLog(f, maxBytes = 5)
        log.append("start")
        assertEquals("previous run\n", rotated().readText())
        assertEquals("start\n", f.readText())
    }

    @Test
    fun `undeletable dot1 raises IOException instead of growing silently`() {
        val f = logFile()
        f.writeText("full\n")
        assertTrue(rotated().mkdir())
        File(rotated(), "child").writeText("x") // non-empty directory cannot be deleted
        val log = RotatingFileLog(f, maxBytes = 1)
        try {
            log.append("line")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("kiosk.log.1"))
        }
        assertEquals("full\n", f.readText())
    }

    // --- Early-boot EPERM: a line whose write failed is queued and written before the next line that succeeds ---

    /** The parent dir does not exist yet, so every write fails with an IOException, like the not-yet-writable app dir. */
    private fun lateDir() = File(tmp.root, "late")
    private fun lateFile() = File(lateDir(), "kiosk.log")

    private fun assertAppendFails(log: RotatingFileLog, line: String) {
        try {
            log.append(line)
            fail("expected IOException for \"$line\"")
        } catch (e: IOException) {
            // expected: the caller (KLog) still sees the failure
        }
    }

    @Test
    fun `a failing append throws IOException and the line is not in the file`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024)
        assertAppendFails(log, "boot line")
        assertFalse(lateFile().exists())
        assertTrue(lateDir().mkdir()) // dir becomes writable, nothing was written behind our back
        assertFalse(lateFile().exists())
    }

    @Test
    fun `next successful append writes the failed lines first in order then the new line once each`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024)
        assertAppendFails(log, "first")
        assertAppendFails(log, "second")
        assertTrue(lateDir().mkdir())

        log.append("third")

        val text = lateFile().readText(Charsets.UTF_8)
        assertEquals("first\nsecond\nthird\n", text)
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `failed UTF-8 lines are written intact once the dir is writable`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024)
        assertAppendFails(log, "khởi động")
        assertTrue(lateDir().mkdir())
        log.append("xong")
        assertEquals("khởi động\nxong\n", lateFile().readText(Charsets.UTF_8))
    }

    @Test
    fun `after a successful flush a further append does not repeat the old lines`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024)
        assertAppendFails(log, "first")
        assertAppendFails(log, "second")
        assertTrue(lateDir().mkdir())
        log.append("third")

        log.append("fourth")
        log.append("fifth")

        assertEquals("first\nsecond\nthird\nfourth\nfifth\n", lateFile().readText())
    }

    @Test
    fun `a failure after a flush queues only the new failed line`() {
        // big maxBytes: a directory reports a length of several KB and must not be taken for a full log (rotated away)
        val log = RotatingFileLog(lateFile(), maxBytes = 1024 * 1024)
        assertAppendFails(log, "early")
        assertTrue(lateDir().mkdir())
        log.append("ok1")
        // make the next write fail again by replacing the file with a directory of the same name
        assertTrue(lateFile().delete())
        assertTrue(lateFile().mkdir())
        assertAppendFails(log, "late")
        assertTrue(lateFile().delete())

        log.append("ok2")

        assertEquals("late\nok2\n", lateFile().readText())
    }

    @Test
    fun `all lines are kept up to MAX_PENDING failures`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024 * 1024)
        val n = RotatingFileLog.MAX_PENDING
        for (i in 1..n) assertAppendFails(log, "l$i")
        assertTrue(lateDir().mkdir())

        log.append("ok")

        val expected = (1..n).joinToString("") { "l$it\n" } + "ok\n"
        assertEquals(expected, lateFile().readText())
    }

    @Test
    fun `beyond MAX_PENDING only the newest lines survive and the oldest are dropped`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024 * 1024)
        val n = RotatingFileLog.MAX_PENDING
        val failures = 70
        for (i in 1..failures) assertAppendFails(log, "l$i")
        assertTrue(lateDir().mkdir())

        log.append("ok")

        val lines = lateFile().readLines()
        assertEquals(n + 1, lines.size)
        assertEquals("l${failures - n + 1}", lines.first()) // l7
        assertEquals("l$failures", lines[n - 1]) // l70
        assertEquals("ok", lines.last())
        assertFalse(lines.contains("l1"))
        assertFalse(lines.contains("l6"))
        assertEquals((failures - n + 1..failures).map { "l$it" } + "ok", lines)
    }

    @Test
    fun `one more failure than MAX_PENDING drops exactly the oldest line`() {
        val log = RotatingFileLog(lateFile(), maxBytes = 1024 * 1024)
        val n = RotatingFileLog.MAX_PENDING
        for (i in 1..n + 1) assertAppendFails(log, "l$i")
        assertTrue(lateDir().mkdir())

        log.append("ok")

        val lines = lateFile().readLines()
        assertEquals(n + 1, lines.size)
        assertEquals("l2", lines.first())
        assertEquals("l${n + 1}", lines[n - 1])
    }

    @Test
    fun `a failure during rotation also queues the line and it is written after rotation recovers`() {
        val f = logFile()
        f.writeText("full\n")
        assertTrue(rotated().mkdir())
        val child = File(rotated(), "child")
        child.writeText("x") // non-empty directory cannot be deleted
        val log = RotatingFileLog(f, maxBytes = 1)

        assertAppendFails(log, "stuck1")
        assertAppendFails(log, "stuck2")
        assertEquals("full\n", f.readText()) // nothing was written while rotation failed

        assertTrue(child.delete())
        assertTrue(rotated().delete()) // rotation can succeed now

        log.append("fresh")

        assertEquals("full\n", rotated().readText())
        assertEquals("stuck1\nstuck2\nfresh\n", f.readText())
    }

    @Test
    fun `pending lines count toward the size check only after they are written`() {
        val f = logFile()
        f.writeText("123456789\n") // already exactly maxBytes (10)
        val log = RotatingFileLog(f, maxBytes = 10)
        // fail a first append through an undeletable dot1: the file stays at maxBytes
        assertTrue(rotated().mkdir())
        val child = File(rotated(), "child")
        child.writeText("x")
        assertAppendFails(log, "pending-line-longer-than-the-limit")
        assertTrue(child.delete())
        assertTrue(rotated().delete())

        log.append("ok") // rotates first (file at maxBytes), then writes the pending line + this one in one go

        assertEquals("123456789\n", rotated().readText())
        // pending bytes were not counted before being written: no extra rotation splits them from the new line
        assertEquals("pending-line-longer-than-the-limit\nok\n", f.readText())
        assertTrue(f.length() > 10)

        log.append("next") // now the written pending lines count: the oversized file rotates, no crash

        assertEquals("pending-line-longer-than-the-limit\nok\n", rotated().readText())
        assertEquals("next\n", f.readText())
    }
}
