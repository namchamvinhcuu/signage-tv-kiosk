package com.youngmin.signagekiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigValidatorTest {

    // --- validateUrl ---

    @Test
    fun `http url with host is accepted and trimmed`() {
        assertEquals(
            "http://192.168.0.254:13000/",
            ConfigValidator.validateUrl("  http://192.168.0.254:13000/ \n"),
        )
    }

    @Test
    fun `https url with path query and fragment is accepted unchanged`() {
        val url = "https://signage.example.com/viewer?x=1#checkin"
        assertEquals(url, ConfigValidator.validateUrl(url))
    }

    @Test
    fun `scheme check is case insensitive and keeps the original text`() {
        assertEquals("HTTP://Host:8080/", ConfigValidator.validateUrl("HTTP://Host:8080/"))
    }

    @Test
    fun `default url passes validation`() {
        assertEquals(KioskConfig.DEFAULT_URL, ConfigValidator.validateUrl(KioskConfig.DEFAULT_URL))
    }

    @Test
    fun `null empty and blank are rejected`() {
        assertNull(ConfigValidator.validateUrl(null))
        assertNull(ConfigValidator.validateUrl(""))
        assertNull(ConfigValidator.validateUrl("   \t"))
    }

    @Test
    fun `non http schemes are rejected`() {
        for (raw in listOf(
            "ftp://host/file",
            "javascript:alert(1)",
            "file:///sdcard/index.html",
            "content://com.example/x",
            "data:text/html,<b>x</b>",
            "host:13000",
        )) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    @Test
    fun `http without a host is rejected`() {
        for (raw in listOf("http://", "http:///path", "http:/path", "https://:8080/", "http:")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    @Test
    fun `garbage and relative input is rejected`() {
        for (raw in listOf("not a url", "192.168.0.254:13000", "/checkin", "http://exa mple.com/", "%%%")) {
            assertNull("expected rejection of $raw", ConfigValidator.validateUrl(raw))
        }
    }

    // --- normalizeRestartMin (0 = off, negative = rejected, else 15..1440) ---

    @Test
    fun `restartMin zero means off`() {
        assertEquals(0, ConfigValidator.normalizeRestartMin(0))
    }

    @Test
    fun `restartMin negative is rejected`() {
        assertNull(ConfigValidator.normalizeRestartMin(-1))
        assertNull(ConfigValidator.normalizeRestartMin(Int.MIN_VALUE))
    }

    @Test
    fun `restartMin is clamped at the low edge`() {
        assertEquals(15, ConfigValidator.normalizeRestartMin(1))
        assertEquals(15, ConfigValidator.normalizeRestartMin(14))
        assertEquals(15, ConfigValidator.normalizeRestartMin(15))
        assertEquals(16, ConfigValidator.normalizeRestartMin(16))
    }

    @Test
    fun `restartMin is clamped at the high edge`() {
        assertEquals(240, ConfigValidator.normalizeRestartMin(240))
        assertEquals(1439, ConfigValidator.normalizeRestartMin(1439))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(1440))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(1441))
        assertEquals(1440, ConfigValidator.normalizeRestartMin(Int.MAX_VALUE))
    }

    // --- normalizeLowMemMb (0 = off, negative = rejected, else 50..4096) ---

    @Test
    fun `lowMemMb zero means off`() {
        assertEquals(0, ConfigValidator.normalizeLowMemMb(0))
    }

    @Test
    fun `lowMemMb negative is rejected`() {
        assertNull(ConfigValidator.normalizeLowMemMb(-1))
        assertNull(ConfigValidator.normalizeLowMemMb(Int.MIN_VALUE))
    }

    @Test
    fun `lowMemMb is clamped at the low edge`() {
        assertEquals(50, ConfigValidator.normalizeLowMemMb(1))
        assertEquals(50, ConfigValidator.normalizeLowMemMb(49))
        assertEquals(50, ConfigValidator.normalizeLowMemMb(50))
        assertEquals(51, ConfigValidator.normalizeLowMemMb(51))
    }

    @Test
    fun `lowMemMb is clamped at the high edge`() {
        assertEquals(4095, ConfigValidator.normalizeLowMemMb(4095))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(4096))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(4097))
        assertEquals(4096, ConfigValidator.normalizeLowMemMb(Int.MAX_VALUE))
    }

    // --- updateChannel (stable | canary | off, default stable) ---

    @Test
    fun `default config follows the stable channel`() {
        assertEquals(UpdateChannel.STABLE, KioskConfig.DEFAULT.updateChannel)
        assertEquals(UpdateChannel.STABLE, KioskConfig("http://h/", 0, 0).updateChannel)
    }

    @Test
    fun `channel is accepted case insensitive and trimmed`() {
        assertEquals(UpdateChannel.STABLE, ConfigValidator.normalizeChannel("stable"))
        assertEquals(UpdateChannel.CANARY, ConfigValidator.normalizeChannel(" Canary\n"))
        assertEquals(UpdateChannel.OFF, ConfigValidator.normalizeChannel("OFF"))
    }

    @Test
    fun `unknown blank and null channel is rejected`() {
        for (raw in listOf(null, "", "  ", "beta", "stable,canary", "../stable", "none")) {
            assertNull("expected rejection of $raw", ConfigValidator.normalizeChannel(raw))
        }
    }
}
