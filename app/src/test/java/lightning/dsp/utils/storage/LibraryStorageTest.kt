package lightning.dsp.utils.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.IOException

class LibraryStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun freshLibrariesIncludeAnEmptyPresetsDirectory() {
        LibraryStorage.directories.forEach { LibraryStorage.ensureDirectory(File(folder.root, it)) }
        assertTrue(File(folder.root, "Presets").isDirectory)
        assertTrue(File(folder.root, "Presets").listFiles()!!.isEmpty())
    }

    @Test fun importCreatesMissingParents() {
        val directory = File(folder.root, "files/Presets")
        val result = LibraryStorage.importStream(directory, "headphones.tar", "preset".byteInputStream())
        assertEquals("preset", result.readText())
    }

    @Test fun initializationDoesNotOverwriteUserFiles() {
        val directory = folder.newFolder("Presets")
        val file = File(directory, "custom.tar").apply { writeText("keep") }
        LibraryStorage.ensureDirectory(directory)
        assertEquals("keep", file.readText())
    }

    @Test fun failedImportPreservesThePreviousFileAndCleansTemporaryFiles() {
        val directory = folder.newFolder("Presets")
        val previous = File(directory, "custom.tar").apply { writeText("keep") }
        val broken = object : InputStream() { override fun read(): Int = throw IOException("unavailable") }
        try {
            LibraryStorage.importStream(directory, "custom.tar", broken)
            fail("Import must report failure")
        } catch (_: IOException) { }
        assertEquals("keep", previous.readText())
        assertEquals(listOf("custom.tar"), directory.list()!!.toList())
    }

    @Test fun providerFileNamesCannotEscapeTheLibrary() {
        for (name in listOf("../outside.tar", "/outside.tar", "..", ".", "", "dir\\file.tar")) {
            try {
                LibraryStorage.importStream(File(folder.root, "Presets"), name, "x".byteInputStream())
                fail("Unsafe name accepted: $name")
            } catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(folder.root, "outside.tar").exists())
    }
    @Test fun failedArchiveCreationKeepsThePreviousPreset() {
        val file = folder.newFile("saved.tar").apply { writeText("original") }
        try {
            LibraryStorage.replaceFile(file) { it.writeText("partial"); throw IOException("disk error") }
            fail("Expected save failure")
        } catch (_: IOException) { }
        assertEquals("original", file.readText())
        assertEquals(listOf("saved.tar"), folder.root.list()!!.toList())
    }

    @Test fun completedArchiveReplacesThePreviousPreset() {
        val file = folder.newFile("saved.tar").apply { writeText("original") }
        LibraryStorage.replaceFile(file) { it.writeText("complete") }
        assertEquals("complete", file.readText())
    }
}
