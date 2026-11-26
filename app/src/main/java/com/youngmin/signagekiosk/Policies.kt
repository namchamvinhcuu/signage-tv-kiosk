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
 * After an app-initiated process restart the fresh activity can land behind the launcher (tivi 7, 2026-11-07:
 * stopped 2 s after onStart, cause unproven). Inside the background-start grace period (an activity launched in
 * this process < 10 s ago, no HOME press since) it may bring itself back once; a blocked start is a no-op.
 * An activity the system finished itself counts too: the app only finishes while restarting or exiting, and both
 * end the process before onStop.
 */
object RefrontPolicy {
    /** Below the platform's 10 s grace period, measured from onCreate (which follows the launch). */
    const val WINDOW_MS = 8_000L

    /** [prevExitReason] = reason saved by the previous process; only restarts this app started itself count. */
    fun shouldRefront(prevExitReason: String?, sinceCreateMs: Long, alreadyTried: Boolean, restarting: Boolean): Boolean {
        if (alreadyTried || restarting || sinceCreateMs !in 0 until WINDOW_MS) return false
        return prevExitReason == RestartPolicy.Reason.PERIODIC.wire ||
            prevExitReason == RestartPolicy.Reason.LOW_MEMORY.wire ||
            prevExitReason?.startsWith(EscalationGuard.ESCALATION_PREFIX) == true
    }
}

/**
 * SPEC R15: a kiosk that lost the front (the customer opened YouTube / a browser with the remote) is only a cached
 * background app: the OS kills it and nothing reopens it. [AWAY_MS] after MainActivity stopped, an alarm asks the app to
 * bring itself back. It needs a lit screen (a TV put on standby by hand stays off) and the overlay appop (without it the
 * platform drops a background activity start silently, as in BootReceiver).
 */
object FrontPolicy {
    /** Nam, 2026-11-20: 5 min away from the front before the app reopens itself. */
    const val AWAY_MS = 5 * 60_000L

    enum class Decision(val wire: String) { START("start"), SCREEN_OFF("screen_off"), NO_OVERLAY("no_overlay") }

    fun decide(interactive: Boolean, canOverlay: Boolean): Decision = when {
        !interactive -> Decision.SCREEN_OFF
        !canOverlay -> Decision.NO_OVERLAY
        else -> Decision.START
    }
}

/**
 * SPEC R9 fallback: decision of [KioskStartService]. The system (re)connects an enabled accessibility service not only
 * at boot but after any process death (operator "exit", periodic restart, OS kill, self-update). Only a connect shortly
 * after the DEVICE booted, while the kiosk is not already in front, may open it; every other connect leaves the kiosk
 * alone, so an "exit" stays an exit and the restart/update machinery keeps its own labels and timing.
 */
object BootStartPolicy {
    /** Device uptime below which a connect counts as "just booted": room for a slow boot, below a normal restart. */
    const val BOOT_WINDOW_MS = 5 * 60_000L

    /** [deviceUptimeMs] is the time since DEVICE boot (SystemClock.elapsedRealtime), not since process start. */
    fun shouldStart(deviceUptimeMs: Long, kioskInFront: Boolean): Boolean =
        deviceUptimeMs in 0 until BOOT_WINDOW_MS && !kioskInFront
}

/**
 * SPEC R15: the FrontWatch alarm runs in the SAME process that then hosts the activity (a killed kiosk is started by the
 * alarm broadcast), so a process-local mark set just before `startActivity` tells [AppStart] that this start was the
 * alarm's, even when the system re-creates the surviving activity record with its ORIGINAL launch intent (a stale
 * EXTRA_START such as `update`). One main thread sets and takes it, so no locking.
 */
class FrontStartMark(private val windowMs: Long = WINDOW_MS) {
    private var markedAtMs = NONE

    fun mark(nowMs: Long) {
        markedAtMs = nowMs
    }

    /** True once per [mark], and only while it is younger than the window: a later start must not inherit it. */
    fun take(nowMs: Long): Boolean {
        val at = markedAtMs
        markedAtMs = NONE
        return at != NONE && nowMs - at in 0..windowMs
    }

    companion object {
        const val WINDOW_MS = 30_000L
        private const val NONE = Long.MIN_VALUE
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
