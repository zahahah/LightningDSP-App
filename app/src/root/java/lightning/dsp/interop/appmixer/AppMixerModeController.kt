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
    private val directPolicyUpdates = AppMixerPolicyMixAidl.isAvailable()
    private val launcher = AppMixerProcessLauncher(app, directPolicyUpdates)
    private val inventory = AppMixerUidInventory(app, directPolicyUpdates)
    private val appPrefs = app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
    private fun modeAndPowerWanted(): Boolean =
        appPrefs.getBoolean(app.getString(R.string.key_audioformat_processing_app_mixer), false) &&
            appPrefs.getBoolean(app.getString(R.string.key_powered_on), true)
    private val effect = AppMixerEffectPreparer(app, ::modeAndPowerWanted)
    private var requested = false
    private var running = false
    private var selected = emptySet<Int>()
    private var selectedSystemSounds = true
    private var selectedStabilityMode = false
    private var selectedUsers = setOf(android.os.Process.myUid() / 100000)
    private var selectedWorkProfileIncluded = false
    private var disposed = false
    private var standbyLease: AutoCloseable? = null
    private var lastStatus: String? = null
    private fun status(value: String) {
        if (value != lastStatus) { lastStatus = value; onStatus(value) }
    }
    private val stabilityModeKey = app.getString(R.string.key_audioformat_fusion_stability_mode)
    private val supervisor: AppMixerSupervisor = AppMixerSupervisor({
        launcher.launch(selected, selectedSystemSounds, selectedUsers,
            appPrefs.getBoolean(stabilityModeKey, false))
    }, acquireExclusive, effect::prepare,
        { modeAndPowerWanted() && inventory.readRoute(selectedSystemSounds)?.let {
            it.userIds == selectedUsers && it.excludedUids == selected &&
                it.includesSystemSounds == selectedSystemSounds && it.workProfileIncluded == selectedWorkProfileIncluded
        } == true }, directPolicyUpdates = directPolicyUpdates) { clean ->
        runCatching {
            executor.execute {
                if (requested && !supervisor.isActive()) {
                    running = false
                    status(if (clean) "Fusion Mode relay stopped; coverage is unavailable until retry."
                        else "Fusion Mode stopped with uncertain cleanup; other DSP remains fenced.")
                }
            }
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(SESSION_DSP_ENABLED_PREFIX) || key == "process_work_profile_audio") runCatching { executor.execute { reconcile(false) } }
    }
    private val appListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            app.getString(R.string.key_audioformat_include_system_sounds) ->
                runCatching { executor.execute { reconcile(false) } }
            stabilityModeKey -> runCatching { executor.execute { if (requested) reconcile(renew = true) } }
        }
    }

    init {
        panel.registerOnSharedPreferenceChangeListener(listener)
        appPrefs.registerOnSharedPreferenceChangeListener(appListener)
        executor.scheduleWithFixedDelay({ if (requested) reconcile(false) }, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun request(enable: Boolean) {
        runCatching { executor.execute { requested = enable; reconcile(false) } }
    }

    private fun reconcile(renew: Boolean) {
        if (disposed) return
        val requestedSystemSounds = appPrefs.getBoolean(app.getString(R.string.key_audioformat_include_system_sounds), true)
        val requestedStabilityMode = appPrefs.getBoolean(stabilityModeKey, false)
        val route = if (requested) inventory.readRoute(requestedSystemSounds) else null
        val next = if (requested) route?.excludedUids else emptySet()
        val nextUsers = route?.userIds ?: setOf(android.os.Process.myUid() / 100000)
        val nextSystemSounds = route?.includesSystemSounds ?: requestedSystemSounds
        val nextWorkProfileIncluded = route?.workProfileIncluded ?: false
        val systemSoundFallback = requested && requestedSystemSounds && route != null && !route.includesSystemSounds
        if (next == null || next.isEmpty()) {
            supervisor.close()
            running = false
            selected = emptySet()
            if (!requested) { runCatching { standbyLease?.close() }; standbyLease = null }
            if (supervisor.hasOwner()) {
                status("Fusion Mode cleanup is uncertain; other DSP remains fenced.")
                return
            }
            if (requested && standbyLease == null) standbyLease = runCatching(acquireExclusive).getOrNull()
            if (requested) {
                Timber.w("Fusion Mode unavailable: incomplete inventory or too many UID exclusions")
                status(if (standbyLease == null)
                    "Fusion Mode unavailable; exclusive handoff failed or cleanup is uncertain."
                else "Fusion Mode unavailable: incomplete inventory or too many app exclusions.")
            } else status("Fusion Mode off requested; cleanup may be pending.")
            return
        }
        if (running && !renew && selected == next && selectedSystemSounds == nextSystemSounds &&
            selectedUsers == nextUsers && selectedWorkProfileIncluded == nextWorkProfileIncluded &&
            selectedStabilityMode == requestedStabilityMode) {
            status(activeStatus(systemSoundFallback, requested && panel.getBoolean("process_work_profile_audio", true) && !nextWorkProfileIncluded))
            return
        }
        if (running && !renew) {
            if (supervisor.updateRules(next, nextSystemSounds, nextUsers)) {
                selected = next
                selectedSystemSounds = nextSystemSounds
                selectedUsers = nextUsers
                selectedWorkProfileIncluded = nextWorkProfileIncluded
                status(activeStatus(systemSoundFallback, requested && panel.getBoolean("process_work_profile_audio", true) && !nextWorkProfileIncluded,
                    updated = true))
            } else {
                running = false
                Timber.w("Fusion Mode rule update failed: %s", supervisor.lastFailureReason)
                status("Fusion Mode rule update failed; requested app selection was not applied. Check logs.")
            }
            return
        }
        runCatching { standbyLease?.close() }
        standbyLease = null
        if (running) supervisor.close()
        running = false
        selected = next
        selectedSystemSounds = nextSystemSounds
        selectedStabilityMode = requestedStabilityMode
        selectedUsers = nextUsers
        selectedWorkProfileIncluded = nextWorkProfileIncluded
        if (requested) {
            running = supervisor.start()
            if (!running) {
                val reason = supervisor.lastFailureReason ?: "an existing relay or exclusive owner is still active"
                Timber.w("Fusion Mode startup failed: %s; original route is not claimed", reason)
                status("Fusion Mode failed: $reason. Other DSP may remain fenced; check logs.")
            } else status(activeStatus(systemSoundFallback, requested && panel.getBoolean("process_work_profile_audio", true) && !nextWorkProfileIncluded,
                started = true))
        }
    }

    private fun activeStatus(systemSoundFallback: Boolean, workProfileFallback: Boolean,
        updated: Boolean = false, started: Boolean = false): String {
        val prefix = when {
            started -> "Fusion Mode started"
            updated -> "Fusion Mode updated"
            else -> "Fusion Mode is active"
        }
        val limitations = buildList {
            if (workProfileFallback) add("work-profile audio is excluded because its app inventory is unavailable")
            if (systemSoundFallback) add("system sounds are excluded because too many apps are turned off")
        }
        return if (limitations.isEmpty()) app.getString(R.string.notification_fusion_relay_working)
        else "$prefix, but ${limitations.joinToString(" and ")}."
    }

    override fun close() {
        panel.unregisterOnSharedPreferenceChangeListener(listener)
        appPrefs.unregisterOnSharedPreferenceChangeListener(appListener)
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
