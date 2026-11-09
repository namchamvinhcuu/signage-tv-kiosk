package com.youngmin.signagekiosk

import com.youngmin.signagekiosk.HangDetector.Tick
import com.youngmin.signagekiosk.RecoveryPolicy.Action
import com.youngmin.signagekiosk.RestartPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

class RecoveryPolicyTest {

    @Test
    fun `first two recoveries recreate the WebView`() {
        val p = RecoveryPolicy()
        assertEquals(Action.RECREATE, p.onRecovery(0))
        assertEquals(Action.RECREATE, p.onRecovery(1 * MIN))
    }

    @Test
    fun `third recovery inside the window restarts the process`() {
        val p = RecoveryPolicy()
        p.onRecovery(0)
        p.onRecovery(5 * MIN)
        assertEquals(Action.RESTART, p.onRecovery(10 * MIN))
    }

    @Test
    fun `recoveries older than the window expire`() {
        val p = RecoveryPolicy()
        p.onRecovery(0)
        p.onRecovery(10 * MIN)
        // 16 min after the first one: it has left the 15-min window, only 2 remain.
        assertEquals(Action.RECREATE, p.onRecovery(16 * MIN))
    }

    @Test
    fun `a recovery exactly windowMs old has expired`() {
        val p = RecoveryPolicy()
        p.onRecovery(0)
        p.onRecovery(1 * MIN)
        assertEquals(Action.RECREATE, p.onRecovery(15 * MIN))
    }

    @Test
    fun `a recovery one ms inside the window still counts`() {
        val p = RecoveryPolicy()
        p.onRecovery(0)
        p.onRecovery(1 * MIN)
        assertEquals(Action.RESTART, p.onRecovery(15 * MIN - 1))
    }

    @Test
    fun `sliding window escalates once three fall within any 15 min`() {
        val p = RecoveryPolicy()
        assertEquals(Action.RECREATE, p.onRecovery(0))
        assertEquals(Action.RECREATE, p.onRecovery(14 * MIN))
        assertEquals(Action.RECREATE, p.onRecovery(20 * MIN)) // 0 expired -> {14, 20}
        assertEquals(Action.RESTART, p.onRecovery(28 * MIN)) // {14, 20, 28}
    }

    @Test
    fun `total counts every recovery including expired ones`() {
        val p = RecoveryPolicy()
        assertEquals(0, p.total)
        p.onRecovery(0)
        p.onRecovery(30 * MIN)
        p.onRecovery(60 * MIN)
        assertEquals(3, p.total)
        p.onRecovery(61 * MIN)
        p.onRecovery(62 * MIN)
        assertEquals(5, p.total)
    }

    @Test
    fun `custom window and max are honoured`() {
        val p = RecoveryPolicy(windowMs = 1_000L, maxRecoveries = 2)
        assertEquals(Action.RECREATE, p.onRecovery(0))
        assertEquals(Action.RESTART, p.onRecovery(999))
    }
}

class RestartPolicyTest {

    private fun decide(
        uptimeMs: Long,
        restartMin: Int = 0,
        lowMemMb: Int = 0,
        lowMemory: Boolean = false,
        availMb: Long = 10_000,
    ) = RestartPolicy.decide(uptimeMs, restartMin, lowMemMb, lowMemory, availMb)

    @Test
    fun `periodic restart fires once uptime reaches restartMin`() {
        assertNull(decide(240 * MIN - 1, restartMin = 240))
        assertEquals(Reason.PERIODIC, decide(240 * MIN, restartMin = 240))
        assertEquals(Reason.PERIODIC, decide(500 * MIN, restartMin = 240))
    }

    @Test
    fun `restartMin zero disables periodic restart`() {
        assertNull(decide(100_000 * MIN, restartMin = 0))
    }

    @Test
    fun `largest restartMin does not overflow`() {
        assertNull(decide(1440 * MIN - 1, restartMin = 1440))
        assertEquals(Reason.PERIODIC, decide(1440 * MIN, restartMin = 1440))
    }

    @Test
    fun `low memory restart when available memory is below the threshold after min uptime`() {
        assertEquals(Reason.LOW_MEMORY, decide(20 * MIN, lowMemMb = 300, availMb = 299))
    }

    @Test
    fun `available memory equal to the threshold does not restart`() {
        assertNull(decide(20 * MIN, lowMemMb = 300, availMb = 300))
    }

    @Test
    fun `system lowMemory flag restarts when the threshold is enabled`() {
        assertEquals(Reason.LOW_MEMORY, decide(20 * MIN, lowMemMb = 300, lowMemory = true, availMb = 5_000))
    }

