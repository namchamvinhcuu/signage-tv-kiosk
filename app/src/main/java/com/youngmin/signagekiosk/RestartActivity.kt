package com.youngmin.signagekiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * SPEC R4 trampoline (ProcessPhoenix pattern), runs in its own `:restart` process: makes sure the old main
 * process is gone (so the WebView renderer memory really goes back to the OS), starts a fresh MainActivity,
 * then exits. Both starts happen from a foreground activity.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KLog.init(this)
        val oldPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        // killProcess only reaches processes of our own uid, so a recycled pid cannot hit another app.
        if (oldPid > 0 && oldPid != Process.myPid()) Process.killProcess(oldPid)
        KLog.i("restart trampoline: old pid $oldPid, starting MainActivity")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        } catch (e: RuntimeException) {
            KLog.e("restart trampoline: cannot start MainActivity", e)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_MAIN_PID = "main_pid"

        /** Call from the foreground MainActivity; does not return (the main process exits). */
        fun trigger(activity: Activity) {
            activity.startActivity(
                Intent(activity, RestartActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_MAIN_PID, Process.myPid()),
            )
            activity.finish()
            Runtime.getRuntime().exit(0)
        }
    }
}
