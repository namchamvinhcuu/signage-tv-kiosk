package com.youngmin.signagekiosk

import java.io.StringReader
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val HOUR = 3_600_000L
private const val NOW = 1_800_000_000_000L
private val SHA = "0123456789abcdef".repeat(4)

private fun manifest(
    code: Int = 201,
    name: String = "1.2.0",
    apk: String = "signage-kiosk-201.apk",
    sha: String = SHA,
    size: Long = 5_000_000L,
) = UpdateManifest(code, name, apk, sha, size)

class UpdateChannelTest {

    @Test
    fun `wire values map to their channel case insensitive and trimmed`() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromWire("stable"))
        assertEquals(UpdateChannel.CANARY, UpdateChannel.fromWire("canary"))
        assertEquals(UpdateChannel.OFF, UpdateChannel.fromWire("off"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromWire("  STABLE \n"))
        assertEquals(UpdateChannel.CANARY, UpdateChannel.fromWire("Canary"))
        assertEquals(UpdateChannel.OFF, UpdateChannel.fromWire("\tOFF"))
    }

    @Test
    fun `unknown blank and null wire values give null`() {
        for (raw in listOf(null, "", "   ", "beta", "stable1", "st able", "on", "false", "0", "stable/../x")) {
            assertNull("expected null for '$raw'", UpdateChannel.fromWire(raw))
        }
    }
}

class UpdateManifestParseTest {

    private fun props(vararg overrides: Pair<String, String?>): Properties {
        val base = linkedMapOf<String, String?>(
            "versionCode" to "201",
            "versionName" to "1.2.0",
            "apk" to "signage-kiosk-201.apk",
            "sha256" to SHA,
            "size" to "5000000",
        )
        for ((k, v) in overrides) base[k] = v
        return Properties().apply { for ((k, v) in base) if (v != null) setProperty(k, v) }
    }

    private fun assertRejected(vararg overrides: Pair<String, String?>) {
        assertNull("expected rejection of ${overrides.toList()}", UpdateManifest.parse(props(*overrides)))
    }

    @Test
    fun `valid manifest parses into its fields`() {
        assertEquals(manifest(), UpdateManifest.parse(props()))
    }

    @Test
    fun `a file in the real properties format parses and values are trimmed`() {
        val text = "# written by make-version-file.sh\n" +
            "versionCode = 202 \n" +
            "versionName=  1.3.0-canary\t\n" +
            "apk=signage-kiosk_1.3.0.apk   \n" +
            "sha256=$SHA  \n" +
            "size=20971520\n"
        val p = Properties().apply { load(StringReader(text)) }
        assertEquals(
            UpdateManifest(202, "1.3.0-canary", "signage-kiosk_1.3.0.apk", SHA, UpdateManifest.MAX_APK_BYTES),
            UpdateManifest.parse(p),
        )
    }

    @Test
    fun `every missing field rejects the manifest`() {
        for (key in listOf("versionCode", "versionName", "apk", "sha256", "size")) {
            assertNull("missing $key must be rejected", UpdateManifest.parse(props(key to null)))
        }
        assertNull(UpdateManifest.parse(Properties()))
    }

    @Test
    fun `versionCode must be a positive int`() {
        assertEquals(1, UpdateManifest.parse(props("versionCode" to "1"))!!.versionCode)
        assertEquals(Int.MAX_VALUE, UpdateManifest.parse(props("versionCode" to "2147483647"))!!.versionCode)
        for (bad in listOf("0", "-1", "", "  ", "abc", "1.0", "2147483648", "0x10", "1 2")) {
            assertRejected("versionCode" to bad)
        }
    }

    @Test
    fun `versionName must be 1 to 32 chars after trimming`() {
        val name32 = "v".repeat(32)
        assertEquals(name32, UpdateManifest.parse(props("versionName" to "  $name32  "))!!.versionName)
        assertEquals("1", UpdateManifest.parse(props("versionName" to "1"))!!.versionName)
        assertRejected("versionName" to "v".repeat(33))
        assertRejected("versionName" to "")
        assertRejected("versionName" to "   ")
    }

    @Test
    fun `apk accepts a bare file name ending in apk`() {
        for (ok in listOf("a.apk", "Kiosk-1.2.0_release.apk", "9.apk", "a" + "b".repeat(99) + ".apk")) {
            assertEquals(ok, UpdateManifest.parse(props("apk" to ok))!!.apk)
        }
        assertEquals("x.apk", UpdateManifest.parse(props("apk" to "  x.apk \t"))!!.apk)
    }