    @Test
    fun `low memory never restarts before 20 min uptime`() {
        assertEquals(20 * MIN, RestartPolicy.LOW_MEM_MIN_UPTIME_MS)
        assertNull(decide(20 * MIN - 1, lowMemMb = 4096, lowMemory = true, availMb = 0))
        assertNull(decide(0, lowMemMb = 4096, lowMemory = true, availMb = 0))
    }

    @Test
    fun `lowMemMb zero still restarts on the system lowMemory flag after min uptime`() {
        // Nam, 2026-10-22: the system flag always counts; lowMemMb = 0 only turns off the MB threshold.
        assertEquals(Reason.LOW_MEMORY, decide(20 * MIN, lowMemMb = 0, lowMemory = true, availMb = 5_000))
        assertEquals(Reason.LOW_MEMORY, decide(100 * MIN, lowMemMb = 0, lowMemory = true, availMb = 0))
    }

    @Test
    fun `lowMemMb zero with the system flag still waits for 20 min uptime`() {
        assertNull(decide(20 * MIN - 1, lowMemMb = 0, lowMemory = true, availMb = 0))
        assertNull(decide(0, lowMemMb = 0, lowMemory = true, availMb = 0))
    }

    @Test
    fun `lowMemMb zero disables the MB threshold when the system flag is off`() {
        assertNull(decide(100 * MIN, lowMemMb = 0, lowMemory = false, availMb = 0))
    }

    @Test
    fun `periodic wins when both rules fire`() {
        assertEquals(
            Reason.PERIODIC,
            decide(240 * MIN, restartMin = 240, lowMemMb = 300, lowMemory = true, availMb = 10),
        )
    }

    @Test
    fun `low memory applies when periodic is configured but not yet due`() {
        assertEquals(Reason.LOW_MEMORY, decide(30 * MIN, restartMin = 240, lowMemMb = 300, availMb = 10))
    }

    @Test
    fun `nothing configured and no memory pressure means no restart`() {
        assertNull(decide(100_000 * MIN, restartMin = 0, lowMemMb = 0, lowMemory = false, availMb = 0))
    }

    @Test
    fun `nothing configured still restarts on the system lowMemory flag`() {
        assertEquals(
            Reason.LOW_MEMORY,
            decide(100_000 * MIN, restartMin = 0, lowMemMb = 0, lowMemory = true, availMb = 0),
        )
    }

    @Test
    fun `wire names match the remote log contract`() {
        assertEquals("periodic", Reason.PERIODIC.wire)
        assertEquals("low_memory", Reason.LOW_MEMORY.wire)
    }
}

class EscalationGuardTest {

    private val min10 = EscalationGuard.MIN_UPTIME_MS

    @Test
    fun `guard constants`() {
        assertEquals(10 * MIN, EscalationGuard.MIN_UPTIME_MS)
        assertEquals("escalation_", EscalationGuard.ESCALATION_PREFIX)
    }

    @Test
    fun `no previous exit reason allows restart`() {
        assertTrue(EscalationGuard.restartAllowed(null, 0))
    }

    @Test
    fun `escalation shortly after an escalation restart is blocked`() {
        // Real wire values: MainActivity.recover("render_gone") / recover("hang") -> ESCALATION_PREFIX + reason.
        for (prev in listOf("escalation_render_gone", "escalation_hang")) {
            assertFalse(prev, EscalationGuard.restartAllowed(prev, 0))
            assertFalse(prev, EscalationGuard.restartAllowed(prev, min10 - 1))
        }
        assertEquals("escalation_render_gone", EscalationGuard.ESCALATION_PREFIX + "render_gone")
        assertFalse(EscalationGuard.restartAllowed("escalation_", 0))
    }

    @Test
    fun `escalation is allowed again from 10 min uptime`() {
        assertTrue(EscalationGuard.restartAllowed("escalation_hang", min10))
        assertTrue(EscalationGuard.restartAllowed("escalation_hang", 1_000 * MIN))
    }

    @Test
    fun `other exit reasons never block`() {
        for (reason in listOf("periodic", "low_memory", "exit_by_user", "crash_ANR", "crash_NATIVE", "", "crash_escalation_x")) {
            assertTrue("reason '$reason'", EscalationGuard.restartAllowed(reason, 0))
        }
    }
}

class RefrontPolicyTest {

    private val window = RefrontPolicy.WINDOW_MS

