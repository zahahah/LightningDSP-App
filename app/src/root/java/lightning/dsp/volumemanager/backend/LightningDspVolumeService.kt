package lightning.dsp.volumemanager.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import lightning.dsp.volumemanager.ui.LightningDspVolumeManagerActivity
import android.content.Intent
import android.os.IBinder

/** Keeps the explicitly enabled manager alive without tying it to the settings Activity. */
class LightningDspVolumeService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "LightningDSP Volume Manager", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(7391, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(PendingIntent.getActivity(this, 7391,
                Intent(this, LightningDspVolumeManagerActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setContentTitle("LightningDSP Volume Manager")
            .setContentText("LightningDSP volume panel is enabled")
            .setOngoing(true).build())
        LightningDspVolumeManager.initialize(this)
        // Once per foreground-service instance, including opted-in boot/resume.
        // Permission alone is not proof that WindowManager accepts our panel.
        if (LightningDspVolumeManager.isEnabled && android.provider.Settings.canDrawOverlays(this))
            lightning.dsp.volumemanager.ui.LightningVolumeOverlay.show(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LightningDspVolumeManager.isEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        LightningDspVolumeManager.attach(this)
        return START_STICKY
    }

    override fun onDestroy() {
        LightningDspVolumeManager.detach(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "lightningdsp_volume_manager" }
}
