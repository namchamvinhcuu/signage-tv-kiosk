package com.youngmin.signagekiosk

// Pure Kotlin decision logic (no android.* imports) so it runs in JVM unit tests.
// Every time value is a monotonic clock reading in milliseconds (SystemClock.elapsedRealtime /
// KeyEvent.eventTime on the device), never wall time.

/**
 * SPEC R3: each renderer death or hang is a "recovery"; the [maxRecoveries]-th recovery inside a sliding
 * [windowMs] escalates to a full process restart instead of another WebView recreate.
 */
class RecoveryPolicy(
    private val windowMs: Long = 15 * 60_000L,
    private val maxRecoveries: Int = 3,
) {
    enum class Action { RECREATE, RESTART }

    private val recent = ArrayDeque<Long>()

    /** Recoveries since process start (reported in app_status). */
    var total = 0
        private set

    fun onRecovery(nowMs: Long): Action {
        total++
        recent.addLast(nowMs)
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        return if (recent.size >= maxRecoveries) Action.RESTART else Action.RECREATE
    }
}

/**
 * Cross-process backoff for R3: [RecoveryPolicy] restarts with every process, so a page that kills the
 * renderer right after load would restart the process every ~15 s forever. When this process itself was
 * started by an escalation restart, a further escalation inside [MIN_UPTIME_MS] becomes a slow recreate.
 */
object EscalationGuard {
    const val ESCALATION_PREFIX = "escalation_"
    const val MIN_UPTIME_MS = 10 * 60_000L

    fun restartAllowed(prevExitReason: String?, uptimeMs: Long): Boolean =
        !(prevExitReason?.startsWith(ESCALATION_PREFIX) == true && uptimeMs < MIN_UPTIME_MS)
}

/** SPEC R5 (periodic) + R6 (low memory) restart decision. */
object RestartPolicy {
    /** R6: never restart for memory before this much uptime (avoids restart loops). */
    const val LOW_MEM_MIN_UPTIME_MS = 20 * 60_000L

    enum class Reason(val wire: String) { PERIODIC("periodic"), LOW_MEMORY("low_memory") }

    /**
     * [restartMin] / [lowMemMb] are already normalized (0 = off). [lowMemMb] = 0 only turns off the MB
     * threshold; the system `lowMemory` flag always counts (Nam, 2026-10-22).
     */
    fun decide(uptimeMs: Long, restartMin: Int, lowMemMb: Int, lowMemory: Boolean, availMb: Long): Reason? {
        if (restartMin > 0 && uptimeMs >= restartMin * 60_000L) return Reason.PERIODIC
        if (uptimeMs >= LOW_MEM_MIN_UPTIME_MS && (lowMemory || (lowMemMb > 0 && availMb < lowMemMb))) {
            return Reason.LOW_MEMORY
        }
        return null
    }
}

/**
 * SPEC R7: the caller ticks every probe interval and sends `evaluateJavascript("1")` with the returned token;
 * [maxMissed] consecutive unanswered probes mean the page is hung. [reset] on every WebView recreate so a late
 * answer from the old WebView cannot clear the new one's state.
 */
class HangDetector(private val maxMissed: Int = 2) {
    sealed interface Tick {
        data class Probe(val token: Int) : Tick
        object Hung : Tick
    }

    private var generation = 0
    private var outstanding = false
    private var missed = 0

    fun tick(): Tick {
        if (outstanding) {
            missed++
            if (missed >= maxMissed) return Tick.Hung
        }
        outstanding = true
        return Tick.Probe(generation)
    }

    fun onAnswer(token: Int) {
        if (token != generation) return
        outstanding = false
        missed = 0
    }

    fun reset() {
        generation++
        outstanding = false
        missed = 0
    }
}

/** SPEC R11: true when [taps] presses land within [windowMs] (then the counter starts over). */
class TapCounter(private val taps: Int = 3, private val windowMs: Long = 2_000L) {
    private val times = ArrayDeque<Long>()

    fun onPress(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.size > taps) times.removeFirst()
        if (times.size == taps && nowMs - times.first() <= windowMs) {
            times.clear()
            return true
        }
        return false
    }
}

/** Sliding-window budget for remote log posts (the server's per-IP limit is shared with the page). */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }
}
