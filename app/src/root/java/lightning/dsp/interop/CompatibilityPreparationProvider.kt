package lightning.dsp.interop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** A request is only a hint: the controller independently verifies UID, session and output. */
internal object CompatibilityPreparationBridge {
    @Volatile var handler: ((Int, Int, Long, (Boolean) -> Unit) -> Unit)? = null
}

class CompatibilityPreparationProvider : ContentProvider() {
    private val slots = Semaphore(2)
    private val admission = CompatibilityPreparationAdmission()
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val deadline = minOf(extras?.getLong("deadline", 0) ?: 0, SystemClock.elapsedRealtime() + 2_000)
        val session = arg?.toIntOrNull() ?: return result(false)
        if (deadline <= SystemClock.elapsedRealtime() || method != "prepare" || session <= 0 || uid % 100000 !in 10000..19999 ||
            Looper.myLooper() == Looper.getMainLooper() || !slots.tryAcquire()) return result(false)
        try {
            val now = SystemClock.elapsedRealtime()
            if (!admission.admit(uid, now)) return result(false)
            val handler = CompatibilityPreparationBridge.handler ?: return result(false)
            val completed = CountDownLatch(1)
            var prepared = false
            handler(uid, session, deadline) { success -> prepared = success; completed.countDown() }
            val ready = completed.await(maxOf(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) && prepared
            if (ready) admission.succeeded(uid, now)
            return result(ready)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return result(false)
        } finally { slots.release() }
    }
    private fun result(prepared: Boolean) = Bundle().apply { putBoolean("prepared", prepared) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