    @Test
    fun `apk rejects path traversal separators urls and wrong names`() {
        for (bad in listOf(
            "../x.apk",
            "..",
            "a/b.apk",
            "/x.apk",
            "a\\b.apk",
            "..\\x.apk",
            "%2e%2e%2fx.apk",
            "http://evil/x.apk",
            "//evil/x.apk",
            ".apk",
            ".hidden.apk",
            "-x.apk",
            "x.APK",
            "x.apk.exe",
            "x.apk?y=1",
            "x.apk#f",
            "x y.apk",
            "x",
            "",
            "a" + "b".repeat(100) + ".apk", // 105 chars, over the 1+99+4 cap
        )) {
            assertRejected("apk" to bad)
        }
    }

    @Test
    fun `sha256 must be exactly 64 lowercase hex chars`() {
        assertEquals(SHA, UpdateManifest.parse(props("sha256" to " $SHA "))!!.sha256)
        for (bad in listOf(
            SHA.uppercase(),
            SHA.dropLast(1) + "A",
            SHA.dropLast(1),
            SHA + "0",
            SHA.dropLast(1) + "g",
            "",
            "sha256:$SHA",
        )) {
            assertRejected("sha256" to bad)
        }
    }

    @Test
    fun `size must be in 1 to 20 MiB`() {
        assertEquals(1L, UpdateManifest.parse(props("size" to "1"))!!.size)
        assertEquals(20L * 1024 * 1024, UpdateManifest.parse(props("size" to "20971520"))!!.size)
        for (bad in listOf("0", "-1", "20971521", "", "abc", "1e6", "5,000,000", "1.5", "99999999999999999999")) {
            assertRejected("size" to bad)
        }
    }
}

class UpdateUrlsTest {

    @Test
    fun `manifest url uses the page origin and the channel directory`() {
        assertEquals(
            "http://192.168.0.254:13000/kiosk/stable/version.properties",
            UpdateUrls.manifestUrl("http://192.168.0.254:13000/#checkin", UpdateChannel.STABLE),
        )
        assertEquals(
            "https://signage.example.com/kiosk/canary/version.properties",
            UpdateUrls.manifestUrl("https://signage.example.com/viewer/index.html", UpdateChannel.CANARY),
        )
    }

    @Test
    fun `manifest url drops path query fragment and userinfo but keeps the port`() {
        assertEquals(
            "http://host:8080/kiosk/stable/version.properties",
            UpdateUrls.manifestUrl("  HTTP://user:pw@host:8080/a/b/c.html?token=secret&x=1#checkin?y=2 ", UpdateChannel.STABLE),
        )
    }

    @Test
    fun `channel off gives no manifest url even for a valid page`() {
        assertNull(UpdateUrls.manifestUrl("http://192.168.0.254:13000/", UpdateChannel.OFF))
    }

    @Test
    fun `bad page url gives no manifest url`() {
        for (raw in listOf("", "   ", "not a url", "ftp://host/", "file:///sdcard/x.html", "http://", "http:///x", "/checkin")) {
            assertNull("expected null for '$raw'", UpdateUrls.manifestUrl(raw, UpdateChannel.STABLE))
        }
    }

    @Test
    fun `apk url sits in the manifest directory`() {
        val m = UpdateUrls.manifestUrl("http://192.168.0.254:13000/#checkin", UpdateChannel.CANARY)!!
        assertEquals(
            "http://192.168.0.254:13000/kiosk/canary/signage-kiosk-201.apk",
            UpdateUrls.apkUrl(m, manifest()),
        )
    }
}

class UpdatePolicyDecideTest {

    private val open = UpdateGates(canDrawOverlays = true, canRequestInstalls = true, settingsOpen = false, restarting = false)
    private val allClosed = UpdateGates(canDrawOverlays = false, canRequestInstalls = false, settingsOpen = true, restarting = true)
    private val fresh = UpdateState()

    private fun decide(
        installed: Long = 200,
        m: UpdateManifest = manifest(),
        now: Long = NOW,
        st: UpdateState = fresh,
        g: UpdateGates = open,
    ) = UpdatePolicy.decide(installed, m, now, st, g)

    private fun skip(reason: String) = UpdateDecision.Skip(reason)

    @Test
    fun `newer code with open gates and clean state installs`() {
        assertEquals(UpdateDecision.Install, decide())
    }

    @Test
    fun `equal or older code is up to date`() {
        assertEquals(skip(UpdatePolicy.UP_TO_DATE), decide(installed = 201))
        assertEquals(skip(UpdatePolicy.UP_TO_DATE), decide(installed = 202))
        assertEquals(skip(UpdatePolicy.UP_TO_DATE), decide(installed = Int.MAX_VALUE.toLong() + 1))
        assertEquals(UpdateDecision.Install, decide(installed = 200))
    }

