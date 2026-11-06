package com.youngmin.signagekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Reopens the kiosk after a self-update: installing the new APK kills the running process and nothing else brings
 * it back. Like the boot start, this background activity start needs the SYSTEM_ALERT_WINDOW appop, which is why
 * [Updater] never installs without it (B6a). Separate from BootReceiver so the measured boot path stays untouched.
 */
class UpdateReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        KLog.init(context)
        KLog.i("package replaced (versionCode ${BuildConfig.VERSION_CODE}), overlay permission=${Settings.canDrawOverlays(context)}, starting MainActivity")
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, "update"),
            )
        } catch (e: RuntimeException) {
            KLog.e("start of MainActivity after update failed", e)
        }
    }
}
