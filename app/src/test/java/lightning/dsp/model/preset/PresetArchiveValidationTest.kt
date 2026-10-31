package lightning.dsp.model.preset

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream

class PresetArchiveValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarOutputStream(bytes).use { out ->
            entries.forEachIndexed { i, (name, content) ->
                val staged = folder.newFile("entry-$i").apply { writeText(content) }
                out.putNextEntry(TarEntry(staged, name))
                out.write(staged.readBytes())
            }
        }
        return bytes.toByteArray()
    }

    @Test fun metadataAloneIsNotAPreset() {
        assertFalse(Preset.validate(archive("metadata" to "version=3").inputStream()))
    }

    @Test fun liveprogAloneDoesNotPretendToRestoreDspSettings() {
        assertFalse(Preset.validate(archive("liveprog" to "@sample\nspl0=spl0;").inputStream()))
    }

    @Test fun existingDspPreferenceArchiveRemainsAccepted() {
        assertTrue(Preset.validate(archive("metadata" to "version=3",
            "dsp_bass.xml" to "<map/>").inputStream()))
    }
}