    // Built from the constants MainActivity writes (restartProcess(reason.wire) / ESCALATION_PREFIX + reason), so a
    // renamed wire keeps these cases meaningful; the literal check below catches a rename that would orphan old data.
    private val appRestartReasons = listOf(
        Reason.PERIODIC.wire,
        Reason.LOW_MEMORY.wire,
        EscalationGuard.ESCALATION_PREFIX + "render_gone",
        EscalationGuard.ESCALATION_PREFIX + "hang",
    )

    // Named arguments on purpose: the 4th input is `restarting` (not `finishing`, review round 2), so a swap back to a
    // finishing flag fails to compile here instead of silently changing what MainActivity has to pass.
    private fun refront(prev: String?, sinceCreateMs: Long = 2_000, tried: Boolean = false, restarting: Boolean = false) =
        RefrontPolicy.shouldRefront(
            prevExitReason = prev,
            sinceCreateMs = sinceCreateMs,
            alreadyTried = tried,
            restarting = restarting,
        )

    @Test
    fun `window stays below the platform 10 s background-start grace period`() {
        assertEquals(8_000L, RefrontPolicy.WINDOW_MS)
        assertTrue(RefrontPolicy.WINDOW_MS < 10_000L)
    }

    @Test
    fun `covered reasons are the literal wires MainActivity saves`() {
        assertEquals(listOf("periodic", "low_memory", "escalation_render_gone", "escalation_hang"), appRestartReasons)
    }

    @Test
    fun `app-initiated restart stopped inside the window brings itself back`() {
        // tivi 7, 2026-11-07: periodic restart, stopped ~2 s after onCreate.
        for (prev in appRestartReasons) assertTrue(prev, refront(prev))
    }

    @Test
    fun `starts that the app did not initiate never refront`() {
        // null = first start / boot / update install (no exit reason saved); update_<code> is an AppStart
        // prevReason, never a saved exit reason, but must not count if it ever arrives here.
        for (prev in listOf(null, "exit_by_user", "crash_RuntimeException", "crash_escalation_x", "update_201", "")) {
            assertFalse("reason '$prev'", refront(prev))
        }
    }

    @Test
    fun `reasons that merely resemble a covered wire do not count`() {
        for (prev in listOf("Periodic", "periodic ", "low_memory_x", "xperiodic", "Escalation_hang", "escalation")) {
            assertFalse("reason '$prev'", refront(prev))
        }
    }

    @Test
    fun `window boundaries from onCreate`() {
        for (prev in appRestartReasons) {
            assertTrue("$prev at 0", refront(prev, sinceCreateMs = 0))
            assertTrue("$prev at window-1", refront(prev, sinceCreateMs = window - 1))
            assertFalse("$prev at window", refront(prev, sinceCreateMs = window))
            assertFalse("$prev at window+1", refront(prev, sinceCreateMs = window + 1))
            assertFalse("$prev long after", refront(prev, sinceCreateMs = 60 * MIN))
        }
    }

    @Test
    fun `negative elapsed time never refronts`() {
        assertFalse(refront(Reason.PERIODIC.wire, sinceCreateMs = -1))
        assertFalse(refront(Reason.PERIODIC.wire, sinceCreateMs = Long.MIN_VALUE))
    }

    @Test
    fun `only one attempt per activity`() {
        for (prev in appRestartReasons) assertFalse(prev, refront(prev, sinceCreateMs = 0, tried = true))
    }

    @Test
    fun `restarting activity never refronts`() {
        // restartProcess() sets `restarting` before RestartActivity.trigger; the new process must win, not this one.
        for (prev in appRestartReasons) assertFalse(prev, refront(prev, sinceCreateMs = 0, restarting = true))
    }
}

class HangDetectorTest {

    @Test
    fun `hung on the third tick when no probe is answered`() {
        val h = HangDetector()
        assertEquals(Tick.Probe(0), h.tick())
        assertEquals(Tick.Probe(0), h.tick()) // 1 missed
        assertEquals(Tick.Hung, h.tick()) // 2 missed
    }

    @Test
    fun `answered probes never report hung`() {
        val h = HangDetector()
        repeat(50) {
            val t = h.tick()
            assertTrue("tick $it was $t", t is Tick.Probe)
            h.onAnswer((t as Tick.Probe).token)
        }
    }

    @Test
    fun `one late answer resets the missed count`() {
        val h = HangDetector()
        h.tick()
        h.tick() // 1 missed
        h.onAnswer(0)
        assertTrue(h.tick() is Tick.Probe)
        assertTrue(h.tick() is Tick.Probe) // 1 missed again
        assertEquals(Tick.Hung, h.tick())
    }

    @Test
    fun `stays hung until reset`() {
        val h = HangDetector()
        repeat(3) { h.tick() }
        assertEquals(Tick.Hung, h.tick())
    }

