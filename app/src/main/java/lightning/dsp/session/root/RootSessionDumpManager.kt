package lightning.dsp.session.root

import android.content.*
import android.os.Handler
import android.os.Looper
import lightning.dsp.session.dump.DumpManager
import lightning.dsp.session.dump.data.ISessionInfoDump
import lightning.dsp.session.shared.BaseSessionManager


class RootSessionDumpManager(context: Context) : BaseSessionManager(context)
{
    private var onSessionDump: ((sessionDump: ISessionInfoDump?) -> Unit)? = null
    private val confirmationHandler = Handler(Looper.getMainLooper())
    private var confirmation: Runnable? = null
    private var confirmationGeneration = 0L
    private var disposed = false
    private var onDumpMethodChanged: (() -> Unit)? = null

    fun setOnSessionDump(callback: ((sessionDump: ISessionInfoDump?) -> Unit)?) {
        onSessionDump = callback
    }

    fun setOnDumpMethodChanged(callback: (() -> Unit)?) {
        onDumpMethodChanged = callback
    }

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        if (!disposed) onSessionDump?.invoke(sessionDump)
    }

    /** One fresh confirmation per pending inactivity transition, never a polling loop. */
    fun requestPlaybackActivityConfirmation(required: Boolean) {
        if (!required || disposed) {
            confirmationGeneration++
            confirmation?.let(confirmationHandler::removeCallbacks)
            confirmation = null
            return
        }
        if (confirmation != null) return
        val generation = ++confirmationGeneration
        confirmation = Runnable {
            if (disposed || generation != confirmationGeneration) return@Runnable
            confirmation = null
            pollOnce(true)
        }.also { confirmationHandler.postDelayed(it, 1_000) }
    }

    override fun destroy() {
        disposed = true
        requestPlaybackActivityConfirmation(false)
        onSessionDump = null
        super.destroy()
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        onDumpMethodChanged?.invoke()
        super.onDumpMethodChange(method)
    }
}