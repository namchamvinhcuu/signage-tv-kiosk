package com.youngmin.signagekiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-update (plan kiosk-boot-autoupdate B5–B7): reads `<origin>/kiosk/<channel>/version.properties`, downloads a
 * newer APK, checks it, and installs it with a PackageInstaller session that needs no user action (API 31+, the app
 * updates itself). Network and file work run on one background thread; every decision and every [Host] call happens
 * on the main thread, because RemoteLogger is main-thread-only. The install result arrives in [UpdateResultReceiver].
 */
class Updater(context: Context, private val store: ConfigStore, private val host: Host) {

    /** Implemented by MainActivity; always called on the main thread. */
    interface Host {
        fun updateGates(): UpdateGates
        fun logUpdate(stage: String, fields: Map<String, Any?>)
        /** Last moment before the session commits: flush cookies, block process restarts. */
        fun beforeInstall(code: Int)
        /** The session could not be committed: the process is not going to be killed, lift the restart block. */
        fun installFailed()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val apkFile = File(app.cacheDir, APK_NAME)
    private var busy = false
    private var closed = false
    private val dedupe = OutcomeDedupe()

    init {
        // A previous process may have died mid-download or right after commit.
        executor.execute { deleteApk() }
    }

    /** Main thread. One check at a time; [forceUserAction] is honoured in debug builds only (phone test). */
    fun check(pageUrl: String, channel: UpdateChannel, forceUserAction: Boolean = false) {
        if (busy || closed) {
            KLog.i("update check skipped: ${if (closed) "closed" else "a check is already running"}")
            return
        }
        val manifestUrl = UpdateUrls.manifestUrl(pageUrl, channel) ?: run {
            KLog.i("update check skipped: channel ${channel.wire} or page url has no update location")
            return
        }
        busy = true
        executor.execute {
            val outcome = try {
                fetchManifest(manifestUrl)
            } catch (e: Exception) {
                ManifestOutcome.Error(e.javaClass.simpleName)
            }
            onMain { onManifest(manifestUrl, channel, outcome, forceUserAction && BuildConfig.DEBUG) }
        }
    }

    /** Main thread, from onDestroy. Pending results are dropped. */
    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun onManifest(manifestUrl: String, channel: UpdateChannel, outcome: ManifestOutcome, forceUserAction: Boolean) {
        val m = when (outcome) {
            is ManifestOutcome.Error -> return finish("fetch_failed", mapOf("reason" to outcome.reason, "channel" to channel.wire))
            ManifestOutcome.Invalid -> return finish("fetch_failed", mapOf("reason" to "invalid_manifest", "channel" to channel.wire))
            is ManifestOutcome.Ok -> outcome.manifest
        }
        val decision = UpdatePolicy.decide(installedCode(), m, System.currentTimeMillis(), store.loadUpdateState(), host.updateGates())
        if (decision is UpdateDecision.Skip) {
            return finish("skip", mapOf("reason" to decision.reason, "code" to m.versionCode, "channel" to channel.wire))
        }
        log("download", mapOf("code" to m.versionCode, "channel" to channel.wire, "size" to m.size), dedupe = false)
        executor.execute {
            val failure = try {
                download(UpdateUrls.apkUrl(manifestUrl, m), m)
            } catch (e: Exception) {
                deleteApk()
                "download:" + e.javaClass.simpleName
            }
            onMain { onDownloaded(m, failure, forceUserAction) }
        }
    }

