package lightning.dsp.volumemanager.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

internal object AppVolumeOrderPreferences {
    const val ORDER_KEY = "order_apps"
    const val PINS_KEY = "pinned_apps"

    fun read(preferences: SharedPreferences): AppVolumeOrder = AppVolumeOrder.fromStored(
        preferences.getString(ORDER_KEY, null),
        preferences.getStringSet(PINS_KEY, emptySet()).orEmpty().toSet(),
        preferences.getString("order_inactive", null),
        preferences.getString("order_active", null),
    )

    fun save(preferences: SharedPreferences, order: AppVolumeOrder) {
        // Persist the migration and pin changes together; leave old keys as fallback history.
        preferences.edit()
            .putString(ORDER_KEY, order.apps.joinToString("|"))
            .putStringSet(PINS_KEY, order.pinned.toSet())
            .apply()
    }
}

/** Both panels consume a real state snapshot, not an unused revision outside a lazy lambda. */
@Composable
internal fun rememberAppVolumeOrder(preferences: SharedPreferences): State<AppVolumeOrder> {
    val state = remember(preferences) { mutableStateOf(AppVolumeOrderPreferences.read(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AppVolumeOrderPreferences.ORDER_KEY ||
                key == AppVolumeOrderPreferences.PINS_KEY || key == "order_active" || key == "order_inactive") {
                state.value = AppVolumeOrderPreferences.read(preferences)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state.value = AppVolumeOrderPreferences.read(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
