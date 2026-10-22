package com.youngmin.signagekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * SPEC R9: start MainActivity after boot. Android 10+ blocks this background start unless the app holds
 * SYSTEM_ALERT_WINDOW (`adb shell appops set com.youngmin.signagekiosk SYSTEM_ALERT_WINDOW allow`); a blocked
 * start fails silently, so the grant state is logged. Behaviour on MiTV standby is unmeasured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        KLog.init(context)
        KLog.i("boot completed, overlay permission=${Settings.canDrawOverlays(context)}, starting MainActivity")
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, "boot"),
            )
        } catch (e: RuntimeException) {
            KLog.e("boot start of MainActivity failed", e)
        }
    }
}
