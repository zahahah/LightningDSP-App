package lightning.dsp.interop

import android.content.Context
import android.os.Process
import com.topjohnwu.superuser.Shell
import lightning.dsp.MainApplication
import lightning.dsp.R
import lightning.dsp.flavor.RootShellImpl
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.PermissionExtensions.hasPackageUsagePermission

/** Session Mode opt-in for retaining verified app sessions while focused or media-backed. */
object KeepEffectsReadyOption {
    private const val LEGACY_PROPERTY = "debug.ldsp.aaudio_focus_hold"
    private const val PREVIOUS_USAGE_ACCESS_MODE = "keep_effects_ready_previous_usage_access_mode"

    fun requested(context: Context): Boolean {
        val preferences = preferences(context)
        val key = context.getString(R.string.key_audioformat_keep_effects_ready)
        if (preferences.contains(key)) return preferences.getBoolean(key, false)
        val legacy = preferences.getBoolean(context.getString(R.string.key_audioformat_aaudio_focus_hold), false)
        if (legacy) preferences.edit().putBoolean(key, true).remove(
            context.getString(R.string.key_audioformat_aaudio_focus_hold)
        ).apply()
        return legacy
    }

    @Synchronized fun set(context: Context, enabled: Boolean): AAudioCompatibilityResult {
        if (enabled && !sessionMode(context)) return AAudioCompatibilityResult.FAILED
        if (!runCatching { RootShellImpl.hasRootShell() }.getOrDefault(false)) return AAudioCompatibilityResult.FAILED

        val preferences = preferences(context)
        val oldRequested = requested(context)
        var previousMode = preferences.getString(PREVIOUS_USAGE_ACCESS_MODE, null)
        val newlyGranted = enabled && !context.hasPackageUsagePermission()
        if (newlyGranted) {
            val mode = readUsageAccessMode(context) ?: return AAudioCompatibilityResult.FAILED
            previousMode = mode
            if (!preferences.edit().putString(PREVIOUS_USAGE_ACCESS_MODE, mode).commit())
                return AAudioCompatibilityResult.FAILED
            if (!setUsageAccess(context, "allow")) {
                preferences.edit().remove(PREVIOUS_USAGE_ACCESS_MODE).apply()
                return AAudioCompatibilityResult.FAILED
            }
        }

        if (!enabled && previousMode != null && !setUsageAccess(context, previousMode)) {
            write(oldRequested && sessionMode(context), context)
            return AAudioCompatibilityResult.FAILED
        }
        if (!write(enabled && sessionMode(context), context)) {
            if (newlyGranted) {
                setUsageAccess(context, previousMode ?: "default")
                preferences.edit().remove(PREVIOUS_USAGE_ACCESS_MODE).apply()
            }
            if (!enabled && previousMode != null) setUsageAccess(context, "allow")
            return AAudioCompatibilityResult.FAILED
        }

        val edit = preferences.edit()
            .putBoolean(context.getString(R.string.key_audioformat_keep_effects_ready), enabled)
            .remove(context.getString(R.string.key_audioformat_aaudio_focus_hold))
        if (!enabled || !newlyGranted) edit.remove(PREVIOUS_USAGE_ACCESS_MODE)
        if (!edit.commit()) {
            write(oldRequested && sessionMode(context), context)
            if (newlyGranted) {
                setUsageAccess(context, previousMode ?: "default")
                preferences.edit().remove(PREVIOUS_USAGE_ACCESS_MODE).apply()
            }
            if (!enabled && previousMode != null) setUsageAccess(context, "allow")
            return AAudioCompatibilityResult.FAILED
        }

        (context.applicationContext as? MainApplication)?.rootSessionDatabase
            ?.setKeepEffectsReadyEnabled(enabled && sessionMode(context))
        return AAudioCompatibilityResult.APPLIED
    }

    /** Reapplies the opt-in after service/process restart and restores only app-op state we changed. */
    @Synchronized fun restore(context: Context): Boolean {
        return try {
            if (!RootShellImpl.hasRootShell()) return false
            val enabled = requested(context)
            val effective = enabled && sessionMode(context)
            if (effective && !context.hasPackageUsagePermission() && !grantUsageAccessForFeature(context)) return false
            if (!enabled && !restorePreviousUsageAccess(context)) return false
            write(effective, context)
        } catch (_: Exception) {
            false
        }
    }

    private fun grantUsageAccessForFeature(context: Context): Boolean {
        val preferences = preferences(context)
        if (preferences.getString(PREVIOUS_USAGE_ACCESS_MODE, null) == null) {
            val previous = readUsageAccessMode(context) ?: return false
            if (!preferences.edit().putString(PREVIOUS_USAGE_ACCESS_MODE, previous).commit()) return false
        }
        if (setUsageAccess(context, "allow")) return true
        preferences.edit().remove(PREVIOUS_USAGE_ACCESS_MODE).apply()
        return false
    }

    private fun restorePreviousUsageAccess(context: Context): Boolean {
        val preferences = preferences(context)
        val previous = preferences.getString(PREVIOUS_USAGE_ACCESS_MODE, null) ?: return true
        if (!setUsageAccess(context, previous)) return false
        return preferences.edit().remove(PREVIOUS_USAGE_ACCESS_MODE).commit()
    }

    private fun sessionMode(context: Context): Boolean = !preferences(context)
        .getBoolean(context.getString(R.string.key_audioformat_processing), true)

    private fun preferences(context: Context) = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)

    private fun write(enabled: Boolean, context: Context): Boolean {
        val aaudioFocusEnabled = enabled && AAudioCompatibilityOption.requested(context) &&
            AAudioSessionReuseOption.requested(context)
        val legacyValue = if (aaudioFocusEnabled) "1" else "0"
        val result = Shell.cmd(
            "setprop $LEGACY_PROPERTY $legacyValue",
            "getprop $LEGACY_PROPERTY",
        ).exec()
        return result.isSuccess && result.out.lastOrNull()?.trim() == legacyValue
    }

    private fun readUsageAccessMode(context: Context): String? {
        val userId = Process.myUid() / 100_000
        val result = Shell.cmd(
            "cmd appops get --user $userId ${context.packageName} GET_USAGE_STATS",
        ).exec()
        if (!result.isSuccess) return null
        val output = result.out.joinToString("\n")
        val mode = Regex("(?i)GET_USAGE_STATS:\\s*(allow|ignore|deny|default|foreground)")
            .find(output)?.groupValues?.get(1)?.lowercase()
        return mode ?: if (output.contains("No operations", ignoreCase = true)) "default" else null
    }

    private fun setUsageAccess(context: Context, mode: String): Boolean {
        if (mode !in USAGE_ACCESS_MODES) return false
        val userId = Process.myUid() / 100_000
        val result = Shell.cmd(
            "cmd appops set --user $userId ${context.packageName} GET_USAGE_STATS $mode",
        ).exec()
        return result.isSuccess && if (mode == "allow") context.hasPackageUsagePermission()
        else !context.hasPackageUsagePermission()
    }

    private val USAGE_ACCESS_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
}
