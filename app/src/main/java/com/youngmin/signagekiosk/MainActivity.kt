package com.youngmin.signagekiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.Instant

/**
 * Full-screen kiosk WebView on the signage page (SPEC R1–R14): recovers from renderer death and hangs,
 * restarts its own process periodically / on low memory / after repeated recoveries, logs locally + remotely.
 * All timers run on the main looper and only while the activity is started (foreground).
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val recovery = RecoveryPolicy()
    private val hang = HangDetector()
    private val backTaps = TapCounter()

    private lateinit var store: ConfigStore
    private lateinit var config: KioskConfig
    private lateinit var remote: RemoteLogger
    private lateinit var root: FrameLayout

    private var webView: WebView? = null
    private var settingsDialog: AlertDialog? = null
    private var blankPoster: Bitmap? = null
    private var restarting = false
    /** Between onStart and onStop. No WebView is built and no restart is started while stopped. */
    private var started = false
    /** Exit reason of the previous process (EscalationGuard). */
    private var prevExitReason: String? = null

    private val watchdogTick = object : Runnable {
        override fun run() {
            onWatchdogTick()
            if (!restarting) handler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private val statusTick = object : Runnable {
        override fun run() {
            logStatus()
            handler.postDelayed(this, STATUS_MS)
        }
    }
    private val recreateWebView = Runnable { createWebView() }
    private val dismissSettings = Runnable {
        KLog.i("settings dialog closed after $SETTINGS_IDLE_MS ms without input")
        settingsDialog?.dismiss()
    }
    private val retryLoad = Runnable {
        KLog.i("retrying main-frame load")
        webView?.loadUrl(config.url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KLog.init(this)
        store = ConfigStore(this)
        installCrashLogger(store)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        config = store.load()
        remote = RemoteLogger(BuildConfig.VERSION_NAME)
        remote.setPageUrl(config.url)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        logAppStart()
        // A system re-creation (savedInstanceState != null) replays the ORIGINAL launch intent, whose
        // extras may be older than a later settings-dialog change.
        if (savedInstanceState == null) applyIntentConfig(intent, reload = false)
        // The WebView itself is built in onStart.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentConfig(intent, reload = true)
    }

    override fun onStart() {
        super.onStart()
        started = true
        webView?.onResume()
        // First start, or the renderer died / a recreate was pending while stopped.
        if (webView == null) createWebView()
        hang.reset()
        handler.postDelayed(watchdogTick, WATCHDOG_MS)
        handler.postDelayed(statusTick, STATUS_MS)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(watchdogTick)
        handler.removeCallbacks(statusTick)
        webView?.onPause()
        super.onStop()
    }

    override fun onPause() {
        // R14: keep the TV logged in across restarts.
        flushCookies()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        settingsDialog?.dismiss()
        settingsDialog = null
        webView?.let { discardWebView(it) }
        remote.shutdown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_RUNNING_* are not delivered since API 34 (verified in SDK sources); log what does arrive.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            KLog.i("onTrimMemory UI_HIDDEN")
        } else {
            remote.event("app_trim", mapOf("level" to level))
        }
    }

    // ---- WebView lifecycle (R1, R2, R13) -------------------------------------------------------------

    private fun createWebView() {
        handler.removeCallbacks(recreateWebView)
        // While stopped, onStart builds it (no heavy page loading behind other apps).
        if (webView != null || restarting || !started) return
        val wv = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // e.g. the WebView package is being updated
            KLog.e("WebView creation failed", e)
            remote.event("app_error", mapOf("where" to "webview_create", "error" to e.javaClass.name, "msg" to e.message))
            handler.postDelayed(recreateWebView, LOAD_RETRY_MS)
            return
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.userAgentString = "${wv.settings.userAgentString} SignageKiosk/${BuildConfig.VERSION_NAME}"
        wv.webViewClient = KioskWebViewClient()
        wv.webChromeClient = KioskChromeClient()
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        hang.reset()
        wv.requestFocus()
        loadConfiguredUrl()
    }

    private fun loadConfiguredUrl() {
        handler.removeCallbacks(retryLoad)
        webView?.loadUrl(config.url)
    }

    /** R2: a dead WebView only gets removed and destroyed — nothing else is called on it. */
    private fun discardWebView(view: WebView) {
        if (view === webView) {
            webView = null
            handler.removeCallbacks(retryLoad)
        }
        root.removeView(view)
        try {
            view.destroy()
        } catch (e: RuntimeException) {
            KLog.e("WebView.destroy failed", e)
        }
    }

    /** One recovery (R2 renderer death / R7 hang): recreate after 3 s, or escalate to a restart (R3). */
    private fun recover(reason: String) {
        webView?.let { discardWebView(it) }
        hang.reset()
        val action = recovery.onRecovery(SystemClock.elapsedRealtime())
        if (action == RecoveryPolicy.Action.RESTART && EscalationGuard.restartAllowed(prevExitReason, uptimeMs())) {
            restartProcess(EscalationGuard.ESCALATION_PREFIX + reason)
            return
        }
        val suppressed = action == RecoveryPolicy.Action.RESTART
        remote.event(
            "app_recreate",
            mapOf("reason" to reason, "recoveries" to recovery.total, "restart_suppressed" to suppressed),
        )
        handler.postDelayed(recreateWebView, if (suppressed) SUPPRESSED_RECREATE_DELAY_MS else RECREATE_DELAY_MS)
    }

    private inner class KioskWebViewClient : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val current = view === webView
            remote.event(
                "app_render_gone",
                mapOf(
                    "did_crash" to detail.didCrash(),
                    "priority" to detail.rendererPriorityAtExit(),
                    "current" to current,
                    "started" to started,
                ),
            )
            // While stopped (LMK kills background renderers) it is not a page fault: no recovery is counted
            // and onStart builds a new WebView, so no restart or background activity start happens here.
            if (current && started) recover("render_gone") else discardWebView(view)
            return true
        }

        // R8: one pending retry at a time.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || view !== webView) return
            remote.event(
                "app_load_error",
                mapOf(
                    "code" to error.errorCode,
                    "desc" to error.description?.toString(),
                    "url" to ClientLogContract.redactUrl(request.url?.toString()),
                ),
            )
            handler.removeCallbacks(retryLoad)
            handler.postDelayed(retryLoad, LOAD_RETRY_MS)
        }
    }

    private inner class KioskChromeClient : WebChromeClient() {
        // R1: no grey default poster before a video starts.
        override fun getDefaultVideoPoster(): Bitmap =
            blankPoster ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { blankPoster = it }
    }

    // ---- Watchdog: hang probe (R7), periodic + low-memory restart (R5, R6) ---------------------------

    private fun onWatchdogTick() {
        if (restarting) return
        val mem = memoryInfo()
        val availMb = mem.availMem / MB
        val reason = RestartPolicy.decide(uptimeMs(), config.restartMin, config.lowMemMb, mem.lowMemory, availMb)
        if (reason != null) {
            restartProcess(reason.wire, mapOf("avail_mb" to availMb, "low_mem" to mem.lowMemory))
            return
        }
        val wv = webView ?: return
        when (val t = hang.tick()) {
            is HangDetector.Tick.Probe -> try {
                wv.evaluateJavascript("1") { hang.onAnswer(t.token) }
            } catch (e: RuntimeException) {
                KLog.e("hang probe failed", e)
            }
            HangDetector.Tick.Hung -> {
                remote.event("app_hang", mapOf("recoveries" to recovery.total))
                recover("hang")
            }
        }
    }

    // ---- Process restart (R4) ------------------------------------------------------------------------

    private fun restartProcess(reason: String, extra: Map<String, Any?> = emptyMap()) {
        if (restarting) return
        restarting = true
        // The POST below dies with the process most of the time; the saved reason is logged on the next start.
        remote.event("app_restart", mapOf("reason" to reason, "up_min" to uptimeMs() / 60_000L, "recoveries" to recovery.total) + extra)
        store.saveExitReason(reason, System.currentTimeMillis())
        flushCookies()
        handler.removeCallbacksAndMessages(null)
        RestartActivity.trigger(this)
    }

    private fun exitApp() {
        KLog.i("exit requested from settings")
        store.saveExitReason("exit_by_user", System.currentTimeMillis())
        flushCookies()
        handler.removeCallbacksAndMessages(null)
        finishAndRemoveTask()
        Runtime.getRuntime().exit(0)
    }

    /** CookieManager.getInstance throws while the WebView package is missing or being updated. */
    private fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: RuntimeException) {
            KLog.w("cookie flush failed", e)
        }
    }

    // ---- Configuration (R10, R14) --------------------------------------------------------------------

    private fun applyIntentConfig(intent: Intent?, reload: Boolean) {
        if (intent == null) return
        val keys = listOf(EXTRA_URL, EXTRA_RESTART_MIN, EXTRA_LOW_MEM_MB).filter { intent.hasExtra(it) }
        if (keys.isEmpty()) return
        var next = config
        val rejected = mutableListOf<String>()
        if (intent.hasExtra(EXTRA_URL)) {
            val url = ConfigValidator.validateUrl(intent.getStringExtra(EXTRA_URL))
            if (url == null) rejected += EXTRA_URL else next = next.copy(url = url)
        }
        if (intent.hasExtra(EXTRA_RESTART_MIN)) {
            val n = intExtra(intent, EXTRA_RESTART_MIN)?.let(ConfigValidator::normalizeRestartMin)
            if (n == null) rejected += EXTRA_RESTART_MIN else next = next.copy(restartMin = n)
        }
        if (intent.hasExtra(EXTRA_LOW_MEM_MB)) {
            val n = intExtra(intent, EXTRA_LOW_MEM_MB)?.let(ConfigValidator::normalizeLowMemMb)
            if (n == null) rejected += EXTRA_LOW_MEM_MB else next = next.copy(lowMemMb = n)
        }
        // Consume the extras so a later re-delivery of this intent does not re-apply them.
        keys.forEach { intent.removeExtra(it) }
        applyConfig(next, "intent", rejected, forceReload = false, reloadAllowed = reload)
    }

    /** Accepts `--ei` (Int) and `--es` (String) for numbers. */
    private fun intExtra(intent: Intent, key: String): Int? {
        @Suppress("DEPRECATION")
        return when (val v = intent.extras?.get(key)) {
            is Int -> v
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun applyConfig(
        next: KioskConfig,
        source: String,
        rejected: List<String>,
        forceReload: Boolean,
        reloadAllowed: Boolean = true,
    ) {
        val urlChanged = next.url != config.url
        if (next != config) {
            if (!store.save(next)) KLog.w("config save did not commit")
            config = next
            remote.setPageUrl(next.url)
        }
        remote.event(
            "app_config",
            mapOf(
                "source" to source,
                "url" to ClientLogContract.redactUrl(next.url),
                "restart_min" to next.restartMin,
                "low_mem_mb" to next.lowMemMb,
                "url_changed" to urlChanged,
                "rejected" to rejected.joinToString(",").ifEmpty { null },
            ),
        )
        flushCookies()
        if (reloadAllowed && (urlChanged || forceReload)) {
            if (webView == null) createWebView() else loadConfiguredUrl()
        }
    }

    // ---- Remote keys + settings dialog (R11) ---------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Never navigate back: the checkin page is a hash SPA.
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && backTaps.onPress(event.eventTime)) {
                    showSettings()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_UP) showSettings()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSettings() {
        if (settingsDialog?.isShowing == true || isFinishing || restarting) return
        val urlInput = EditText(this).apply {
            setText(config.url)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val restartInput = EditText(this).apply {
            setText(config.restartMin.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val lowMemInput = EditText(this).apply {
            setText(config.lowMemMb.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label(R.string.settings_url))
            addView(urlInput)
            addView(label(R.string.settings_restart_min))
            addView(restartInput)
            addView(label(R.string.settings_low_mem_mb))
            addView(lowMemInput)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_title)
            .setView(form)
            .setPositiveButton(R.string.settings_save, null)
            .setNeutralButton(R.string.settings_exit) { _, _ -> exitApp() }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here so an invalid value keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveSettings(urlInput.text.toString(), restartInput.text.toString(), lowMemInput.text.toString())) {
                    dialog.dismiss()
                }
            }
        }
        // An accidental triple-BACK must not leave the dialog over the signage: close it when idle.
        // Keys consumed by the on-screen IME never reach the dialog, so text changes re-arm the timer too.
        val rearm: () -> Unit = {
            handler.removeCallbacks(dismissSettings)
            handler.postDelayed(dismissSettings, SETTINGS_IDLE_MS)
        }
        dialog.setOnKeyListener { _, _, _ ->
            rearm()
            false
        }
        val typing = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rearm()
        }
        listOf(urlInput, restartInput, lowMemInput).forEach { it.addTextChangedListener(typing) }
        dialog.setOnDismissListener {
            handler.removeCallbacks(dismissSettings)
            settingsDialog = null
            webView?.requestFocus()
        }
        settingsDialog = dialog
        KLog.i("settings dialog opened")
        dialog.show()
        handler.postDelayed(dismissSettings, SETTINGS_IDLE_MS)
    }

    private fun label(res: Int) = TextView(this).apply { setText(res) }

    private fun saveSettings(urlText: String, restartText: String, lowMemText: String): Boolean {
        val url = ConfigValidator.validateUrl(urlText)
        if (url == null) {
            Toast.makeText(this, R.string.settings_invalid_url, Toast.LENGTH_LONG).show()
            return false
        }
        val restartMin = restartText.trim().toIntOrNull()?.let(ConfigValidator::normalizeRestartMin)
        val lowMemMb = lowMemText.trim().toIntOrNull()?.let(ConfigValidator::normalizeLowMemMb)
        if (restartMin == null || lowMemMb == null) {
            Toast.makeText(this, R.string.settings_invalid_number, Toast.LENGTH_LONG).show()
            return false
        }
        applyConfig(KioskConfig(url, restartMin, lowMemMb), "dialog", emptyList(), forceReload = true)
        return true
    }

    // ---- Logs (R12, §3) ------------------------------------------------------------------------------

    private fun logAppStart() {
        val prev = store.takeExitReason()
        prevExitReason = prev?.reason
        val start = intent?.getStringExtra(EXTRA_START) ?: if (prev != null) "restart" else "launch"
        remote.event(
            "app_start",
            mapOf(
                "start" to start,
                "prev_reason" to prev?.reason,
                "prev_at" to prev?.let { Instant.ofEpochMilli(it.atWallMs).toString() },
                "webview" to WebView.getCurrentWebViewPackage()?.versionName,
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "overlay" to Settings.canDrawOverlays(this),
                "url" to ClientLogContract.redactUrl(config.url),
                "restart_min" to config.restartMin,
                "low_mem_mb" to config.lowMemMb,
            ),
        )
    }

    private fun logStatus() {
        val mem = memoryInfo()
        remote.event(
            "app_status",
            mapOf(
                "avail_mb" to mem.availMem / MB,
                "total_mb" to mem.totalMem / MB,
                "low_mem" to mem.lowMemory,
                "threshold_mb" to mem.threshold / MB,
                // Own process only — the sandboxed renderer is a separate process.
                "pss_mb" to Debug.getPss() / 1024,
                "up_min" to uptimeMs() / 60_000L,
                "recoveries" to recovery.total,
                "restart_min" to config.restartMin,
                "low_mem_mb" to config.lowMemMb,
                "webview" to (webView != null),
            ),
        )
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }

    private fun uptimeMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_RESTART_MIN = "restart_min"
        const val EXTRA_LOW_MEM_MB = "low_mem_mb"
        /** Why the activity was started ("boot" from BootReceiver); otherwise derived. */
        const val EXTRA_START = "start"

        private const val WATCHDOG_MS = 60_000L
        private const val STATUS_MS = 5 * 60_000L
        private const val RECREATE_DELAY_MS = 3_000L
        private const val LOAD_RETRY_MS = 30_000L
        private const val SUPPRESSED_RECREATE_DELAY_MS = 60_000L
        private const val SETTINGS_IDLE_MS = 120_000L
        private const val MB = 1024L * 1024L

        private var crashLoggerInstalled = false

        /** Logs an uncaught exception locally and saves it as the exit reason, then lets the process die. */
        private fun installCrashLogger(store: ConfigStore) {
            if (crashLoggerInstalled) return
            crashLoggerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                try {
                    KLog.e("uncaught exception on ${thread.name}", e)
                    store.saveExitReason("crash_${e.javaClass.simpleName}", System.currentTimeMillis())
                } catch (t: Throwable) {
                    android.util.Log.e("SignageKiosk", "crash logger failed", t)
                }
                previous?.uncaughtException(thread, e)
            }
        }
    }
}
