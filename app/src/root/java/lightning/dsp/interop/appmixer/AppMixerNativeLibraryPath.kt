package lightning.dsp.interop.appmixer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Modern APKs keep native libraries inside the APK instead of nativeLibraryDir. */
internal object AppMixerNativeLibraryPath {
    private val libraries = listOf("liblightningdsp-wrapper.so", "liblightningdsplib.so", "libldspimprestoolbox.so")
    fun select(apk: String, extractedDirectory: String, abi: String): String {
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        if (libraries.all { File(extractedDirectory, it).canRead() }) return extractedDirectory
        ZipFile(apk).use { zip ->
            check(libraries.all { name ->
                zip.getEntry("lib/$abi/$name")?.method == ZipEntry.STORED
            }) { "Local DSP libraries are missing or cannot be loaded directly from the APK" }
        }
        // Both DexPathList and Bionic support the APK!/lib/ABI search-path form.
        return "$apk!/lib/$abi"
    }
}