    @Test
    fun `up to date wins over closed gates backoff and guard`() {
        val blocked = UpdateState(backoffUntilMs = NOW + HOUR, verifyFailedCode = 201, verifyFailedAtMs = NOW - HOUR)
        assertEquals(skip(UpdatePolicy.UP_TO_DATE), decide(installed = 201, st = blocked, g = allClosed))
    }

    @Test
    fun `each gate blocks on its own`() {
        assertEquals(skip(UpdatePolicy.NO_OVERLAY), decide(g = open.copy(canDrawOverlays = false)))
        assertEquals(skip(UpdatePolicy.NO_INSTALL_PERMISSION), decide(g = open.copy(canRequestInstalls = false)))
        assertEquals(skip(UpdatePolicy.SETTINGS_OPEN), decide(g = open.copy(settingsOpen = true)))
        assertEquals(skip(UpdatePolicy.RESTARTING), decide(g = open.copy(restarting = true)))
    }

    @Test
    fun `gates are checked in order a b c d`() {
        assertEquals(skip(UpdatePolicy.NO_OVERLAY), decide(g = allClosed))
        assertEquals(skip(UpdatePolicy.NO_INSTALL_PERMISSION), decide(g = allClosed.copy(canDrawOverlays = true)))
        assertEquals(
            skip(UpdatePolicy.SETTINGS_OPEN),
            decide(g = allClosed.copy(canDrawOverlays = true, canRequestInstalls = true)),
        )
    }

    @Test
    fun `a closed gate wins over backoff and same code guard`() {
        val blocked = UpdateState(backoffUntilMs = NOW + HOUR, verifyFailedCode = 201, verifyFailedAtMs = NOW - HOUR)
        assertEquals(skip(UpdatePolicy.RESTARTING), decide(st = blocked, g = open.copy(restarting = true)))
    }

    @Test
    fun `backoff wins over the same code guard`() {
        val both = UpdateState(backoffUntilMs = NOW + HOUR, verifyFailedCode = 201, verifyFailedAtMs = NOW - HOUR)
        assertEquals(skip(UpdatePolicy.BACKOFF), decide(st = both))
    }

    @Test
    fun `backoff deadline boundaries`() {
        assertEquals(skip(UpdatePolicy.BACKOFF), decide(st = UpdateState(backoffUntilMs = NOW + 1)))
        assertEquals(UpdateDecision.Install, decide(st = UpdateState(backoffUntilMs = NOW)))
        assertEquals(UpdateDecision.Install, decide(st = UpdateState(backoffUntilMs = NOW - 1)))
        assertEquals(skip(UpdatePolicy.BACKOFF), decide(st = UpdateState(backoffUntilMs = NOW + 24 * HOUR)))
        // Written under a wrong clock: more than 24 h ahead counts as expired.
        assertEquals(UpdateDecision.Install, decide(st = UpdateState(backoffUntilMs = NOW + 24 * HOUR + 1)))
        assertEquals(UpdateDecision.Install, decide(st = UpdateState(backoffUntilMs = Long.MAX_VALUE)))
    }

    @Test
    fun `channel switched off skips and defaults to on`() {
        assertEquals(false, open.channelOff)
        assertEquals(skip(UpdatePolicy.CHANNEL_OFF), decide(g = open.copy(channelOff = true)))
        assertEquals("channel_off", UpdatePolicy.CHANNEL_OFF)
    }

    @Test
    fun `channel off comes after up to date and before every other gate`() {
        val blocked = UpdateState(backoffUntilMs = NOW + HOUR, verifyFailedCode = 201, verifyFailedAtMs = NOW - HOUR)
        assertEquals(skip(UpdatePolicy.UP_TO_DATE), decide(installed = 201, g = allClosed.copy(channelOff = true)))
        assertEquals(skip(UpdatePolicy.CHANNEL_OFF), decide(st = blocked, g = allClosed.copy(channelOff = true)))
    }

    @Test
    fun `fresh state with code zero never triggers the guard`() {
        assertEquals(UpdateDecision.Install, decide(m = manifest(code = 1), installed = 0))
    }
}

class UpdatePolicyD11Test {

    private val open = UpdateGates(canDrawOverlays = true, canRequestInstalls = true, settingsOpen = false, restarting = false)

    private fun decide(st: UpdateState, now: Long, m: UpdateManifest = manifest()) =
        UpdatePolicy.decide(200, m, now, st, open)

