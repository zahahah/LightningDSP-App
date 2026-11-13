package lightning.dsp.interop.appmixer

import android.content.Context
import android.os.Process
import lightning.dsp.session.shared.SESSION_DSP_ENABLED_PREFIX

/** Current-user inventory; unknown package membership rejects the entire snapshot. */
internal class AppMixerUidInventory(private val context: Context) {
    data class Route(val excludedUids: Set<Int>, val includesSystemSounds: Boolean)

    fun readRoute(includeSystemSounds: Boolean): Route? {
        return try {
            val packages = context.packageManager
            val preferences = context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val installed = packages.getInstalledApplications(0)
            val uids = installed.map { it.uid }.filter { it >= 0 && it % 100000 in 10000..19999 }.toSet()
            val apps = ArrayList<AppMixerUidSelection.App>()
            for (uid in uids) {
                val members = packages.getPackagesForUid(uid)?.toSet() ?: return null
                if (members.isEmpty()) return null
                for (name in members) apps += AppMixerUidSelection.App(uid, name,
                    preferences.getBoolean(SESSION_DSP_ENABLED_PREFIX + name, true))
            }
            val exclusions = AppMixerUidSelection.exclusions(apps, Process.myUid(), includeSystemSounds)
            if (exclusions != null) Route(exclusions, includeSystemSounds)
            else if (includeSystemSounds) {
                // Preserve Fusion Mode if the extra usage rules exceed AudioPolicy's
                // criterion budget; degrade only optional system-sound coverage.
                AppMixerUidSelection.exclusions(apps, Process.myUid(), false)?.let { Route(it, false) }
            } else null
        } catch (_: RuntimeException) { null }
    }
}
