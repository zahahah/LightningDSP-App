package lightning.dsp.volumemanager

import lightning.dsp.volumemanager.backend.VolumeProfiles
import lightning.dsp.volumemanager.backup.VolumeManagerBackupCodec
import lightning.dsp.volumemanager.backup.VolumeManagerBackupSettings
import org.junit.Assert.*
import org.junit.Test

class VolumeProfilesTest {
    @Test fun migrationPreservesExistingValuesAsProfileOne() {
        val state = VolumeProfiles.initialize(mapOf("volume:com.player.app" to .4f, "muted:com.player.app" to true))
        assertEquals("Profile 1", VolumeProfiles.list(state).single().name)
        assertEquals(.4f, state["profile_volume:default:com.player.app"])
        assertEquals(true, state["profile_muted:default:com.player.app"])
    }
    @Test fun newProfileStartsAtDefaultsAndSwitchingRestoresPreviousRules() {
        val initial = VolumeProfiles.initialize(mapOf("volume:com.player.app" to .4f, "pan:com.player.app" to -.2f))
        val (id, fresh) = VolumeProfiles.create(initial, "  Night  ")
        assertEquals(id, fresh[VolumeProfiles.SELECTED])
        assertFalse(fresh.containsKey("volume:com.player.app"))
        assertFalse(fresh.containsKey("pan:com.player.app"))
        val adjusted = fresh + ("volume:com.player.app" to .7f)
        val old = VolumeProfiles.select(adjusted, "default")
        assertEquals(.4f, old["volume:com.player.app"])
        assertEquals(.7f, VolumeProfiles.select(old, id)["volume:com.player.app"])
    }
    @Test fun backupRoundTripsAllProfilesWithoutRuntimeKeys() {
        val (_, values) = VolumeProfiles.create(mapOf("volume:com.player.app" to .4f), "Night")
        val settings = VolumeManagerBackupSettings(manager = values + ("cleanup_uncertain" to true))
        val restored = VolumeManagerBackupCodec.decode(VolumeManagerBackupCodec.encode(settings)).manager
        assertEquals(values, restored)
        assertFalse(restored.containsKey("cleanup_uncertain"))
    }
    @Test fun duplicateBlankAndControlCharacterNamesAreRejected() {
        for (name in listOf("", "  ", "Profile 1", "PROFILE 1", "bad\nname", "x".repeat(49))) {
            assertTrue(runCatching { VolumeProfiles.create(emptyMap<String, Any>(), name) }.isFailure)
        }
    }
    @Test fun renamePreservesRulesSelectionAndRejectsDuplicateNames() {
        val (otherId, created) = VolumeProfiles.create(
            VolumeProfiles.initialize(mapOf("volume:com.player.app" to .4f)), "Night")
        val renamed = VolumeProfiles.rename(created, otherId, "  Quiet  ")
        assertEquals("Quiet", VolumeProfiles.list(renamed).first { it.id == otherId }.name)
        assertEquals(otherId, renamed[VolumeProfiles.SELECTED])
        assertEquals(.4f, renamed["profile_volume:default:com.player.app"])
        assertTrue(runCatching { VolumeProfiles.rename(renamed, otherId, "Profile 1") }.isFailure)
        assertTrue(runCatching { VolumeProfiles.rename(renamed, "missing", "Other") }.isFailure)
        assertTrue(runCatching { VolumeProfiles.rename(renamed, otherId, "bad\nname") }.isFailure)
    }
    @Test fun invalidProfileValuesAndKeyInjectionAreRejected() {
        assertFalse(VolumeProfiles.isSetting("profile_volume:default:../../system"))
        assertTrue(runCatching { VolumeManagerBackupCodec.filterManager(mapOf("profile_volume:default:com.player.app" to Float.NaN)) }.isFailure)
    }
}
