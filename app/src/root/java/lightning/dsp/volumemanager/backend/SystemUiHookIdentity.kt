package lightning.dsp.volumemanager.backend

import android.app.ActivityManager
import android.content.Context

/** Root control-loop probe; never queues a shell job on the app's heartbeat worker. */
internal object SystemUiHookIdentity {
    fun matches(marker: String?, livePids: Collection<Int>): Boolean =
        marker?.toIntOrNull()?.let { it > 0 && it in livePids } == true

    fun available(context: Context): Boolean = runCatching {
        val marker = SystemUiHookReadiness.currentPid(context)
        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        marker > 0 && marker in processes.filter { it.processName == "com.android.systemui" }.map { it.pid }
    }.getOrDefault(false)
}
