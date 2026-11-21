package com.youngmin.signagekiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * SPEC R15: the alarm that reopens MainActivity after [FrontPolicy.AWAY_MS] away from the front. MainActivity.onStop
 * arms it, onStart cancels it. A system alarm survives the OS killing a cached process, so no service or notification
 * is needed; it does not survive a force-stop or a reboot (BootReceiver covers the reboot). Measured on tivi 7
 * (2026-11-21): it fires after a process kill and the background start works under the overlay appop; the platform
 * delivered it 6m38s and 8m45s after arming (inexact alarm, window ~3m45s).
 */
object FrontWatch {
    /** Wire value of EXTRA_START for a start made by this alarm. */
    const val START_WIRE = "front_watch"

    /** Set just before the alarm starts MainActivity; read by `logAppStart` so app_start reports [START_WIRE]. */
    val startMark = FrontStartMark()

    /** True between onStart and onStop of THIS process: an alarm that fires inside that race does nothing. */
    @Volatile
    var inFront = false

    /** Throws (e.g. SecurityException) when the platform refuses; the caller logs it. */
    fun arm(context: Context) {
        // Not a WAKEUP alarm: onAlarm only acts on a lit screen, and the SCREEN_OFF retry would otherwise wake a TV in
        // standby every AWAY_MS all night. An overdue alarm fires as soon as the TV wakes.
        alarms(context).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + FrontPolicy.AWAY_MS,
            pending(context),
        )
    }

    fun cancel(context: Context) = alarms(context).cancel(pending(context))

    fun onAlarm(context: Context) {
        if (inFront) return
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val decision = FrontPolicy.decide(interactive, Settings.canDrawOverlays(context))
        KLog.i("front watch fired: ${decision.wire}")
        // Re-armed first: until MainActivity.onStart cancels it, a skipped or dropped start is retried every AWAY_MS.
        try {
            arm(context)
        } catch (e: RuntimeException) {
            KLog.e("front watch re-arm failed", e)
        }
        if (decision != FrontPolicy.Decision.START) return
        try {
            startMark.mark(SystemClock.elapsedRealtime())
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, START_WIRE),
            )
        } catch (e: RuntimeException) {
            KLog.e("front watch start of MainActivity failed", e)
        }
    }

    private fun alarms(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FrontWatchReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Target of the [FrontWatch] alarm; the PendingIntent is our own, so a non-exported receiver is reachable. */
class FrontWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        KLog.init(context)
        FrontWatch.onAlarm(context)
    }
}
