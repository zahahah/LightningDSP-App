package lightning.dsp.volumemanager.ui

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import lightning.dsp.volumemanager.backend.LightningDspVolumeManager

/** Explicitly enabled key filtering only. No screen contents, gesture injection, or root grants. */
class LightningVolumeAccessibilityService : AccessibilityService() {
    companion object {
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, LightningVolumeAccessibilityService::class.java)
            return Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any {
                ComponentName.unflattenFromString(it) == component
            } == true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val foreground by lazy { VolumeKeyForegroundPolicy(packageName) }
    private var heldKey: Int? = null
    private val repeat = object : Runnable {
        override fun run() {
            if (heldKey == null || !eligible()) { stopRepeating(); return }
            if (!adjust()) { stopRepeating(); return }
            handler.postDelayed(this, 100L)
        }
    }
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (eligible()) LightningVolumeOverlay.show(this@LightningVolumeAccessibilityService)
        }
    }

    override fun onServiceConnected() {
        LightningDspVolumeManager.initialize(applicationContext)
        stopRepeating()
        foreground.reset()
        LightningDspVolumeManager.setForegroundPackage(null)
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Validate an activity component, not a view/dialog class or window's screen contents.
            val owner = event.packageName?.toString()?.takeIf { it.isNotBlank() }
            val activityClass = event.className?.toString()?.takeIf { it.isNotBlank() }
            val activity = if (owner == null || activityClass == null) false else runCatching {
                val qualifiedClass = if (activityClass.startsWith(".")) owner + activityClass else activityClass
                @Suppress("DEPRECATION")
                val info = packageManager.getActivityInfo(ComponentName(owner, qualifiedClass), 0)
                info.packageName == owner
            }.getOrDefault(false)
            foreground.windowChanged(owner, activity)
            LightningDspVolumeManager.setForegroundPackage(foreground.foregroundPackage)
            if (!eligible()) stopRepeating()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_UP && heldKey == event.keyCode) {
            stopRepeating()
            return true
        }
        if (!eligible()) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (heldKey == event.keyCode) return true
        stopRepeating()
        heldKey = event.keyCode
        if (!adjust()) {
            stopRepeating()
            return false
        }
        handler.postDelayed(repeat, 500L)
        return true
    }

    private fun eligible(): Boolean {
        val state = LightningDspVolumeManager.state.value ?: return false
        return state.enabled && state.connected && Settings.canDrawOverlays(this) &&
            foreground.mayIntercept(state.apps.filter { it.volumeButtonsIgnored }.map { it.packageName }.toSet())
    }

    private fun adjust(): Boolean {
        val direction = if (heldKey == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            if (!LightningDspVolumeManager.adjustSystemVolume(direction)) return false
            LightningVolumeOverlay.show(this)
            true
        } catch (_: SecurityException) { stopRepeating(); false }
    }

    private fun stopRepeating() {
        handler.removeCallbacks(repeat)
        heldKey = null
    }

    override fun onInterrupt() {
        stopRepeating()
        foreground.reset()
        LightningDspVolumeManager.setForegroundPackage(null)
        LightningVolumeOverlay.hide()
    }
    override fun onDestroy() {
        stopRepeating()
        foreground.reset()
        LightningDspVolumeManager.setForegroundPackage(null)
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        LightningVolumeOverlay.hide()
        super.onDestroy()
    }
}
