package com.youngmin.signagekiosk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val H = 3_600_000L
private const val T0 = 1_800_000_000_000L

class UpdateResultTest {

    private val o = UpdateResult.Outcome.entries

    @Test
    fun `success leaves the state untouched`() {
        val st = UpdateState(backoffUntilMs = T0 + H, verifyFailedCode = 7, verifyFailedAtMs = T0)
        assertSame(st, UpdateResult.nextState(UpdateResult.Outcome.SUCCESS, st, T0))
    }

    @Test
    fun `pending user action backs off 24 h and failures back off 6 h`() {
        assertEquals(T0 + 24 * H, UpdateResult.nextState(UpdateResult.Outcome.PENDING_USER_ACTION, UpdateState(), T0).backoffUntilMs)
        assertEquals(T0 + 6 * H, UpdateResult.nextState(UpdateResult.Outcome.ABORTED, UpdateState(), T0).backoffUntilMs)
        assertEquals(T0 + 6 * H, UpdateResult.nextState(UpdateResult.Outcome.FAILURE, UpdateState(), T0).backoffUntilMs)
    }

    @Test
    fun `only success keeps the pending update code`() {
        assertFalse(UpdateResult.clearsPending(UpdateResult.Outcome.SUCCESS))
        for (x in o - UpdateResult.Outcome.SUCCESS) assertTrue("$x", UpdateResult.clearsPending(x))
    }

    @Test
    fun `aborted never hides a pending user action record of the same code`() {
        val name = UpdateResult.PENDING_USER_ACTION_NAME
        assertEquals("pending_user_action", name)
        assertFalse(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, name, 201, 201))
    }

    @Test
    fun `everything else is recorded`() {
        val name = UpdateResult.PENDING_USER_ACTION_NAME
        // Aborted: different code, other stored status, nothing stored.
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, name, 201, 202))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, "failure", 201, 201))
        assertTrue(UpdateResult.shouldRecord(UpdateResult.Outcome.ABORTED, null, 0, 201))
        // Any other outcome overwrites even a pending record of the same code.
        for (x in o - UpdateResult.Outcome.ABORTED) {
            assertTrue("$x", UpdateResult.shouldRecord(x, name, 201, 201))
            assertTrue("$x", UpdateResult.shouldRecord(x, null, 0, 201))
        }
    }

    // ---- The receiver's sequence, driven only through UpdateResult (UpdateResultReceiver glue) ----

    private data class Prefs(
        var state: UpdateState = UpdateState(),
        var pending: Int = 0,
        var record: Pair<String, Int>? = null,
    )

    private fun Prefs.receive(outcome: UpdateResult.Outcome, statusName: String, code: Int, now: Long) {
        if (UpdateResult.clearsPending(outcome)) pending = 0
        state = UpdateResult.nextState(outcome, state, now)
        if (UpdateResult.shouldRecord(outcome, record?.first, record?.second ?: 0, code)) record = statusName to code
    }

    @Test
    fun `pending then aborted of the same code keeps 24 h and the pending record_Fix_F1`() {
        val p = Prefs(pending = 201)
        p.receive(UpdateResult.Outcome.PENDING_USER_ACTION, UpdateResult.PENDING_USER_ACTION_NAME, 201, T0)
        p.receive(UpdateResult.Outcome.ABORTED, "failure_aborted", 201, T0 + 1000)
        assertEquals(T0 + 24 * H, p.state.backoffUntilMs)
        assertEquals(UpdateResult.PENDING_USER_ACTION_NAME to 201, p.record)
        assertEquals(0, p.pending)
    }

    @Test
    fun `aborted for a different code is recorded and still keeps the longer backoff`() {
        val p = Prefs()
        p.receive(UpdateResult.Outcome.PENDING_USER_ACTION, UpdateResult.PENDING_USER_ACTION_NAME, 201, T0)
        p.receive(UpdateResult.Outcome.ABORTED, "failure_aborted", 202, T0 + 1000)
        assertEquals("failure_aborted" to 202, p.record)
        assertEquals(T0 + 24 * H, p.state.backoffUntilMs)
    }

    @Test
    fun `success keeps the pending code and the backoff`() {
        val p = Prefs(state = UpdateState(backoffUntilMs = T0 + H), pending = 201)
        p.receive(UpdateResult.Outcome.SUCCESS, "success", 201, T0)
        assertEquals(201, p.pending)
        assertEquals(T0 + H, p.state.backoffUntilMs)
        assertEquals("success" to 201, p.record)
    }

    @Test
    fun `a plain failure clears the pending code and backs off 6 h`() {
        val p = Prefs(pending = 201)
        p.receive(UpdateResult.Outcome.FAILURE, "failure_conflict", 201, T0)
        assertEquals(0, p.pending)
        assertEquals(T0 + 6 * H, p.state.backoffUntilMs)
        assertEquals("failure_conflict" to 201, p.record)
    }
}

