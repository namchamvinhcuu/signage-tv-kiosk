package com.youngmin.signagekiosk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * SPEC R9 fallback for TVs that never deliver BOOT_COMPLETED or reset the overlay appop (TCL in the sibling kiosk
 * project): the idea is that a service the SYSTEM binds at boot may start an activity where [BootReceiver] cannot
 * (Android 10+ background-start block). That is HYPOTHESIS here: measured once on the sibling's TCL, unverified on MiTV
 * and not in the SDK sources. It registers for no events and reads no content (res/xml/kiosk_start_service.xml); it
 * only opens [MainActivity], and only on a connect shortly after device boot ([BootStartPolicy]).
 * Inert until an operator enables it on a TV: `settings put secure enabled_accessibility_services
 * com.youngmin.signagekiosk/.KioskStartService` (this REPLACES the whole list: append to the existing value if other
 * services must stay) and `settings put secure accessibility_enabled 1`.
 * Behaviour on MiTV is UNMEASURED; on the sibling project's TCL a cold boot start was measured once and the rebind
 * after a process death was not.
 */
class KioskStartService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        KLog.init(this)
        val uptimeMs = SystemClock.elapsedRealtime()
        val inFront = FrontWatch.inFront
        if (!BootStartPolicy.shouldStart(uptimeMs, inFront)) {
            KLog.i("kiosk start service connected, not a boot start (uptime_ms=$uptimeMs, kiosk_in_front=$inFront)")
            return
        }
        KLog.i("kiosk start service connected shortly after boot, starting MainActivity (uptime_ms=$uptimeMs)")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, "boot"),
            )
        } catch (e: RuntimeException) {
            KLog.e("kiosk start service: start of MainActivity failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
