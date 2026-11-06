package com.youngmin.signagekiosk

import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests. Plan kiosk-boot-autoupdate B5/B6, D11.

/** Which release the TV follows: `/kiosk/<wire>/` on the page's server; [OFF] never checks. */
enum class UpdateChannel(val wire: String) {
    STABLE("stable"),
    CANARY("canary"),
    OFF("off");

    companion object {
        fun fromWire(raw: String?): UpdateChannel? {
            val s = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == s }
        }
    }
}

/** `version.properties` published next to the APK (written by `scripts/make-version-file.sh`). */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
) {
    companion object {
        const val MAX_APK_BYTES = 20L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        // A bare file name: the APK is always fetched from the manifest's own directory.
        private val APK_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.apk$")
        private const val MAX_VERSION_NAME = 32

        /** Null when any field is missing or invalid; values are trimmed, sha256 must be lowercase hex. */
        fun parse(p: Properties): UpdateManifest? {
            val code = p.getProperty("versionCode")?.trim()?.toIntOrNull() ?: return null
            if (code <= 0) return null
            val name = p.getProperty("versionName")?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_VERSION_NAME) return null
            val apk = p.getProperty("apk")?.trim().orEmpty()
            if (!APK_NAME.matches(apk)) return null
            val sha = p.getProperty("sha256")?.trim().orEmpty()
            if (!SHA256.matches(sha)) return null
            val size = p.getProperty("size")?.trim()?.toLongOrNull() ?: return null
            if (size <= 0 || size > MAX_APK_BYTES) return null
            return UpdateManifest(code, name, apk, sha, size)
        }
    }
}

object UpdateUrls {
    const val MANIFEST_FILE = "version.properties"

    /** `<origin of the page>/kiosk/<channel>/version.properties`; null for [UpdateChannel.OFF] or a bad page URL. */
    fun manifestUrl(pageUrl: String, channel: UpdateChannel): String? {
        if (channel == UpdateChannel.OFF) return null
        val origin = ClientLogContract.originOf(pageUrl) ?: return null
        return "$origin/kiosk/${channel.wire}/$MANIFEST_FILE"
    }

    /** The APK sits in the manifest's directory ([UpdateManifest.apk] is a validated bare file name). */
    fun apkUrl(manifestUrl: String, manifest: UpdateManifest): String =
        manifestUrl.substringBeforeLast('/') + "/" + manifest.apk
}

/** Persisted between checks and processes (ConfigStore). 0 = never. */
data class UpdateState(
    /** Install failure / pending user action: no install before this wall-clock time. */
    val backoffUntilMs: Long = 0L,
    /** Same-code guard: the last verify failure, for this manifest code only. */
    val verifyFailedCode: Int = 0,
    val verifyFailedAtMs: Long = 0L,
)

/** Conditions read from the Android layer at decision time (B6 a–d; [channelOff] = switched to `off` meanwhile). */
data class UpdateGates(
    val canDrawOverlays: Boolean,
    val canRequestInstalls: Boolean,
    val settingsOpen: Boolean,
    val restarting: Boolean,
    val channelOff: Boolean = false,
)

sealed class UpdateDecision {
    object Install : UpdateDecision()
    data class Skip(val reason: String) : UpdateDecision()
}

object UpdatePolicy {
    const val CHECK_START_DELAY_MS = 2 * 60_000L
    const val CHECK_INTERVAL_MS = 30 * 60_000L
    const val INSTALL_FAILURE_BACKOFF_MS = 6 * 3_600_000L
    const val PENDING_USER_ACTION_BACKOFF_MS = 24 * 3_600_000L
    const val SAME_CODE_GUARD_MS = 24 * 3_600_000L
    /** A deadline further ahead than this was written under a wrong clock and is ignored. */
    const val MAX_DEADLINE_AHEAD_MS = 24 * 3_600_000L

    // Skip reasons, also logged as `app_update reason=…`.
    const val UP_TO_DATE = "up_to_date"
    const val CHANNEL_OFF = "channel_off"
    const val NO_OVERLAY = "no_overlay"
    const val NO_INSTALL_PERMISSION = "no_install_permission"
    const val SETTINGS_OPEN = "settings_open"
    const val RESTARTING = "restarting"
    const val BACKOFF = "backoff"
    const val SAME_CODE_GUARD = "same_code_guard"

    /**
     * Whether to download + install [m]. Order: not newer → up to date; gates (a)–(d); install-failure /
     * pending-user-action backoff; same-code guard (e). Fetch errors never reach here, so they never back off.
     */
    fun decide(installedCode: Long, m: UpdateManifest, nowMs: Long, st: UpdateState, g: UpdateGates): UpdateDecision {
        if (m.versionCode.toLong() <= installedCode) return UpdateDecision.Skip(UP_TO_DATE)
        if (g.channelOff) return UpdateDecision.Skip(CHANNEL_OFF)
        if (!g.canDrawOverlays) return UpdateDecision.Skip(NO_OVERLAY)
        if (!g.canRequestInstalls) return UpdateDecision.Skip(NO_INSTALL_PERMISSION)
        if (g.settingsOpen) return UpdateDecision.Skip(SETTINGS_OPEN)
        if (g.restarting) return UpdateDecision.Skip(RESTARTING)
        if (deadlineActive(st.backoffUntilMs, nowMs)) return UpdateDecision.Skip(BACKOFF)
        if (st.verifyFailedCode == m.versionCode && deadlineActive(st.verifyFailedAtMs + SAME_CODE_GUARD_MS, nowMs)) {
            return UpdateDecision.Skip(SAME_CODE_GUARD)
        }
        return UpdateDecision.Install
    }

