package lightning.dsp.preference

import android.content.Context
import android.util.AttributeSet
import lightning.dsp.BuildConfig
import lightning.dsp.R
import lightning.dsp.interop.PostGainRangePolicy

/** Sets bounds before reading the saved gain, so opening the UI preserves extended values. */
class PostGainPreference(context: Context, attrs: AttributeSet?) : MaterialSeekbarPreference(context, attrs) {
    private fun extended(): Boolean = !BuildConfig.ROOTLESS &&
        (sharedPreferences?.getBoolean(context.getString(R.string.key_output_extra_postgain),
            PostGainRangePolicy.DEFAULT_EXTENDED) ?: PostGainRangePolicy.DEFAULT_EXTENDED)

    private fun updateBounds() {
        val limit = PostGainRangePolicy.limit(extended())
        setMin(-limit)
        setMax(limit)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        updateBounds()
        // Display a safe value without rewriting saved preferences merely by opening the screen.
        mSeekBarValue = PostGainRangePolicy.clamp(getPersistedFloat(defaultValue as? Float ?: 0f), extended())
    }

    override fun setValue(seekBarValue: Float) {
        super.setValue(PostGainRangePolicy.clamp(seekBarValue, extended()))
    }

    fun prepareRangeChange(enabled: Boolean) {
        val preferences = sharedPreferences ?: return
        val stored = preferences.getFloat(key, 0f)
        val target = PostGainRangePolicy.onToggle(stored, extended(), enabled && !BuildConfig.ROOTLESS)
        // Compare with storage, not the displayed value: an external writer may have changed it.
        if (stored != target) preferences.edit().putFloat(key, target).apply()
    }

    fun refreshRange() {
        updateBounds()
        mSeekBarValue = PostGainRangePolicy.clamp(sharedPreferences?.getFloat(key, 0f) ?: 0f, extended())
        notifyChanged()
    }
}
