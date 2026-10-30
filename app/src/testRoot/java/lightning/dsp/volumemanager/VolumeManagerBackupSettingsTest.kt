package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backup.VolumeManagerBackupCodec as Codec
import lightning.dsp.volumemanager.backup.VolumeManagerBackupSettings
import org.junit.Assert.*
import org.junit.Test

class VolumeManagerBackupSettingsTest {
    private fun payload(vararg entries: Pair<String, String>): ByteArray =
        (listOf("version=1") + entries.map { "${it.first.replace(":", "\\:")}=${it.second}" }).joinToString("\n").toByteArray()

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid backup to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid known settings must not silently become a partial restore.
        }
    }

    @Test fun allPortableSettingsRoundTripWithoutLosingAbsentPackages() {
        val manager = mapOf(
            "enabled" to true, "per_app_enabled" to false, "startup_on_boot" to true,
            "access_mode" to "ROOT", "volume:com.missing.player" to 0.35f,
            "pan:com.missing.player" to -0.7f, "muted:com.missing.player" to true,
            "ignore_buttons:com.missing.player" to false, "had_player:android" to true,
        )
        val panel = mapOf(
            "show_dsp_card" to true, "hide_system_apps" to false, "group_system_streams" to true,
            "media_icon_hold_expand" to true, "dsp_bar_monochrome" to true,
            "hide_inactive_dsp_card" to false, "hidden_com.missing.player" to true,
            "stream_0" to false, "stream_2" to true, "stream_3" to true, "stream_4" to false,
            "stream_5" to true, "floating_panel_theme" to "dark",
            "order_streams" to "3|2|4|5|0", "order_apps" to "com.missing.player|android",
            "order_active" to "android", "order_inactive" to "com.missing.player",
            "dsp_effect_display_order" to "[\"bass\",\"eq\"]",
            "pinned_apps" to setOf("com.missing.player", "android"),
        )
        val original = VolumeManagerBackupSettings(manager, panel)
        assertEquals(original, Codec.decode(Codec.encode(original)))
    }

    @Test fun emptyUnifiedOrderPinsAndLegacyRanksAreNotReplacedOrDropped() {
        val settings = VolumeManagerBackupSettings(panel = mapOf(
            "order_apps" to "", "order_active" to "com.legacy.active",
            "order_inactive" to "com.legacy.inactive", "pinned_apps" to emptySet<String>(),
            "dsp_effect_display_order" to "[]",
        ))
        assertEquals(settings, Codec.decode(Codec.encode(settings)))
        assertEquals(VolumeManagerBackupSettings(), Codec.decode(Codec.encode(VolumeManagerBackupSettings())))
    }

    @Test fun knownFloatSettingsRejectNonfiniteAndOutOfRangeRatherThanClamping() {
        for ((key, values) in listOf(
            "volume:com.example.app" to listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f),
            "pan:com.example.app" to listOf(Float.NaN, Float.POSITIVE_INFINITY, -1.01f, 1.01f),
        )) {
            for (value in values) {
                rejects { Codec.filterManager(mapOf(key to value)) }
                rejects { Codec.decode(payload("manager.$key" to value.toString())) }
            }
        }
        val endpoints = VolumeManagerBackupSettings(manager = mapOf(
            "volume:com.example.a" to 0f, "volume:com.example.b" to 1f,
            "pan:com.example.a" to -1f, "pan:com.example.b" to 1f,
        ))
        assertEquals(endpoints, Codec.decode(Codec.encode(endpoints)))
    }

    @Test fun filteringAndEncodingExcludeCleanupLedgerAndUnknownRuntimeKeys() {
        val values = mapOf(
            "cleanup_uncertain" to true, "cleanup_receipt_nonce" to "secret",
            "cleanup_boot_id" to "boot-secret",
            "runtime_pid" to 123, "unknown" to true, "enabled" to true,
        )
        assertEquals(mapOf("enabled" to true), Codec.filterManager(values))
        assertTrue(Codec.filterPanel(values).isEmpty())
        val encoded = Codec.encode(VolumeManagerBackupSettings(values, values)).toString(Charsets.ISO_8859_1)
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("cleanup"))
        assertFalse(encoded.contains("boot-secret"))
        assertEquals(VolumeManagerBackupSettings(manager = mapOf("enabled" to true)), Codec.decode(encoded.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(VolumeManagerBackupSettings(), Codec.decode(payload(
            "manager.cleanup_uncertain" to "true", "manager.cleanup_receipt_nonce" to "secret",
            "panel.cleanup_receipt_nonce" to "secret", "panel.stream_999" to "true",
            "future.setting" to "anything", "manager.unknown" to "anything",
        )))
    }

    @Test fun packageKeysRejectPathsAndPinsRejectInvalidPackageIds() {
        for (suffix in listOf("", "../com.app", "com/app", "com.app|com.other", "com..app", "plain", "com.app\nother")) {
            assertFalse(Codec.isManagerSetting("volume:$suffix"))
            assertFalse(Codec.isPanelSetting("hidden_$suffix"))
            rejects { Codec.filterPanel(mapOf("pinned_apps" to setOf(suffix))) }
        }
        assertTrue(Codec.isManagerSetting("volume:android"))
        assertTrue(Codec.isPanelSetting("hidden_com.example.player_2"))
        rejects { Codec.decode(payload("panel.pinned_apps" to "com.app|")) }
    }

    @Test fun knownSettingsRejectWrongJvmTypesAndMalformedBooleans() {
        for ((key, value) in listOf(
            "enabled" to "true", "per_app_enabled" to 1, "volume:com.app" to 0.5,
            "pan:com.app" to "0.0", "access_mode" to "UNKNOWN",
        )) rejects { Codec.filterManager(mapOf(key to value)) }
        for ((key, value) in listOf(
            "show_dsp_card" to "false", "order_apps" to 1, "floating_panel_theme" to "purple",
            "pinned_apps" to listOf("com.app"), "pinned_apps" to setOf(1),
        )) rejects { Codec.filterPanel(mapOf(key to value)) }
        for (text in listOf("TRUE", "yes", "1", "", " false")) {
            // Properties ignores leading whitespace; escape it to preserve the invalid value.
            val stored = if (text.startsWith(' ')) "\\$text" else text
            rejects { Codec.decode(payload("manager.enabled" to stored)) }
        }
        rejects { Codec.decode(payload("panel.floating_panel_theme" to "purple")) }
        rejects { Codec.decode(payload("manager.access_mode" to "root")) }
    }

    @Test fun supportsAllStoredAccessAndThemeChoices() {
        for (mode in listOf("ROOT", "SHIZUKU")) for (theme in listOf("system", "light", "dark")) {
            val settings = VolumeManagerBackupSettings(mapOf("access_mode" to mode), mapOf("floating_panel_theme" to theme))
            assertEquals(settings, Codec.decode(Codec.encode(settings)))
        }
    }

    @Test fun filteredPinsAreADefensiveCopy() {
        val pins = mutableSetOf("com.app")
        val filtered = Codec.filterPanel(mapOf("pinned_apps" to pins))
        pins.clear()
        assertEquals(setOf("com.app"), filtered["pinned_apps"])
    }

    @Test fun corruptUnsupportedAndOversizedPayloadsAreRejected() {
        for (bytes in listOf(
            byteArrayOf(), "garbage".toByteArray(), "version=2".toByteArray(),
            "version=1\npanel.order_apps=\\uGGGG".toByteArray(),
            ByteArray(Codec.MAX_BYTES + 1) { 'x'.code.toByte() },
        )) rejects { Codec.decode(bytes) }
        rejects { Codec.encode(VolumeManagerBackupSettings(panel = mapOf("order_apps" to "x".repeat(Codec.MAX_BYTES)))) }
    }

    @Test fun rawEffectOrderIsPreservedForTheExistingTolerantUiParser() {
        val settings = VolumeManagerBackupSettings(panel = mapOf("dsp_effect_display_order" to "[future effect ids, malformed]"))
        assertEquals(settings, Codec.decode(Codec.encode(settings)))
    }
}
