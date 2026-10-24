package lightning.dsp.service

import android.service.notification.NotificationListenerService
import android.content.Intent
import android.os.IBinder
import android.service.notification.StatusBarNotification

open class NotificationListenerService : NotificationListenerService() {
    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}