package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test

class AppMixerExternalPathTest {
    private val owner = "com.lightning.dsp"
    @Test fun acceptsOnlyCanonicalOwningPackageFilesDirectory() {
        val path = "/storage/emulated/0/Android/data/$owner/files"
        assertEquals(path, AppMixerExternalPath.verify(path, owner)?.path)
        assertNull(AppMixerExternalPath.verify("", owner))
        for (invalid in listOf("relative/Android/data/$owner/files", "/storage/emulated/0/Android/data/other/files",
                "$path/../files", "/storage/emulated/0/Android/data/$owner/cache")) {
            assertThrows(IllegalArgumentException::class.java) { AppMixerExternalPath.verify(invalid, owner) }
        }
    }

    @Test fun storageLookupIsPerformedByTheAppNotTheRootPackageContext() {
        val app = sequenceOf(java.io.File("."), java.io.File("app"))
            .first { java.io.File(it, "src/root/AndroidManifest.xml").isFile }
        val context = java.io.File(app, "src/root/java/lightning/dsp/interop/appmixer/AppMixerRelayFileContext.kt").readText()
        assertTrue(context.contains("override fun getExternalFilesDir"))
        assertFalse(context.contains("super.getExternalFilesDir"))
        val launcher = java.io.File(app, "src/root/java/lightning/dsp/interop/appmixer/AppMixerProcessLauncher.kt").readText()
        assertTrue(launcher.contains("context.getExternalFilesDir(null)?.canonicalPath"))
        assertTrue(launcher.contains("--owner-external-b64=$"))
    }
}
