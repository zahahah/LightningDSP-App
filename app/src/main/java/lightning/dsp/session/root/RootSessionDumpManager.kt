package lightning.dsp.session.root

import android.content.*
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import lightning.dsp.MainApplication
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
    private data class ObservedMediaController(
        val callback: MediaController.Callback,
        var playbackState: PlaybackState?,
    )
    private val mediaControllers = linkedMapOf<MediaController, ObservedMediaController>()

    fun setOnSessionDump(callback: ((sessionDump: ISessionInfoDump?) -> Unit)?) {
        onSessionDump = callback
    }

    fun setOnDumpMethodChanged(callback: (() -> Unit)?) {
        onDumpMethodChanged = callback
    }

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        if (!disposed) onSessionDump?.invoke(sessionDump)
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        super.onActiveSessionsChanged(controllers)
        if (disposed) return
        val current = controllers.orEmpty().toList()
        confirmationHandler.post {
            if (disposed) return@post
            val currentSet = current.toSet()
            mediaControllers.keys.filterNot(currentSet::contains).toList().forEach { controller ->
                mediaControllers.remove(controller)?.let { controller.unregisterCallback(it.callback) }
            }
            current.forEach { controller ->
                if (controller in mediaControllers) return@forEach
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (disposed) return
                        mediaControllers[controller]?.playbackState = state
                        publishMediaPlayingPackages()
                    }
                }
                mediaControllers[controller] = ObservedMediaController(callback, controller.playbackState)
                controller.registerCallback(callback, confirmationHandler)
            }
            publishMediaPlayingPackages()
        }
    }

    private fun publishMediaPlayingPackages() {
        val packages = mediaControllers.entries.asSequence()
            .filter { it.value.playbackState?.state == PlaybackState.STATE_PLAYING }
            .mapNotNull { it.key.packageName }
            .toSet()
        (context.applicationContext as? MainApplication)?.rootSessionDatabase
            ?.setMediaPlayingPackages(packages)
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
        mediaControllers.forEach { (controller, observed) -> controller.unregisterCallback(observed.callback) }
        mediaControllers.clear()
        (context.applicationContext as? MainApplication)?.rootSessionDatabase
            ?.setMediaPlayingPackages(emptySet())
        super.destroy()
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        onDumpMethodChanged?.invoke()
        super.onDumpMethodChange(method)
    }
}
