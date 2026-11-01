package lightning.dsp.service

import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.core.content.getSystemService
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lightning.dsp.BuildConfig
import lightning.dsp.MainApplication
import lightning.dsp.R
import lightning.dsp.flavor.VolumeManagerLifecycle
import lightning.dsp.interop.GlobalCoverageMonitor
import lightning.dsp.interop.LightningDspRemoteEngine
import lightning.dsp.model.IEffectSession
import lightning.dsp.model.room.AppBlocklistDatabase
import lightning.dsp.model.room.AppBlocklistRepository
import lightning.dsp.model.room.BlockedApp
import lightning.dsp.session.root.OnRootSessionChangeListener
import lightning.dsp.session.root.RootSessionDumpManager
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import lightning.dsp.utils.extensions.ContextExtensions.registerLocalReceiver
import lightning.dsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import lightning.dsp.utils.notifications.StatusNotificationGroup
import lightning.dsp.utils.notifications.Notifications
import lightning.dsp.utils.notifications.ServiceNotificationHelper
import lightning.dsp.utils.preferences.Preferences
import lightning.dsp.utils.sdkAbove
import lightning.dsp.utils.isRoot
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import timber.log.Timber

open class RootAudioProcessorService : BaseAudioProcessorService(), KoinComponent,
    SharedPreferences.OnSharedPreferenceChangeListener, OnRootSessionChangeListener {

    // System services
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Termination flags
    private var isServiceDisposing = false

    private val backgroundScope = MainScope()
    private var backgroundStartup: Job? = null

    // Enhanced processing
    private var sessionDumpManager: RootSessionDumpManager? = null
    private var globalCoverageMonitor: GlobalCoverageMonitor? = null

    // Preferences
    private val preferences: Preferences.App by inject()

    private val dspPreferencesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PREFERENCES_UPDATED) {
                app.rootSessionDatabase.syncWithPreferences()
            }
        }
    }

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> { apps ->
        if(!app.isEnhancedProcessing)
            return@Observer

        apps?.map { it.uid }?.let { uids ->
            Timber.d("blockedAppObserver: Excluded UIDs: ${uids.joinToString("; ")}")

            app.rootSessionDatabase.setExcludedUids(uids.toTypedArray())
            sessionDumpManager?.pollOnce(false)
        }
    }


    // App object
    private val app
        get() = application as MainApplication

    override fun onCreate() {
        super.onCreate()

        // Boot/soft-reboot may leave an output route selected without a fresh MediaRouter event.
        app.profileManager.refreshCurrentDevice()

        // Register shared preferences listener
        preferences.registerOnSharedPreferenceChangeListener(this)
        app.rootSessionDatabase.registerOnSessionChangeListener(this)
        registerLocalReceiver(dspPreferencesReceiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!
        if (isRoot()) {
            globalCoverageMonitor = GlobalCoverageMonitor(this,
                shouldMonitor = { !isServiceDisposing && app.isLegacyMode &&
                    app.rootSessionDatabase.enabled && app.rootSessionDatabase.hasUsableGlobalSession() },
                reconcile = { app.rootSessionDatabase.reconcileGlobalOutput() },
            )
        }

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        // Launch foreground service
        val notification = if (app.isLegacyMode)
            ServiceNotificationHelper.createServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.createServiceNotification(this, arrayOf())

        sdkAbove(Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification,
                if (isRoot() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }.below {
            startForeground(
                Notifications.ID_SERVICE_STATUS,
                notification
            )
        }

        StatusNotificationGroup.serviceStarted(this, Notifications.ID_SERVICE_STATUS)

        // Preserve power without creating/clearing contexts before factory discovery.
        onSharedPreferenceChanged(preferences.preferences, getString(R.string.key_powered_on))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")
        if (intent == null) {
            if (isRoot()) {
                VolumeManagerLifecycle.onBackgroundEntry(this)
                startBackgroundDiscovery()
            }
            return START_STICKY
        }

        if (intent.action == ACTION_STOP) {
            backgroundStartup?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_START_BACKGROUND && isRoot()) {
            VolumeManagerLifecycle.onBackgroundEntry(this)
            startBackgroundDiscovery()
            return START_REDELIVER_INTENT
        }

        if(LightningDspRemoteEngine.discoveredPluginState() == LightningDspRemoteEngine.PluginState.Unavailable) {
            Timber.e("onStartCommand: Ignoring command because audio factory has no LightningDSP descriptor")
            if (backgroundStartup?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }

        initializeProcessingIfNeeded()

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START_ENHANCED_PROCESSING -> {
                if(!app.isEnhancedProcessing) {
                    Timber.e("onStartCommand: ACTION_START_ENHANCED_PROCESSING received, but isEnhancedProcessing is false")
                }
                sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
            }
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                if(app.rootSessionDatabase.sessionList.isEmpty())
                    sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))

                val duplicateGlobal = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) == 0 &&
                    app.rootSessionDatabase.hasUsableGlobalSession()
                if(!isServiceDisposing && !app.isEnhancedProcessing && !duplicateGlobal)
                    app.rootSessionDatabase.addSessionByIntent(intent)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                if(!app.isEnhancedProcessing) {
                    val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                    MainScope().launch {
                        if (sessionId != 0)
                            delay(800)
                        app.rootSessionDatabase.removeSessionByIntent(intent)
                        if (app.rootSessionDatabase.sessionList.isEmpty()) {
                            stopSelf()
                        }
                    }
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceDisposing = true
        globalCoverageMonitor?.close()
        globalCoverageMonitor = null
        backgroundStartup?.cancel()
        backgroundScope.cancel()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Notify app about service termination and unregister
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        app.rootSessionDatabase.clearSessions()

        preferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterLocalReceiver(dspPreferencesReceiver)
        app.rootSessionDatabase.unregisterOnSessionChangeListener(this)

        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
        StatusNotificationGroup.serviceStopped(this, Notifications.ID_SERVICE_STATUS)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            getString(R.string.key_audioformat_processing) -> {
                updateServiceNotification()
                globalCoverageMonitor?.requestCheck()
            }
            getString(R.string.key_powered_on) -> {
                app.rootSessionDatabase.enabled = sharedPreferences?.getBoolean(key, true) ?: true
                globalCoverageMonitor?.requestCheck()
                updateServiceNotification()
            }
            getString(R.string.key_audioformat_enhanced_processing) -> {
                // If switched to enhanced processing while the service was already active...
                if(app.isEnhancedProcessing) {
                    setupEnhancedProcessing()
                }
                else {
                    sessionDumpManager?.destroy()
                    sessionDumpManager = null
                    app.rootSessionDatabase.setExcludedUids(arrayOf())
                    app.rootSessionDatabase.clearSessions()
                }
            }
        }
    }

    /** Discovery retries are limited to boot/late-load requests, never normal controls. */
    private fun startBackgroundDiscovery() {
        if (backgroundStartup?.isActive == true) return
        backgroundStartup = backgroundScope.launch {
            for (attempt in 0 until BackgroundAudioStartupPolicy.MAX_ATTEMPTS) {
                val state = withContext(Dispatchers.IO) {
                    LightningDspRemoteEngine.discoveredPluginState()
                }
                if (state == LightningDspRemoteEngine.PluginState.Available) {
                    initializeProcessingIfNeeded()
                    if (BackgroundAudioStartupPolicy.shouldOpenGlobalSession(
                            app.isLegacyMode, app.rootSessionDatabase.hasUsableGlobalSession())) {
                        if (app.rootSessionDatabase.sessionList.containsKey(0))
                            app.rootSessionDatabase.removeSession(0)
                        app.rootSessionDatabase.addSession(0, android.os.Process.myUid(), packageName)
                    }
                    sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
                    return@launch
                }
                if (!BackgroundAudioStartupPolicy.shouldRetry(
                        attempt, state == LightningDspRemoteEngine.PluginState.Unsupported)) break
                delay(BackgroundAudioStartupPolicy.RETRY_INTERVAL_MS)
            }
            // Keep existing contexts and foreground lifecycle intact on discovery failure.
            Timber.w("Background audio startup: descriptor unavailable after bounded discovery")
        }
    }

    private fun initializeProcessingIfNeeded() {
        if (app.isEnhancedProcessing && sessionDumpManager == null) setupEnhancedProcessing()
    }

    private fun setupEnhancedProcessing() {
        app.rootSessionDatabase.clearSessions()

        sessionDumpManager = RootSessionDumpManager(this)
        sessionDumpManager?.setOnSessionDump(app.rootSessionDatabase::update)
        sessionDumpManager?.setOnDumpMethodChanged(app.rootSessionDatabase::clearSessions)
        sessionDumpManager?.pollOnce(false)
    }

    override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
        globalCoverageMonitor?.requestCheck()
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        if(preferences.get<Boolean>(R.string.key_audioformat_processing))
            ServiceNotificationHelper.pushServiceNotificationLegacy(this)
        else
            ServiceNotificationHelper.pushServiceNotification(this, app.rootSessionDatabase.sessionList.values.toTypedArray())
    }

    companion object {
        const val ACTION_START_ENHANCED_PROCESSING = BuildConfig.APPLICATION_ID + ".root.service.START_ENHANCED"
        const val ACTION_START_BACKGROUND = BuildConfig.APPLICATION_ID + ".root.service.START_BACKGROUND"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".root.service.STOP"

        /** No Activity or synchronous root/plugin probe on the boot receiver thread. */
        fun startBackground(context: Context) {
            val intent = Intent(context,
                me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService::class.java
            ).setAction(ACTION_START_BACKGROUND)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (ex: Exception) {
                Timber.w(ex, "Background audio startup was not permitted")
            }
        }

        fun startService(context: Context, intent: Intent) {
            Timber.d("startService: intent=$intent")

            // Prevent launch if plugin missing
            if(intent.action != ACTION_STOP &&
                LightningDspRemoteEngine.isPluginInstalled() == LightningDspRemoteEngine.PluginState.Unavailable) {
                Timber.e("Service launch cancelled. Plugin not installed.")
                return
            }

            try {
                sdkAbove(Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                }.below {
                    context.startService(intent)
                }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun startServiceEnhanced(context: Context) {
            try {
                Intent(context, me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_START_ENHANCED_PROCESSING }
                    .run { startService(context, this) }
            }
            catch (ex: Exception) {
                Timber.e("Failed to start service. Try to disable battery optimizations")
                Timber.e(ex)
            }
        }

        fun stopService(context: Context) {
            try {
                Intent(context, me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService::class.java)
                    .apply { this.action = ACTION_STOP }
                    .run { startService(context, this) }
            }
            catch(ex: Exception) {
                Timber.e(ex)
            }
        }

        fun updateLegacyMode(context: Context, enable: Boolean) {
            Timber.d("updateLegacyMode: enable=$enable")

            val action =
                if (enable) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            Intent(context, me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService::class.java)
                .apply { this.action = action }
                .apply { putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) }
                .run { startService(context, this) }
        }
    }
}