    private fun onDownloaded(m: UpdateManifest, failure: String?, forceUserAction: Boolean) {
        if (failure != null) {
            if (failure.startsWith("download:")) {
                // A network error is retried at the next check, without backoff (D11).
                return finish("download_failed", mapOf("reason" to failure.removePrefix("download:"), "code" to m.versionCode))
            }
            store.saveUpdateState(UpdatePolicy.afterVerifyFailure(store.loadUpdateState(), m.versionCode, System.currentTimeMillis()))
            return finish("verify_failed", mapOf("reason" to failure, "code" to m.versionCode))
        }
        // Gates may have changed during the download (settings opened, restart started, appop revoked).
        val decision = UpdatePolicy.decide(installedCode(), m, System.currentTimeMillis(), store.loadUpdateState(), host.updateGates())
        if (decision is UpdateDecision.Skip) {
            executor.execute { deleteApk() }
            return finish("skip", mapOf("reason" to decision.reason, "code" to m.versionCode))
        }
        if (!store.savePendingUpdateCode(m.versionCode)) KLog.w("pending update code not committed")
        host.beforeInstall(m.versionCode)
        log("commit", mapOf("code" to m.versionCode, "user_action" to forceUserAction), dedupe = false)
        executor.execute {
            val error = try {
                install(m, forceUserAction)
                null
            } catch (e: Exception) {
                e.javaClass.simpleName
            } finally {
                deleteApk()
            }
            onMain {
                if (error != null) {
                    host.installFailed()
                    store.takePendingUpdateCode()
                    store.saveUpdateState(UpdatePolicy.afterInstallFailure(store.loadUpdateState(), System.currentTimeMillis()))
                    finish("install_failed", mapOf("reason" to error, "code" to m.versionCode))
                } else {
                    // On success the system kills this process; UpdateResultReceiver records the outcome.
                    busy = false
                }
            }
        }
    }

    private fun finish(stage: String, fields: Map<String, Any?>) {
        busy = false
        log(stage, fields, dedupe = true)
    }

    /** Repeated identical outcomes (e.g. `skip up_to_date` every 30 min) are logged remotely only once. */
    private fun log(stage: String, fields: Map<String, Any?>, dedupe: Boolean) {
        if (!this.dedupe.shouldSend(stage, fields, dedupe)) {
            KLog.i("app_update $stage $fields (repeat)")
            return
        }
        host.logUpdate(stage, fields)
    }

    private fun onMain(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private fun installedCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    // ---- Background thread -------------------------------------------------------------------------

    private sealed class ManifestOutcome {
        data class Ok(val manifest: UpdateManifest) : ManifestOutcome()
        object Invalid : ManifestOutcome()
        data class Error(val reason: String) : ManifestOutcome()
    }

    private fun fetchManifest(url: String): ManifestOutcome {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return ManifestOutcome.Error("http_$code")
            val props = Properties()
            conn.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_MANIFEST_BYTES + 1)
                if (bytes.size > MAX_MANIFEST_BYTES) return ManifestOutcome.Error("manifest_too_large")
                props.load(bytes.inputStream())
            }
            return UpdateManifest.parse(props)?.let { ManifestOutcome.Ok(it) } ?: ManifestOutcome.Invalid
        } finally {
            conn.disconnect()
        }
    }

    /** Null when the file is ready to install, else a verify-failure reason (see [UpdatePolicy.verifyDownload]). */
    private fun download(url: String, m: UpdateManifest): String? {
        deleteApk()
        val digest = MessageDigest.getInstance("SHA-256")
        val total: Long
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("http_$code")
            total = conn.inputStream.use { input ->
                apkFile.outputStream().use { out -> UpdateIo.copyCapped(input, out, m.size, digest) }
            }
        } finally {
            conn.disconnect()
        }
        val sha = UpdateIo.hex(digest.digest())
        val archive = if (total == m.size) app.packageManager.getPackageArchiveInfo(apkFile.path, 0) else null
        @Suppress("DEPRECATION")
        val archiveCode = archive?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val failure = UpdatePolicy.verifyDownload(m, total, sha, archive?.packageName, archiveCode, app.packageName)
        if (failure != null) deleteApk()
        return failure
    }

    private fun install(m: UpdateManifest, forceUserAction: Boolean) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (forceUserAction) PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                )
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(app, UpdateResultReceiver::class.java)
                    .setPackage(app.packageName)
                    .putExtra(UpdateResultReceiver.EXTRA_CODE, m.versionCode)
                // The installer fills in the status extras, so the PendingIntent must be mutable (explicit target).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (abandon: Exception) {
                KLog.w("abandon session $sessionId failed", abandon)
            }
            throw e
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            useCaches = false
            instanceFollowRedirects = false
        }

    private fun deleteApk() {
        if (apkFile.exists() && !apkFile.delete()) KLog.w("could not delete ${apkFile.name}")
    }

    /** InputStream.readNBytes is API 33+. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val APK_NAME = "update.apk"
        private const val MAX_MANIFEST_BYTES = 4 * 1024
    }
}