class AppStartTest {

    private fun derive(extra: String? = null, prev: String? = null, pending: Int = 0, version: Int = 201) =
        AppStart.derive(extra, prev, pending, version)

    @Test
    fun `plain launch and restart without an update`() {
        assertEquals(AppStartInfo("launch", null, false), derive())
        assertEquals(AppStartInfo("restart", "periodic", false), derive(prev = "periodic"))
    }

    @Test
    fun `installed pending code reports an update`() {
        assertEquals(AppStartInfo("update", "update_201", false), derive(pending = 201))
    }

    @Test
    fun `installed update wins over an exit reason`() {
        assertEquals(AppStartInfo("update", "update_201", false), derive(prev = "low_mem", pending = 201))
    }

    @Test
    fun `intent extra wins for start but not for prev reason`() {
        assertEquals(AppStartInfo("update", "update_201", false), derive(extra = "update", pending = 201))
        assertEquals(AppStartInfo("boot", "update_201", false), derive(extra = "boot", prev = "x", pending = 201))
        assertEquals(AppStartInfo("boot", "x", false), derive(extra = "boot", prev = "x"))
        assertEquals(AppStartInfo("update", null, false), derive(extra = "update"))
    }

    @Test
    fun `pending code that is not the running version is flagged not installed`() {
        assertEquals(AppStartInfo("launch", null, true), derive(pending = 202))
        assertEquals(AppStartInfo("restart", "periodic", true), derive(prev = "periodic", pending = 200))
    }

    @Test
    fun `no pending code is never an update even when the version is zero`() {
        assertEquals(AppStartInfo("launch", null, false), derive(pending = 0, version = 0))
    }
}

class OutcomeDedupeTest {

    private val skip = mapOf("reason" to "up_to_date", "code" to 201, "channel" to "stable")

    @Test
    fun `identical consecutive deduped outcomes go out once`() {
        val d = OutcomeDedupe()
        assertTrue(d.shouldSend("skip", skip, dedupe = true))
        assertFalse(d.shouldSend("skip", skip, dedupe = true))
        // A new map with the same entries in the same order (how Updater builds them) is the same outcome.
        assertFalse(d.shouldSend("skip", mapOf("reason" to "up_to_date", "code" to 201, "channel" to "stable"), dedupe = true))
    }

    @Test
    fun `a different stage or field value is sent and becomes the new memory`() {
        val d = OutcomeDedupe()
        assertTrue(d.shouldSend("skip", skip, dedupe = true))
        assertTrue(d.shouldSend("skip", skip + ("code" to 202), dedupe = true))
        assertTrue(d.shouldSend("skip", skip, dedupe = true))
        assertTrue(d.shouldSend("fetch_failed", skip, dedupe = true))
        assertFalse(d.shouldSend("fetch_failed", skip, dedupe = true))
    }

