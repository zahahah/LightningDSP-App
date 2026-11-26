package lightning.dsp.volumemanager.backend

import android.content.Context
import android.net.Uri
import android.os.Bundle

/** PID-bound readiness receipt exchanged through the app provider, not a SystemUI-writable sysprop. */
object SystemUiHookReadiness {
    private const val AUTHORITY = "com.lightning.dsp.compatibility"
    private const val METHOD_READY = "systemui_volume_panel_ready"
    private const val METHOD_CLEAR = "systemui_volume_panel_clear"
    private const val METHOD_STATUS = "systemui_volume_panel_status"
    private const val KEY_ACCEPTED = "accepted"
    private const val KEY_PID = "pid"
    private val URI = Uri.parse("content://$AUTHORITY")

    @JvmStatic
    fun register(context: Context, pid: Int): Boolean = runCatching {
        context.contentResolver.call(URI, METHOD_READY, null, Bundle().apply { putInt(KEY_PID, pid) })
            ?.getBoolean(KEY_ACCEPTED, false) == true
    }.getOrDefault(false)

    @JvmStatic
    fun clear(context: Context, pid: Int): Boolean = runCatching {
        context.contentResolver.call(URI, METHOD_CLEAR, null, Bundle().apply { putInt(KEY_PID, pid) })
            ?.getBoolean(KEY_ACCEPTED, false) == true
    }.getOrDefault(false)

    @JvmStatic
    fun currentPid(context: Context): Int = runCatching {
        context.contentResolver.call(URI, METHOD_STATUS, null, null)?.getInt(KEY_PID, 0) ?: 0
    }.getOrDefault(0)
}

internal object SystemUiHookReadinessPolicy {
    fun canRegister(callerUid: Int, systemUiUid: Int, callerPid: Int, claimedPid: Int): Boolean =
        systemUiUid >= 0 && callerUid == systemUiUid && callerPid > 0 && claimedPid == callerPid

    fun canRead(callerUid: Int, appUid: Int, systemUiUid: Int): Boolean =
        callerUid == 0 || callerUid == appUid || (systemUiUid >= 0 && callerUid == systemUiUid)
}
