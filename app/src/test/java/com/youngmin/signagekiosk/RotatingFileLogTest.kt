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
}
