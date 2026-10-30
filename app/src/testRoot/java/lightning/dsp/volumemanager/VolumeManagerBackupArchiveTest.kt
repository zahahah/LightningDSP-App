package lightning.dsp.volumemanager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import lightning.dsp.backup.VolumeManagerBackup
import lightning.dsp.utils.storage.Tar
import lightning.dsp.volumemanager.backup.VolumeManagerBackupCodec as Codec
import lightning.dsp.volumemanager.backup.VolumeManagerBackupSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream

/** Actual compressed TAR extraction; Android services and SAF are outside this host harness. */
class VolumeManagerBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarOutputStream(GZIPOutputStream(bytes)).use { output ->
            entries.forEach { (path, data) ->
                val staged = temp.newFile()
                staged.writeBytes(data)
                output.putNextEntry(TarEntry(staged, path))
                output.write(data)
            }
        }
        return bytes.toByteArray()
    }

    @Test fun mixerSettingsAndDspEntriesSurviveCompressedTarRoundTrip() {
        val settings = VolumeManagerBackupSettings(
            manager = mapOf("enabled" to false, "volume:com.absent.app" to 0.3f, "pan:com.absent.app" to -0.2f),
            panel = mapOf("order_apps" to "com.absent.app|com.other.app",
                "pinned_apps" to setOf("com.absent.app"), "floating_panel_theme" to "light"))
        val archive = archive(mapOf("metadata" to "is_backup=true\nmin_version_code=75".toByteArray(),
            "shared_prefs/dsp_output.xml" to "<map/>".toByteArray(),
            VolumeManagerBackup.ARCHIVE_PATH to Codec.encode(settings)))
        val target = temp.newFolder()
        val metadata = Tar.Reader(GZIPInputStream(ByteArrayInputStream(archive))) {
            it == VolumeManagerBackup.ARCHIVE_PATH || it == "shared_prefs/dsp_output.xml"
        }.extract(target)
        assertEquals("true", metadata?.get("is_backup"))
        assertEquals("<map/>", File(target, "shared_prefs/dsp_output.xml").readText())
        assertEquals(settings, VolumeManagerBackup.readSettings(target))
    }

    @Test fun legacyCompressedBackupHasNoMixerSectionToRestore() {
        val archive = archive(mapOf("metadata" to "is_backup=true".toByteArray(),
            "shared_prefs/dsp_output.xml" to "<map/>".toByteArray()))
        val target = temp.newFolder()
        Tar.Reader(GZIPInputStream(ByteArrayInputStream(archive))).extract(target)
        assertNull(VolumeManagerBackup.readSettings(target))
    }

    @Test fun commonBackupPathValidatesBeforeCleanAndAllFlavorsHaveAdapters() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/root/AndroidManifest.xml").isFile }
        val manager = File(app, "src/main/java/lightning/dsp/backup/BackupManager.kt").readText()
        assertTrue(manager.contains("VolumeManagerBackup.addTo(context, c)"))
        assertTrue(manager.indexOf("VolumeManagerBackup.prepareRestore(") < manager.indexOf("if(!dirty)"))
        assertTrue(manager.indexOf("targetFolder.deleteRecursively())") < manager.indexOf("Tar.Reader(stream"))
        assertTrue(manager.contains("name == VolumeManagerBackup.ARCHIVE_PATH"))
        val backend = File(app, "src/root/java/lightning/dsp/volumemanager/backend/LightningDspVolumeManager.kt").readText()
        val restore = backend.substringAfter("fun restoreSettings(").substringBefore("fun setAppVolume(")
        assertTrue(restore.contains("worker.submit"))
        assertTrue(restore.contains("finally"))
        assertTrue(restore.contains("LightningVolumeOverlay.hide()"))
        assertTrue(restore.contains("setEnabled(isEnabled)"))
        assertFalse(restore.contains("closeProcess()"))
        assertFalse(restore.contains("cleanup_uncertain"))
        for (flavor in listOf("rootless", "plugin")) {
            val adapter = File(app, "src/$flavor/java/lightning/dsp/backup/VolumeManagerBackup.kt").readText()
            assertTrue(adapter.contains("fun addTo(context: Context, composer: Tar.Composer) = Unit"))
            assertTrue(adapter.contains("fun prepareRestore(context: Context, folder: File, dirty: Boolean): () -> Unit = {}"))
            assertFalse(adapter.contains("LightningDspVolumeManager"))
        }
    }
}