    /** True while [deadlineMs] is ahead of [nowMs], unless it is implausibly far ahead (wrong clock). */
    fun deadlineActive(deadlineMs: Long, nowMs: Long): Boolean {
        val ahead = deadlineMs - nowMs
        return ahead > 0 && ahead <= MAX_DEADLINE_AHEAD_MS
    }

    /** Never shortens a longer backoff that is still active (abandoning a pending session reports ABORTED too). */
    fun afterInstallFailure(st: UpdateState, nowMs: Long): UpdateState {
        val sixHours = nowMs + INSTALL_FAILURE_BACKOFF_MS
        val keep = deadlineActive(st.backoffUntilMs, nowMs) && st.backoffUntilMs > sixHours
        return st.copy(backoffUntilMs = if (keep) st.backoffUntilMs else sixHours)
    }

    fun afterPendingUserAction(st: UpdateState, nowMs: Long) =
        st.copy(backoffUntilMs = nowMs + PENDING_USER_ACTION_BACKOFF_MS)

    fun afterVerifyFailure(st: UpdateState, code: Int, nowMs: Long) =
        st.copy(verifyFailedCode = code, verifyFailedAtMs = nowMs)

    /**
     * Checks the downloaded file against the manifest before any install session is opened. Returns the
     * failure reason (logged as `verify_failed reason=…`) or null when it may be installed. A null archive
     * package/code means the APK could not be parsed.
     */
    fun verifyDownload(
        m: UpdateManifest,
        actualSize: Long,
        actualSha256: String,
        archivePackage: String?,
        archiveVersionCode: Long?,
        ownPackage: String,
    ): String? = when {
        actualSize != m.size -> "size"
        !actualSha256.equals(m.sha256, ignoreCase = true) -> "sha256"
        archivePackage == null || archiveVersionCode == null -> "unparsable"
        archivePackage != ownPackage -> "package"
        archiveVersionCode != m.versionCode.toLong() -> "version_code"
        else -> null
    }
}

/** Install-session status handling (UpdateResultReceiver), kept free of android.* so it is JVM-tested. */
object UpdateResult {
    enum class Outcome { SUCCESS, PENDING_USER_ACTION, ABORTED, FAILURE }

    const val PENDING_USER_ACTION_NAME = "pending_user_action"

    fun nextState(o: Outcome, st: UpdateState, nowMs: Long): UpdateState = when (o) {
        Outcome.SUCCESS -> st
        Outcome.PENDING_USER_ACTION -> UpdatePolicy.afterPendingUserAction(st, nowMs)
        Outcome.ABORTED, Outcome.FAILURE -> UpdatePolicy.afterInstallFailure(st, nowMs)
    }

    /** Every outcome but success means the new version is not coming: forget the pending code. */
    fun clearsPending(o: Outcome): Boolean = o != Outcome.SUCCESS

    /**
     * Whether to overwrite the stored, not yet reported result. The ABORTED that follows our own abandon of a
     * pending session must not hide the `pending_user_action` record of the same version.
     */
    fun shouldRecord(o: Outcome, storedStatus: String?, storedCode: Int, code: Int): Boolean =
        !(o == Outcome.ABORTED && storedStatus == PENDING_USER_ACTION_NAME && storedCode == code)
}

/** `start` / `prev_reason` of `app_start` (B5). */
data class AppStartInfo(val start: String, val prevReason: String?, val pendingNotInstalled: Boolean)

object AppStart {
    /**
     * [extraStart] = EXTRA_START of the launching intent ("boot", "update"); [prevExitReason] = reason saved by the
     * previous process (restart, crash, exit); [pendingUpdateCode] = version the previous process was installing (0 =
     * none). The update wins over an exit reason: a restart racing a slow install must not hide the update.
     */
    fun derive(extraStart: String?, prevExitReason: String?, pendingUpdateCode: Int, versionCode: Int): AppStartInfo {
        val updated = pendingUpdateCode != 0 && pendingUpdateCode == versionCode
        val start = extraStart ?: when {
            updated -> "update"
            prevExitReason != null -> "restart"
            else -> "launch"
        }
        val prevReason = if (updated) "update_$pendingUpdateCode" else prevExitReason
        return AppStartInfo(start, prevReason, pendingNotInstalled = pendingUpdateCode != 0 && !updated)
    }
}

/** Sends identical consecutive outcomes (e.g. `skip up_to_date` every 30 min) only once. */
class OutcomeDedupe {
    private var last: String? = null

    /** Non-deduped events (download, commit) always go out and reset the memory. */
    fun shouldSend(stage: String, fields: Map<String, Any?>, dedupe: Boolean): Boolean {
        val key = "$stage $fields"
        if (dedupe && key == last) return false
        last = key
        return true
    }
}

object UpdateIo {
    /**
     * Copies at most [max] bytes from [input] to [out], hashing exactly the bytes written. Returns the number of bytes
     * the source offered up to the point it went over [max] (so a longer source returns > [max] and fails the size
     * check). Throws [java.io.InterruptedIOException] when the thread is interrupted (Updater closed).
     */
    fun copyCapped(input: InputStream, out: OutputStream, max: Long, digest: MessageDigest): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("update download interrupted")
            val n = input.read(buf)
            if (n < 0) break
            total += n
            // Never write more than announced: a wrong or hostile server cannot fill the disk.
            if (total > max) break
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        return total
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
