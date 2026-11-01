package lightning.dsp.utils.storage

import java.io.ByteArrayOutputStream
import java.io.File
import lightning.dsp.model.preset.SharedPreferencesXml
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream

class TarExtractionIsolationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarOutputStream(bytes).use { out ->
            entries.forEach { (name, text) ->
                val file = File.createTempFile("entry-", ".xml", folder.root).apply { writeText(text) }
                out.putNextEntry(TarEntry(file, name))
                out.write(file.readBytes())
            }
        }
        return bytes.toByteArray()
    }

    @Test fun nextProfileCannotInheritXmlFromFailedPreviousLoad() {
        val target = folder.newFolder("preset")
        File(target, "dsp_bass.xml").writeText("<map><boolean name=\"enabled\" value=\"true\"/></map>")
        val contents = archive("dsp_reverb.xml" to "<map/>", "metadata" to "version=3")
        assertNotNull(Tar.Reader(contents.inputStream()).extract(target))
        assertFalse("Stale DSP state from another preset must not survive extraction", File(target, "dsp_bass.xml").exists())
        assertEquals("<map/>", File(target, "dsp_reverb.xml").readText())
    }

    @Test fun metadataOnlyArchiveCannotInheritPreviousDspSettings() {
        val target = folder.newFolder("preset")
        File(target, "dsp_bass.xml").writeText("<map/>")
        assertNotNull(Tar.Reader(archive("metadata" to "version=3").inputStream()).extract(target))
        assertFalse(target.listFiles().orEmpty().any { it.name.startsWith("dsp_") })
    }

    @Test fun extractionRejectsEntriesOutsideItsDirectory() {
        val target = folder.newFolder("preset")
        assertNull(Tar.Reader(archive("../outside.xml" to "<map/>").inputStream()).extract(target))
        assertFalse(File(folder.root, "outside.xml").exists())
    }
    @Test fun failedXmlParseCannotLeakEffectsIntoTheNextPartialProfile() {
        val target = folder.newFolder("preset")
        val invalid = archive("dsp_bass.xml" to "<map><boolean name='enabled' value='true'/></map>",
            "dsp_reverb.xml" to "<!DOCTYPE map><map/>", "metadata" to "version=3")
        assertNotNull(Tar.Reader(invalid.inputStream()).extract(target))
        try {
            File(target, "dsp_reverb.xml").inputStream().use(SharedPreferencesXml::parse)
            fail("The previous load must fail its XML validation")
        } catch (_: IllegalArgumentException) { }
        val next = archive("dsp_reverb.xml" to "<map><boolean name='enabled' value='false'/></map>",
            "metadata" to "version=3")
        assertNotNull(Tar.Reader(next.inputStream()).extract(target))
        assertFalse(File(target, "dsp_bass.xml").exists())
        assertEquals(listOf(SharedPreferencesXml.Value.BooleanValue("enabled", false)),
            File(target, "dsp_reverb.xml").inputStream().use(SharedPreferencesXml::parse))
    }

}
