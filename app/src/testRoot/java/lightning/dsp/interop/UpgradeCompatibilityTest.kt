package lightning.dsp.interop

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.impl.model.WorkSpec
import lightning.dsp.backup.BackupCreatorJob
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UpgradeCompatibilityTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private fun source(path: String) = File(app, path).readText()

    @Test fun oldPersistedBackupWorkSpecStillResolvesWithoutCancellingOrReplacingWork() {
        for (interval in listOf(0L, 24 * 60 * 60 * 1000L)) {
            val spec = WorkSpec("existing-backup-id", "me.timschneeberger.rootlessjamesdsp.backup.BackupCreatorJob")
            spec.intervalDuration = interval // Zero represents a queued manual request, nonzero periodic work.
            spec.input = Data.Builder().putBoolean("is_auto_backup", interval != 0L)
                .putString("location_uri", "content://existing-backup/location").build()
            val worker = Class.forName(spec.workerClassName)
            assertTrue(BackupCreatorJob::class.java.isAssignableFrom(worker))
            assertNotNull(worker.getConstructor(Context::class.java, WorkerParameters::class.java))
            assertEquals("existing-backup-id", spec.id)
            assertEquals(interval, spec.intervalDuration)
            assertEquals(interval != 0L, spec.input.getBoolean("is_auto_backup", false))
            assertEquals("content://existing-backup/location", spec.input.getString("location_uri"))
        }
    }

    @Test fun persistedListenerTileAndLauncherNamesAreStableAndUsedForRegistration() {
        val manifest = source("src/main/AndroidManifest.xml")
        for (name in listOf("service.NotificationListenerService", "service.QuickTileService",
            "service.RootAudioProcessorService", "service.RootlessAudioProcessorService", "backup.BackupRestoreService",
            "receiver.SessionReceiver", "receiver.BootCompletedReceiver", "receiver.PowerStateReceiver", "activity.MainActivity")) {
            assertTrue(name, manifest.contains("android:name=\"me.timschneeberger.rootlessjamesdsp.$name\""))
        }
        assertTrue(manifest.contains("<activity-alias"))
        assertTrue(manifest.contains("android:targetActivity=\".activity.MainActivity\""))
        for (path in listOf("session/shared/BaseSessionManager.kt", "fragment/settings/SettingsTroubleshootingFragment.kt")) {
            assertTrue(source("src/main/java/lightning/dsp/$path")
                .contains("import me.timschneeberger.rootlessjamesdsp.service.NotificationListenerService"))
        }
    }

    @Test fun historicalNotificationChannelIdRemainsDeletionTarget() {
        val notifications = source("src/main/java/lightning/dsp/utils/notifications/Notifications.kt")
        assertTrue(notifications.contains("\"JamesDSP\""))
    }

    @Test fun savedAutoEqSerializableClassIdentityAndRoundTripRemainCompatible() {
        val item = lightning.dsp.model.api.AeqSearchResult("Saved result", "old source", 1, 42L)
        assertEquals("me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult", item.javaClass.name)
        val bytes = java.io.ByteArrayOutputStream().also { stream ->
            java.io.ObjectOutputStream(stream).use { it.writeObject(arrayOf(item)) }
        }.toByteArray()
        val restored = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readObject() }
        assertArrayEquals(arrayOf(item), restored as Array<*>)
    }

    @Test fun previewIsInstallableWithoutChangingUnsignedReleaseOrApplicationIdentityTests() {
        val build = source("build.gradle.kts")
        val preview = build.substringAfter("create(\"preview\")")
        assertTrue(preview.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
        assertTrue(build.substringBefore("create(\"preview\")").contains("signingConfig = null"))
        assertTrue(source("src/androidTest/java/lightning/dsp/ExampleInstrumentedTest.kt")
            .contains("assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)"))
    }
}