    private val backoff = UpdateDecision.Skip(UpdatePolicy.BACKOFF)
    private val guard = UpdateDecision.Skip(UpdatePolicy.SAME_CODE_GUARD)

    @Test
    fun `backoff and guard durations match D11`() {
        assertEquals(6 * HOUR, UpdatePolicy.INSTALL_FAILURE_BACKOFF_MS)
        assertEquals(24 * HOUR, UpdatePolicy.PENDING_USER_ACTION_BACKOFF_MS)
        assertEquals(24 * HOUR, UpdatePolicy.SAME_CODE_GUARD_MS)
        assertEquals(24 * HOUR, UpdatePolicy.MAX_DEADLINE_AHEAD_MS)
        assertEquals(2 * 60_000L, UpdatePolicy.CHECK_START_DELAY_MS)
        assertEquals(30 * 60_000L, UpdatePolicy.CHECK_INTERVAL_MS)
    }

    @Test
    fun `install failure backs off 6 h for every code`() {
        val st = UpdatePolicy.afterInstallFailure(UpdateState(), NOW)
        assertEquals(NOW + 6 * HOUR, st.backoffUntilMs)
        assertEquals(backoff, decide(st, NOW))
        assertEquals(backoff, decide(st, NOW + 6 * HOUR - 1))
        assertEquals(backoff, decide(st, NOW + HOUR, manifest(code = 999)))
        assertEquals(UpdateDecision.Install, decide(st, NOW + 6 * HOUR))
    }

    @Test
    fun `pending user action backs off 24 h and is not dropped by the clock clamp`() {
        val st = UpdatePolicy.afterPendingUserAction(UpdateState(), NOW)
        assertEquals(NOW + 24 * HOUR, st.backoffUntilMs)
        // Right after recording, the deadline is exactly 24 h ahead: still a valid backoff.
        assertEquals(backoff, decide(st, NOW))
        assertEquals(backoff, decide(st, NOW + 24 * HOUR - 1))
        assertEquals(UpdateDecision.Install, decide(st, NOW + 24 * HOUR))
    }

    // Regression for test-writer finding F1: the ABORTED that follows our abandon of a pending session
    // used to replace the 24 h pending-user-action backoff with 6 h.
    @Test
    fun `install failure after pending user action keeps the 24 h backoff_Fix_F1`() {
        val pending = UpdatePolicy.afterPendingUserAction(UpdateState(), NOW)
        val after = UpdatePolicy.afterInstallFailure(pending, NOW + 1000)
        assertEquals(NOW + 24 * HOUR, after.backoffUntilMs)
        assertEquals(backoff, decide(after, NOW + 7 * HOUR))
    }

