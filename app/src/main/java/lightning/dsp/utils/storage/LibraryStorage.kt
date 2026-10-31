package lightning.dsp.utils.storage

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private libraries; public module examples are not the app's storage root. */
internal object LibraryStorage {
    val directories = listOf("Convolver", "DDC", "Liveprog", "Presets")

    fun ensureDirectory(directory: File): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create library directory: ${directory.name}" }
        return directory
    }

    fun importStream(directory: File, name: String, input: InputStream): File {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name) { "Invalid library file name" }
        return replaceFile(File(directory, name)) { temporary ->
            temporary.outputStream().use { input.copyTo(it) }
        }
    }

    fun replaceFile(destination: File, write: (File) -> Unit): File {
        val directory = ensureDirectory(requireNotNull(destination.parentFile))
        val temporary = File.createTempFile(".write-", ".tmp", directory)
        try {
            write(temporary)
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return destination
        } finally {
            temporary.delete()
        }
    }
}
