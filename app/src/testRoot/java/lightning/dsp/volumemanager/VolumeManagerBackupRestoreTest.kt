package lightning.dsp.volumemanager

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import lightning.dsp.backup.VolumeManagerBackup
import lightning.dsp.volumemanager.backup.VolumeManagerBackupCodec as Codec
import lightning.dsp.volumemanager.backup.VolumeManagerBackupSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VolumeManagerBackupRestoreTest {
    @get:Rule val temp = TemporaryFolder()

    /** Exercise the actual Editor operations without Android storage or a privileged process. */
    private class Preferences(initial: Map<String, Any>, private val commitSucceeds: Boolean = true) {
        val values = initial.toMutableMap()
        var commits = 0
        private val pending = mutableMapOf<String, Any?>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name == "remove" -> { pending[args!![0] as String] = null; proxy }
                method.name.startsWith("put") -> { pending[args!![0] as String] = args[1]; proxy }
                method.name == "commit" -> {
                    commits++
                    if (commitSucceeds) pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    pending.clear()
                    commitSucceeds
                }
                else -> error("Unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun cleanRestoreReplacesPortableValuesButPreservesCurrentCleanupOwnership() {
        val original = mapOf("enabled" to true, "volume:com.old.app" to 0.8f,
            "cleanup_uncertain" to true, "cleanup_receipt_nonce" to "local-owner",
            "unknown_runtime" to "do-not-copy-or-clear")
        val prefs = Preferences(original)
        VolumeManagerBackup.restorePreferences(prefs.preferences,
            mapOf("enabled" to false, "volume:com.new.app" to 0.25f), false, Codec::isManagerSetting)
        assertEquals(mapOf("enabled" to false, "volume:com.new.app" to 0.25f,
            "cleanup_uncertain" to true, "cleanup_receipt_nonce" to "local-owner",
            "unknown_runtime" to "do-not-copy-or-clear"), prefs.values)
        assertEquals(1, prefs.commits)
    }

    @Test fun dirtyRestoreMergesAppSettingsAndPreservesUnmentionedRules() {
        val prefs = Preferences(mapOf("enabled" to true, "volume:com.existing.app" to 0.4f,
            "pan:com.saved.app" to 0.5f))
        VolumeManagerBackup.restorePreferences(prefs.preferences,
            mapOf("pan:com.saved.app" to -0.5f, "muted:com.missing.app" to true), true, Codec::isManagerSetting)
        assertEquals(mapOf("enabled" to true, "volume:com.existing.app" to 0.4f,
            "pan:com.saved.app" to -0.5f, "muted:com.missing.app" to true), prefs.values)
    }

    @Test fun pinsAndEmptyUnifiedOrderReplaceValuesEvenOnDirtyRestore() {
        val prefs = Preferences(mapOf("pinned_apps" to setOf("com.old.app"), "order_apps" to "com.old.app",
            "floating_panel_theme" to "dark"))
        VolumeManagerBackup.restorePreferences(prefs.preferences,
            mapOf("pinned_apps" to emptySet<String>(), "order_apps" to ""), true, Codec::isPanelSetting)
        assertEquals(emptySet<String>(), prefs.values["pinned_apps"])
        assertEquals("", prefs.values["order_apps"])
        assertEquals("dark", prefs.values["floating_panel_theme"])
    }

    @Test fun emptyNewSectionResetsPortablePanelSettingsOnlyOnCleanRestore() {
        val prefs = Preferences(mapOf("pinned_apps" to setOf("com.old.app"), "order_apps" to "com.old.app",
            "private_runtime" to true))
        VolumeManagerBackup.restorePreferences(prefs.preferences, emptyMap(), false, Codec::isPanelSetting)
        assertEquals(mapOf("private_runtime" to true), prefs.values)
    }

    @Test fun oldBackupWithoutMixerSectionHasNoSettingsToApply() {
        assertNull(VolumeManagerBackup.readSettings(temp.root))
    }

    @Test fun optionalSectionDecodesSettingsAndRejectsCorruptionBeforeRestore() {
        val file = File(temp.root, VolumeManagerBackup.ARCHIVE_PATH)
        file.parentFile!!.mkdirs()
        val settings = VolumeManagerBackupSettings(panel = mapOf("pinned_apps" to setOf("com.saved.app")))
        file.writeBytes(Codec.encode(settings))
        assertEquals(settings, VolumeManagerBackup.readSettings(temp.root))
        file.writeText("version=99")
        try { VolumeManagerBackup.readSettings(temp.root); fail("Corrupt section accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun failedCommitIsNotReportedAsSuccessfulRestore() {
        val prefs = Preferences(emptyMap(), commitSucceeds = false)
        try {
            VolumeManagerBackup.restorePreferences(prefs.preferences, mapOf("enabled" to false), false, Codec::isManagerSetting)
            fail("Failed storage write accepted")
        } catch (_: IOException) { }
        assertEquals(1, prefs.commits)
    }

    @Test fun runtimeOwnershipCannotBeInjectedByRestoreHelper() {
        val prefs = Preferences(mapOf("cleanup_uncertain" to true))
        try {
            VolumeManagerBackup.restorePreferences(prefs.preferences, mapOf("cleanup_uncertain" to false), false, Codec::isManagerSetting)
            fail("Runtime ownership accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(mapOf("cleanup_uncertain" to true), prefs.values)
        assertEquals(0, prefs.commits)
    }
}
