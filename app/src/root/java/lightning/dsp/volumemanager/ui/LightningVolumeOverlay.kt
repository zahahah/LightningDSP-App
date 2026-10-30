package lightning.dsp.volumemanager.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager
import lightning.dsp.volumemanager.backend.VolumeManagerState

/** Touch-friendly floating mixer styled like the reference app's centered, rounded panel. */
object LightningVolumeOverlay {
    private const val DISMISS_TIMEOUT_MS = 3_300L
    private const val FADE_OUT_DURATION_MS = 500L
    private const val DISMISS_CLEANUP_GRACE_MS = 100L
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var panel: FrameLayout? = null
    private var lifecycleOwner: PanelLifecycleOwner? = null
    private var isDismissing = false
    private var dismissalGeneration = 0L
    private var state by mutableStateOf(VolumeManagerState())
    private var observer: Observer<VolumeManagerState>? = null
    private var idlePolicy: VolumeOverlayIdlePolicy? = null
    private val dismiss = Runnable {
        if (idlePolicy?.remainingMillis(SystemClock.uptimeMillis()) == 0L) beginDismissal(explicit = false)
        else scheduleDismiss()
    }

    fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(context.applicationContext) }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            hide()
            LightningDspVolumeManager.reportOverlayUnavailable("Floating panel permission is unavailable")
            return
        }
        panel?.takeIf { it.isAttachedToWindow }?.let {
            it.animate().cancel()
            dismissalGeneration++
            it.alpha = 1f
            isDismissing = false
            LightningDspVolumeManager.reportOverlayAvailable()
            idlePolicy?.reopened(SystemClock.uptimeMillis())
            scheduleDismiss()
            return
        }
        if (panel != null) hide()
        if (!LightningDspVolumeManager.isEnabled) return

        val owner = PanelLifecycleOwner()
        val panelContext = ContextThemeWrapper(context.applicationContext, lightning.dsp.R.style.Theme_RootlessLightningDSP)
        val idle = VolumeOverlayIdlePolicy(DISMISS_TIMEOUT_MS, SystemClock.uptimeMillis())
        val host = object : FrameLayout(panelContext) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE ->
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.TOUCH, true)
                    MotionEvent.ACTION_OUTSIDE -> { hide(); return true }
                }
                return try { super.dispatchTouchEvent(event) } finally {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.TOUCH, false)
                }
            }

            override fun dispatchHoverEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.HOVER, true)
                    MotionEvent.ACTION_HOVER_EXIT ->
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.HOVER, false)
                }
                return super.dispatchHoverEvent(event)
            }

            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.ACCESSIBILITY, true)
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED ->
                        updateInteraction(this, idle, VolumeOverlayIdlePolicy.Interaction.ACCESSIBILITY, false)
                }
                return super.requestSendAccessibilityEvent(child, event)
            }
        }
        // Compose's window recomposer looks up owners from the WindowManager root.
        // Setting them only on ComposeView is too late for this detached window.
        host.setViewTreeLifecycleOwner(owner)
        host.setViewTreeSavedStateRegistryOwner(owner)
        val composeView = ComposeView(panelContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LightningVolumeFloatingPanel(
                    state = state,
                    onClose = ::hide,
                    onPanelFocusChanged = { focused ->
                        updateInteraction(host, idle, VolumeOverlayIdlePolicy.Interaction.FOCUS, focused)
                    }
                )
            }
        }
        host.addView(composeView, FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))
        val manager = context.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            manager.addView(host, params)
        } catch (_: WindowManager.BadTokenException) {
            owner.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            LightningDspVolumeManager.reportOverlayUnavailable("Floating panel window token was rejected")
            return
        } catch (_: SecurityException) {
            owner.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            LightningDspVolumeManager.reportOverlayUnavailable("Floating panel permission was denied")
            return
        }
        windowManager = manager
        panel = host
        idlePolicy = idle
        lifecycleOwner = owner
        dismissalGeneration++
        isDismissing = false
        owner.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        LightningDspVolumeManager.reportOverlayAvailable()

        lateinit var currentObserver: Observer<VolumeManagerState>
        currentObserver = Observer { next ->
            if (panel === host && observer === currentObserver) {
                if (!LightningDspVolumeManager.isEnabled) hide()
                else state = if (next.enabled) next else next.copy(enabled = true)
            }
        }
        observer = currentObserver
        LightningDspVolumeManager.state.observeForever(currentObserver)
        scheduleDismiss(host)
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { hide() }; return }
        beginDismissal(explicit = true)
    }

    private fun beginDismissal(explicit: Boolean) {
        idlePolicy?.requestDismissal(explicit)
        handler.removeCallbacks(dismiss)
        val current = panel
        val manager = windowManager
        if (current == null || isDismissing) return

        val generation = ++dismissalGeneration
        isDismissing = true
        current.animate().cancel()
        current.animate().alpha(0f).setDuration(FADE_OUT_DURATION_MS).withEndAction {
            finishDismissal(current, manager, generation)
        }.start()
        // Keep window cleanup deterministic if an OEM cancels the property animation callback.
        handler.postDelayed({ finishDismissal(current, manager, generation) },
            FADE_OUT_DURATION_MS + DISMISS_CLEANUP_GRACE_MS)
    }

    private fun finishDismissal(current: FrameLayout, manager: WindowManager?, generation: Long) {
        if (panel !== current || !isDismissing || dismissalGeneration != generation) return
        observer?.let { LightningDspVolumeManager.state.removeObserver(it) }
        observer = null
        panel = null
        idlePolicy = null
        windowManager = null
        lifecycleOwner?.lifecycleRegistry?.currentState = Lifecycle.State.DESTROYED
        lifecycleOwner = null
        isDismissing = false
        manager?.let { runCatching { it.removeView(current) } }
    }

    private fun updateInteraction(host: FrameLayout, idle: VolumeOverlayIdlePolicy,
                                  kind: VolumeOverlayIdlePolicy.Interaction, active: Boolean) {
        idle.setInteraction(kind, active, SystemClock.uptimeMillis())
        if (panel !== host || idlePolicy !== idle) return
        handler.removeCallbacks(dismiss)
        if (active && isDismissing && idle.mayResumeOnInteraction()) {
            host.animate().cancel()
            dismissalGeneration++
            host.alpha = 1f
            isDismissing = false
        }
        scheduleDismiss(host)
    }

    private fun scheduleDismiss(expected: FrameLayout? = panel) {
        val current = expected ?: return
        if (panel !== current || !current.isAttachedToWindow || isDismissing) return
        handler.removeCallbacks(dismiss)
        val delay = idlePolicy?.remainingMillis(SystemClock.uptimeMillis()) ?: return
        handler.postDelayed(dismiss, delay)
    }

    private class PanelLifecycleOwner : SavedStateRegistryOwner {
        val lifecycleRegistry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this).apply { performRestore(null) }
        init { lifecycleRegistry.currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }
}
