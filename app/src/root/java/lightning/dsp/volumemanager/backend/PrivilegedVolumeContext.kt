package lightning.dsp.volumemanager.backend

import android.content.Context
import android.os.Looper

/** Use the existing Shizuku ActivityThread instead of replacing its process state. */
internal object PrivilegedVolumeContext {
    @Synchronized fun obtain(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val existing = type.getDeclaredMethod("currentActivityThread").invoke(null)
        val thread = existing ?: run {
            if (Looper.myLooper() == null) Looper.prepare()
            type.getDeclaredMethod("systemMain").invoke(null)
        }
        return type.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }
}
