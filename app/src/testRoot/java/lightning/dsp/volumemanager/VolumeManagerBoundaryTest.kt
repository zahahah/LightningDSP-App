package lightning.dsp.volumemanager

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Host-side manifest contracts; these do not validate Android permissions at runtime. */
class VolumeManagerBoundaryTest {
    private val app = sequenceOf(File("."), File("app"))
        .first { File(it, "src/root/AndroidManifest.xml").isFile }
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(app, path))

    @Test fun managementActivityAndRootProcessOwnerArePrivate() {
        val manifest = xml("src/root/AndroidManifest.xml")
        for ((tag, className) in listOf(
            "activity" to "lightning.dsp.volumemanager.ui.LightningDspVolumeManagerActivity",
            "service" to "lightning.dsp.volumemanager.backend.LightningDspVolumeService"
        )) {
            val nodes = manifest.getElementsByTagName(tag)
            val matches = (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttributeNS(android, "name") == className }
            assertEquals("Exactly one declaration of $className", 1, matches.size)
            assertEquals("false", matches.single().getAttributeNS(android, "exported"))
        }
    }

    @Test fun accessibilityEntryRequiresTheFrameworkBindingPermission() {
        val nodes = xml("src/root/AndroidManifest.xml").getElementsByTagName("service")
        val service = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttributeNS(android, "name") ==
                "lightning.dsp.volumemanager.ui.LightningVolumeAccessibilityService" }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.getAttributeNS(android, "permission"))
    }

    @Test fun volumeKeysDoNotGrantScreenContentOrGestureAccess() {
        val service = xml("src/root/res/xml/lightning_volume_accessibility.xml").documentElement
        assertEquals("false", service.getAttributeNS(android, "canRetrieveWindowContent"))
        assertNotEquals("true", service.getAttributeNS(android, "canPerformGestures"))
        assertEquals("true", service.getAttributeNS(android, "canRequestFilterKeyEvents"))
        assertEquals("typeWindowStateChanged", service.getAttributeNS(android, "accessibilityEventTypes"))
    }

    @Test fun privilegedImplementationIsConfinedToRootSources() {
        for (flavor in listOf("main", "rootless", "plugin")) {
            val path = File(app, "src/$flavor/java/lightning/dsp/volumemanager")
            assertFalse("$flavor must not package privileged volume implementation",
                path.walkTopDown().any { it.isFile && it.extension == "kt" })
        }
    }
}