    @Test
    fun `non deduped events always go out and reset the memory`() {
        val d = OutcomeDedupe()
        val dl = mapOf("code" to 201, "size" to 5L)
        assertTrue(d.shouldSend("skip", skip, dedupe = true))
        assertTrue(d.shouldSend("download", dl, dedupe = false))
        assertTrue(d.shouldSend("download", dl, dedupe = false))
        assertTrue(d.shouldSend("skip", skip, dedupe = true))
    }

    @Test
    fun `each instance starts empty`() {
        val a = OutcomeDedupe()
        assertTrue(a.shouldSend("skip", skip, dedupe = true))
        assertTrue(OutcomeDedupe().shouldSend("skip", skip, dedupe = true))
    }
}

class UpdateIoTest {

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }
    private fun sha() = MessageDigest.getInstance("SHA-256")
    private fun sha(b: ByteArray) = sha().digest(b)

    private class Run(val returned: Long, val written: ByteArray, val digest: ByteArray)

    private fun copy(src: ByteArray, max: Long): Run {
        val out = ByteArrayOutputStream()
        val d = sha()
        val n = UpdateIo.copyCapped(ByteArrayInputStream(src), out, max, d)
        return Run(n, out.toByteArray(), d.digest())
    }

    @Test
    fun `exact size over several buffers copies and hashes everything`() {
        val src = bytes(200_000) // > 3 × 64 KiB reads
        val r = copy(src, 200_000)
        assertEquals(200_000L, r.returned)
        assertArrayEquals(src, r.written)
        assertArrayEquals(sha(src), r.digest)
    }

    @Test
    fun `shorter source returns its length`() {
        val src = bytes(10)
        val r = copy(src, 100)
        assertEquals(10L, r.returned)
        assertArrayEquals(src, r.written)
        assertArrayEquals(sha(src), r.digest)
    }

    @Test
    fun `empty source writes nothing and hashes the empty input`() {
        val r = copy(ByteArray(0), 0)
        assertEquals(0L, r.returned)
        assertEquals(0, r.written.size)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateIo.hex(r.digest))
    }

    @Test
    fun `longer source never writes beyond max and the hash matches the written bytes`() {
        for ((size, max) in listOf(100_001 to 100_000L, 200_000 to 65_536L + 1, 1_000_000 to 10L, 1 to 0L)) {
            val r = copy(bytes(size), max)
            assertTrue("returned ${r.returned} for $size/$max", r.returned > max)
            assertTrue("wrote ${r.written.size} for $size/$max", r.written.size <= max)
            assertArrayEquals(bytes(size).copyOf(r.written.size), r.written)
            assertArrayEquals(sha(r.written), r.digest)
        }
    }

    @Test
    fun `one byte more than max is caught in the last read`() {
        val r = copy(bytes(100_001), 100_000)
        // First 64 KiB read fits and is written, the second read goes over max and is dropped.
        assertEquals(65_536, r.written.size)
        assertEquals(100_001L, r.returned)
    }

    @Test
    fun `interrupted thread throws before reading or writing`() {
        val out = ByteArrayOutputStream()
        val input = ByteArrayInputStream(bytes(1000))
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                UpdateIo.copyCapped(input, out, 1000, sha())
            }
            assertEquals(0, out.size())
            assertEquals(1000, input.available())
        } finally {
            Thread.interrupted() // clear the flag so later tests on this thread are not affected
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `hex is lowercase two digits per byte`() {
        assertEquals("", UpdateIo.hex(ByteArray(0)))
        assertEquals("000f10ff7f80", UpdateIo.hex(byteArrayOf(0, 15, 16, -1, 127, -128)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateIo.hex(sha("abc".toByteArray())),
        )
    }

    @Test
    fun `hex of a full digest is 64 chars and accepted by the manifest check`() {
        val h = UpdateIo.hex(sha(bytes(5)))
        assertEquals(64, h.length)
        assertNull(
            UpdatePolicy.verifyDownload(
                UpdateManifest(201, "1.0", "a.apk", h, 5), 5, h, "p", 201, "p",
            ),
        )
    }
}
