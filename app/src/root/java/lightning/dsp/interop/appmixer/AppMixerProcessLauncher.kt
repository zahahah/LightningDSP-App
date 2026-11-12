package lightning.dsp.interop.appmixer

import android.content.Context

/** Launch only from a supervised background owner that retains stdin until stop. */
internal class AppMixerProcessLauncher(private val context: Context) {
    fun launch(uids: Set<Int>): Process {
        val values = uids.sorted().joinToString(",")
        // Reject exclusions that cannot fit alongside the usage and user rules.
        AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=$values")
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        val owner = checkNotNull(AppMixerOwnerIdentity.read(android.os.Process.myPid())) {
            "Cannot establish App Mixer owner identity"
        }
        check(owner.uid == android.os.Process.myUid())
        return ProcessBuilder("su", "-c", "CLASSPATH=$apk exec /system/bin/app_process /system/bin " +
            "lightning.dsp.interop.appmixer.AppMixerRoutingExperiment --supervised --exclude-uids=$values --user=${owner.uid / 100000} --owner=${owner.uid} --owner-pid=${owner.pid} --owner-start=${owner.startTicks}").start()
    }
}