    @Test
    fun `reset starts a new generation and clears the missed count`() {
        val h = HangDetector()
        h.tick()
        h.tick() // 1 missed
        h.reset()
        assertEquals(Tick.Probe(1), h.tick())
        assertEquals(Tick.Probe(1), h.tick())
        assertEquals(Tick.Hung, h.tick())
    }

    @Test
    fun `answer carrying a token from before reset is ignored`() {
        val h = HangDetector()
        val old = (h.tick() as Tick.Probe).token
        h.reset()
        h.tick()
        h.onAnswer(old) // late answer from the destroyed WebView
        assertTrue(h.tick() is Tick.Probe) // 1 missed
        assertEquals(Tick.Hung, h.tick()) // the stale answer did not clear it
    }

    @Test
    fun `answer of the current generation after reset clears state`() {
        val h = HangDetector()
        h.tick()
        h.reset()
        val t = h.tick() as Tick.Probe
        h.tick() // 1 missed
        h.onAnswer(t.token)
        assertTrue(h.tick() is Tick.Probe)
        assertTrue(h.tick() is Tick.Probe)
        assertEquals(Tick.Hung, h.tick())
    }
}

class TapCounterTest {

    @Test
    fun `three presses within 2000 ms inclusive trigger`() {
        val c = TapCounter()
        assertFalse(c.onPress(0))
        assertFalse(c.onPress(1_000))
        assertTrue(c.onPress(2_000))
    }

    @Test
    fun `three presses spanning 2001 ms do not trigger`() {
        val c = TapCounter()
        c.onPress(0)
        c.onPress(1_000)
        assertFalse(c.onPress(2_001))
    }

    @Test
    fun `counter restarts after a trigger`() {
        val c = TapCounter()
        c.onPress(0)
        c.onPress(100)
        assertTrue(c.onPress(200))
        assertFalse(c.onPress(300))
        assertFalse(c.onPress(400))
        assertTrue(c.onPress(500))
    }

    @Test
    fun `slow presses never trigger`() {
        val c = TapCounter()
        for (t in 0L..20_000L step 1_500L) assertFalse("press at $t", c.onPress(t))
    }

    @Test
    fun `window slides over the last three presses`() {
        val c = TapCounter()
        assertFalse(c.onPress(0))
        assertFalse(c.onPress(1_500))
        assertFalse(c.onPress(2_500)) // 0..2500 too slow
        assertTrue(c.onPress(3_000)) // 1500..3000 fits
    }

    @Test
    fun `two presses alone never trigger`() {
        val c = TapCounter()
        assertFalse(c.onPress(0))
        assertFalse(c.onPress(1))
    }
}

class RateLimiterTest {

    @Test
    fun `allows max posts then blocks inside the window`() {
        val r = RateLimiter(max = 3, windowMs = 1_000)
        assertTrue(r.tryAcquire(0))
        assertTrue(r.tryAcquire(0))
        assertTrue(r.tryAcquire(0))
        assertFalse(r.tryAcquire(0))
        assertFalse(r.tryAcquire(999))
    }

    @Test
    fun `budget comes back exactly windowMs later`() {
        val r = RateLimiter(max = 3, windowMs = 1_000)
        repeat(3) { r.tryAcquire(0) }
        assertTrue(r.tryAcquire(1_000))
    }

    @Test
    fun `window slides per entry`() {
        val r = RateLimiter(max = 3, windowMs = 1_000)
        assertTrue(r.tryAcquire(0))
        assertTrue(r.tryAcquire(400))
        assertTrue(r.tryAcquire(800))
        assertFalse(r.tryAcquire(900))
        assertTrue(r.tryAcquire(1_000)) // 0 expired
        assertFalse(r.tryAcquire(1_100))
        assertTrue(r.tryAcquire(1_400)) // 400 expired
        assertFalse(r.tryAcquire(1_401))
    }

    @Test
    fun `rejected attempts do not consume budget`() {
        val r = RateLimiter(max = 1, windowMs = 1_000)
        assertTrue(r.tryAcquire(0))
        repeat(10) { assertFalse(r.tryAcquire(500)) }
        assertTrue(r.tryAcquire(1_000))
    }

    @Test
    fun `remote logger budget of 20 per 5 min`() {
        val r = RateLimiter(20, 5 * MIN)
        repeat(20) { assertTrue(r.tryAcquire(it * 1_000L)) }
        assertFalse(r.tryAcquire(5 * MIN - 1))
        assertTrue(r.tryAcquire(5 * MIN))
    }
}
