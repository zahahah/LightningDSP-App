package lightning.dsp.utils

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

/** Reports whether a managed profile exposes apps that can be listed by LightningDSP. */
internal object WorkProfileAppsPolicy {
    fun hasAccessibleWorkProfile(context: Context): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return false
        val currentUserId = Process.myUid() / 100000
        return runCatching {
            launcherApps.profiles.any { profile ->
                launcherApps.getActivityList(null, profile).any {
                    it.applicationInfo.uid / 100000 != currentUserId
                }
            }
        }.getOrDefault(false)
    }
}
