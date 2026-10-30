package lightning.dsp.volumemanager.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import lightning.dsp.volumemanager.ui.LightningDspVolumeManagerActivity
import lightning.dsp.utils.notifications.StatusNotificationGroup
import lightning.dsp.utils.notifications.processingNotificationIcon
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import lightning.dsp.R
import lightning.dsp.utils.Constants
import android.os.IBinder

/** Keeps the explicitly enabled manager alive without tying it to the settings Activity. */
class LightningDspVolumeService : Service() {
    private val appPreferences by lazy { getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE) }
    private val powerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == getString(R.string.key_powered_on)) {
            getSystemService(NotificationManager::class.java).notify(StatusNotificationGroup.VOLUME_ID, createNotification())
            StatusNotificationGroup.refresh(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "LightningDSP Volume Manager", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(StatusNotificationGroup.VOLUME_ID, createNotification())
        StatusNotificationGroup.serviceStarted(this, StatusNotificationGroup.VOLUME_ID)
        appPreferences.registerOnSharedPreferenceChangeListener(powerListener)
        LightningDspVolumeManager.initialize(this)
    }

    private fun createNotification() = Notification.Builder(this, CHANNEL)
        // The bolt follows DSP power even when only the mixer service is running.
        .setSmallIcon(processingNotificationIcon(
            appPreferences.getBoolean(getString(R.string.key_powered_on), true)))
        .setContentIntent(PendingIntent.getActivity(this, 7391,
            Intent(this, LightningDspVolumeManagerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setContentTitle("LightningDSP Volume Manager")
        .setContentText("LightningDSP volume panel is enabled")
        .setOnlyAlertOnce(true)
        .setOngoing(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LightningDspVolumeManager.isEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        LightningDspVolumeManager.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        appPreferences.unregisterOnSharedPreferenceChangeListener(powerListener)
        LightningDspVolumeManager.detach(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(StatusNotificationGroup.VOLUME_ID)
        StatusNotificationGroup.serviceStopped(this, StatusNotificationGroup.VOLUME_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "lightningdsp_volume_manager" }
}
