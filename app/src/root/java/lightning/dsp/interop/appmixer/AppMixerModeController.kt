package lightning.dsp.interop.appmixer

import android.content.Context
import android.content.SharedPreferences
import lightning.dsp.session.shared.SESSION_DSP_ENABLED_PREFIX
import lightning.dsp.R
import lightning.dsp.utils.Constants
import java.util.concurrent.Executors
import timber.log.Timber

/** Serial, root-only owner. All I/O and waits stay off the main thread. */
internal class AppMixerModeController(
    context: Context,
    private val acquireExclusive: () -> AutoCloseable?,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val panel = app.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val launcher = AppMixerProcessLauncher(app)
    private val inventory = AppMixerUidInventory(app)
    private val appPrefs = app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
    private fun modeAndPowerWanted(): Boolean =
        appPrefs.getBoolean(app.getString(R.string.key_audioformat_processing_app_mixer), false) &&
            appPrefs.getBoolean(app.getString(R.string.key_powered_on), true)
    private val effect = AppMixerEffectPreparer(app, ::modeAndPowerWanted)
    private var requested = false
    private var running = false
    private var selected = emptySet<Int>()
    private var disposed = false
    private var standbyLease: AutoCloseable? = null
    private var lastStatus: String? = null
    private fun status(value: String) {
        if (value != lastStatus) { lastStatus = value; onStatus(value) }
    }
    private val supervisor: AppMixerSupervisor = AppMixerSupervisor({ launcher.launch(selected) }, acquireExclusive, effect::prepare,
        { modeAndPowerWanted() && inventory.read() == selected }) { clean ->
        runCatching {
            executor.execute {
                if (requested && !supervisor.isActive()) {
                    running = false
                    status(if (clean) "App Mixer relay stopped; coverage is unavailable until retry."
                        else "App Mixer stopped with uncertain cleanup; other DSP remains fenced.")
                }
            }
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(SESSION_DSP_ENABLED_PREFIX)) runCatching { executor.execute { reconcile(false) } }
    }

    init {
        panel.registerOnSharedPreferenceChangeListener(listener)
    }

    fun request(enable: Boolean) {
        runCatching { executor.execute { requested = enable; reconcile(false) } }
    }

    private fun reconcile(renew: Boolean) {
        if (disposed) return
        val next = if (requested) inventory.read() else emptySet()
        if (next == null || next.isEmpty()) {
            supervisor.close()
            running = false
            selected = emptySet()
            if (!requested) { runCatching { standbyLease?.close() }; standbyLease = null }
            if (supervisor.hasOwner()) {
                status("App Mixer cleanup is uncertain; other DSP remains fenced.")
                return
            }
            if (requested && standbyLease == null) standbyLease = runCatching(acquireExclusive).getOrNull()
            if (requested) {
                Timber.w("App Mixer unavailable: incomplete inventory or too many UID exclusions")
                status(if (standbyLease == null)
                    "App Mixer unavailable; exclusive handoff failed or cleanup is uncertain."
                else "App Mixer unavailable: incomplete inventory or too many app exclusions.")
            } else status("App Mixer off requested; cleanup may be pending.")
            return
        }
        if (running && !renew && selected == next) return
        if (running && !renew) {
            if (supervisor.updateExclusions(next)) {
                selected = next
                status("App Mixer rules updated. Route coverage is not verified.")
            } else {
                running = false
                Timber.w("App Mixer rule update failed: %s", supervisor.lastFailureReason)
                status("App Mixer rule update failed; requested app selection was not applied. Check logs.")
            }
            return
        }
        runCatching { standbyLease?.close() }
        standbyLease = null
        if (running) supervisor.close()
        running = false
        selected = next
        if (requested) {
            running = supervisor.start()
            if (!running) {
                val reason = supervisor.lastFailureReason ?: "an existing relay or exclusive owner is still active"
                Timber.w("App Mixer startup failed: %s; original route is not claimed", reason)
                status("App Mixer failed: $reason. Other DSP may remain fenced; check logs.")
            } else status("App Mixer relay started. Route coverage is not verified.")
        }
    }

    override fun close() {
        panel.unregisterOnSharedPreferenceChangeListener(listener)
        runCatching {
            executor.execute {
                disposed = true
                requested = false
                supervisor.close()
                runCatching { standbyLease?.close() }
                standbyLease = null
                executor.shutdownNow()
            }
        }
    }
}
