package lightning.dsp.interop.appmixer

import android.content.Context
import android.os.Build
import android.util.Base64
import android.os.Process as AndroidProcess

/** Launch only from a supervised background owner that retains stdin until stop. */
internal class AppMixerProcessLauncher(private val context: Context) {
    fun launch(uids: Set<Int>, includeSystemSounds: Boolean): Process {
        val values = uids.sorted().joinToString(",")
        // Reject exclusions that cannot fit alongside the selected usage and user rules.
        AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=$values", includeSystemSounds)
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        val abi = (if (AndroidProcess.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
            .firstOrNull() ?: error("Cannot establish relay ABI")
        val libraryPath = AppMixerNativeLibraryPath.select(context.applicationInfo.sourceDir,
            context.applicationInfo.nativeLibraryDir, abi)
        val nativeDir = "'" + libraryPath.replace("'", "'\\''") + "'"
        val external = context.getExternalFilesDir(null)?.canonicalPath.orEmpty()
        AppMixerExternalPath.verify(external, context.packageName)
        val encodedExternal = Base64.encodeToString(external.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val runtime = if (AndroidProcess.is64Bit()) "/system/bin/app_process64" else "/system/bin/app_process32"
        val owner = checkNotNull(AppMixerOwnerIdentity.read(android.os.Process.myPid())) {
            "Cannot establish Fusion Mode owner identity"
        }
        check(owner.uid == android.os.Process.myUid())
        return ProcessBuilder("su", "-c", "CLASSPATH=$apk LD_LIBRARY_PATH=$nativeDir exec $runtime -Djava.library.path=$nativeDir /system/bin " +
            "lightning.dsp.interop.appmixer.AppMixerRoutingExperiment --supervised --exclude-uids=$values --user=${owner.uid / 100000} --owner=${owner.uid} --owner-pid=${owner.pid} --owner-start=${owner.startTicks} --include-system-sounds=${if (includeSystemSounds) 1 else 0} --local-dsp --owner-external-b64=$encodedExternal").start()
    }
}
