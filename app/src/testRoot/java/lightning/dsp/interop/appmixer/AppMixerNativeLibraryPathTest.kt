package lightning.dsp.interop.appmixer

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppMixerNativeLibraryPathTest {
    @get:Rule val temporary = TemporaryFolder()
    private val names = listOf("liblightningdsp-wrapper.so", "liblightningdsplib.so", "libldspimprestoolbox.so")

    @Test fun supportsUnextractedApkLibrariesAndCorrectAbi() {
        val apk = temporary.newFile("base.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            for (name in names) {
                val bytes = byteArrayOf(1, 2)
                val entry = ZipEntry("lib/arm64-v8a/$name")
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        val extracted = temporary.newFolder().path
        assertEquals("${apk.path}!/lib/arm64-v8a", AppMixerNativeLibraryPath.select(apk.path, extracted, "arm64-v8a"))
        assertThrows(IllegalStateException::class.java) { AppMixerNativeLibraryPath.select(apk.path, extracted, "x86") }
    }

    @Test fun extractedLibrariesDoNotRequireOpeningApk() {
        val extracted = temporary.newFolder()
        names.forEach { File(extracted, it).writeBytes(byteArrayOf(1)) }
        assertEquals(extracted.path, AppMixerNativeLibraryPath.select("nonexistent.apk", extracted.path, "arm64-v8a"))
        assertThrows(IllegalArgumentException::class.java) { AppMixerNativeLibraryPath.select("", extracted.path, "../other") }
    }
}
