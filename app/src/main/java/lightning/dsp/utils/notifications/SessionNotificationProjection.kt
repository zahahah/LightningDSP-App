package lightning.dsp.utils.notifications

import lightning.dsp.model.IEffectSession

/** Keep notification text app-oriented while processing remains session-oriented. */
internal object SessionNotificationProjection {
    fun uniqueApps(sessions: Array<out IEffectSession>, rootless: Boolean): List<IEffectSession> =
        sessions.distinctBy { session ->
            if (rootless) session.uid
            else session.packageName.takeIf(String::isNotBlank) ?: "uid:${session.uid}"
        }
}