    @Test
    fun `install failure keeps a longer active deadline up to the clamp edge`() {
        // Exactly 24 h ahead is still active (clamp is inclusive) and longer than 6 h: kept.
        val edge = UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = NOW + 24 * HOUR), NOW)
        assertEquals(NOW + 24 * HOUR, edge.backoffUntilMs)
        val justLonger = UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = NOW + 6 * HOUR + 1), NOW)
        assertEquals(NOW + 6 * HOUR + 1, justLonger.backoffUntilMs)
    }

    @Test
    fun `install failure replaces an expired wrong clock or shorter deadline with 6 h`() {
        for (old in listOf(0L, NOW - 1, NOW, NOW + 24 * HOUR + 1, Long.MAX_VALUE, NOW + HOUR, NOW + 6 * HOUR)) {
            val after = UpdatePolicy.afterInstallFailure(UpdateState(backoffUntilMs = old), NOW)
            assertEquals("old deadline $old", NOW + 6 * HOUR, after.backoffUntilMs)
        }
    }

    @Test
    fun `verify failure guards that code for 24 h`() {
        val st = UpdatePolicy.afterVerifyFailure(UpdateState(), 201, NOW)
        assertEquals(UpdateState(backoffUntilMs = 0, verifyFailedCode = 201, verifyFailedAtMs = NOW), st)
        assertEquals(guard, decide(st, NOW))
        assertEquals(guard, decide(st, NOW + 24 * HOUR - 1))
        assertEquals(UpdateDecision.Install, decide(st, NOW + 24 * HOUR))
    }

    @Test
    fun `verify failure does not block a different code`() {
        val st = UpdatePolicy.afterVerifyFailure(UpdateState(), 201, NOW)
        assertEquals(UpdateDecision.Install, decide(st, NOW, manifest(code = 202)))
        assertEquals(UpdateDecision.Install, decide(st, NOW, manifest(code = 203)))
    }

    @Test
    fun `verify failure leaves an existing backoff unchanged and adds none`() {
        val after = UpdatePolicy.afterVerifyFailure(UpdateState(backoffUntilMs = NOW + HOUR), 201, NOW)
        assertEquals(NOW + HOUR, after.backoffUntilMs)
        assertEquals(0L, UpdatePolicy.afterVerifyFailure(UpdateState(), 201, NOW).backoffUntilMs)
    }

    @Test
    fun `a newer verify failure replaces the guarded code`() {
        val first = UpdatePolicy.afterVerifyFailure(UpdateState(), 201, NOW)
        val second = UpdatePolicy.afterVerifyFailure(first, 202, NOW + HOUR)
        assertEquals(UpdateDecision.Install, decide(second, NOW + 2 * HOUR, manifest(code = 201)))
        assertEquals(guard, decide(second, NOW + 2 * HOUR, manifest(code = 202)))
    }

    @Test
    fun `install failure and pending action keep the verify guard fields`() {
        val guarded = UpdateState(verifyFailedCode = 201, verifyFailedAtMs = NOW)
        val a = UpdatePolicy.afterInstallFailure(guarded, NOW)
        val b = UpdatePolicy.afterPendingUserAction(guarded, NOW)
        assertEquals(201, a.verifyFailedCode)
        assertEquals(NOW, a.verifyFailedAtMs)
        assertEquals(201, b.verifyFailedCode)
        assertEquals(NOW, b.verifyFailedAtMs)
    }

    @Test
    fun `verify failure time in the future is expired by the clock clamp`() {
        // Recorded under a clock that was ahead: the guard deadline is then more than 24 h away.
        val skewed = UpdateState(verifyFailedCode = 201, verifyFailedAtMs = NOW + 1)
        assertEquals(UpdateDecision.Install, decide(skewed, NOW))
        val farFuture = UpdateState(verifyFailedCode = 201, verifyFailedAtMs = NOW + 365 * 24 * HOUR)
        assertEquals(UpdateDecision.Install, decide(farFuture, NOW))
    }

    @Test
    fun `deadlineActive boundaries`() {
        assertEquals(false, UpdatePolicy.deadlineActive(0L, NOW))
        assertEquals(false, UpdatePolicy.deadlineActive(NOW - 1, NOW))
        assertEquals(false, UpdatePolicy.deadlineActive(NOW, NOW))
        assertEquals(true, UpdatePolicy.deadlineActive(NOW + 1, NOW))
        assertEquals(true, UpdatePolicy.deadlineActive(NOW + 24 * HOUR, NOW))
        assertEquals(false, UpdatePolicy.deadlineActive(NOW + 24 * HOUR + 1, NOW))
    }
}

class UpdateVerifyDownloadTest {

    private val own = "com.youngmin.signagekiosk"
    private val m = manifest()

    private fun verify(
        size: Long = m.size,
        sha: String = SHA,
        pkg: String? = own,
        code: Long? = 201L,
    ) = UpdatePolicy.verifyDownload(m, size, sha, pkg, code, own)

    @Test
    fun `matching download verifies`() {
        assertNull(verify())
    }

    @Test
    fun `sha256 comparison ignores case`() {
        assertNull(verify(sha = SHA.uppercase()))
    }

    @Test
    fun `size mismatch is reported`() {
        assertEquals("size", verify(size = m.size - 1))
        assertEquals("size", verify(size = m.size + 1))
        assertEquals("size", verify(size = 0))
    }

    @Test
    fun `sha256 mismatch is reported`() {
        assertEquals("sha256", verify(sha = SHA.dropLast(1) + "1"))
        assertEquals("sha256", verify(sha = ""))
    }

    @Test
    fun `unparsable archive is reported`() {
        assertEquals("unparsable", verify(pkg = null))
        assertEquals("unparsable", verify(code = null))
        assertEquals("unparsable", verify(pkg = null, code = null))
    }

    @Test
    fun `foreign package is reported`() {
        assertEquals("package", verify(pkg = "com.evil.app"))
        assertEquals("package", verify(pkg = "com.youngmin.signagekiosk.debug"))
    }

    @Test
    fun `version code mismatch is reported`() {
        assertEquals("version_code", verify(code = 202L))
        assertEquals("version_code", verify(code = 200L))
        assertEquals("version_code", verify(code = 201L + (1L shl 32)))
    }

    @Test
    fun `checks run in order size sha256 unparsable package version_code`() {
        assertEquals("size", verify(size = 1, sha = "", pkg = null, code = null))
        assertEquals("sha256", verify(sha = "", pkg = null, code = null))
        assertEquals("unparsable", verify(pkg = null, code = 999L))
        assertEquals("package", verify(pkg = "x", code = 999L))
    }
}
