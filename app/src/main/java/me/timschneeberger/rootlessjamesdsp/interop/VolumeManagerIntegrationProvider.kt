package me.timschneeberger.rootlessjamesdsp.interop

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/** Narrow external control API for the VolumeManager app; all calls are caller-package checked. */
class VolumeManagerIntegrationProvider : ContentProvider() {
    companion object {
        const val METHOD_GET_STATE = "get_state"
        const val METHOD_SET_POST_GAIN = "set_post_gain"
        const val METHOD_SET_EFFECT = "set_effect"
        const val METHOD_SET_PARAMETER = "set_parameter"
        const val METHOD_SET_POWERED_ON = "set_powered_on"
        const val EXTRA_POST_GAIN = "post_gain"
        const val EXTRA_EFFECT_ID = "effect_id"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PARAMETER_ID = "parameter_id"
        const val EXTRA_VALUE = "value"

        private const val AUTHORIZED_PACKAGE = "moe.chensi.volume"

        private data class EffectKey(val id: String, val namespace: String, val key: Int)
        private data class ParameterKey(val id: String, val namespace: String, val key: Int, val default: Float, val min: Float, val max: Float)

        private val parameters = listOf(
            ParameterKey("bass", Constants.PREF_BASS, R.string.key_bass_max_gain, 5f, 3f, 15f),
            ParameterKey("stereo_wide", Constants.PREF_STEREOWIDE, R.string.key_stereowide_mode, 60f, 30f, 75f),
            ParameterKey("tube", Constants.PREF_TUBE, R.string.key_tube_drive, 2f, -3f, 12f)
        )

        private val effects = listOf(
            EffectKey("compander", Constants.PREF_COMPANDER, R.string.key_compander_enable),
            EffectKey("bass", Constants.PREF_BASS, R.string.key_bass_enable),
            EffectKey("equalizer", Constants.PREF_EQ, R.string.key_eq_enable),
            EffectKey("graphic_eq", Constants.PREF_GEQ, R.string.key_geq_enable),
            EffectKey("reverb", Constants.PREF_REVERB, R.string.key_reverb_enable),
            EffectKey("stereo_wide", Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable),
            EffectKey("crossfeed", Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable),
            EffectKey("tube", Constants.PREF_TUBE, R.string.key_tube_enable),
            EffectKey("ddc", Constants.PREF_DDC, R.string.key_ddc_enable),
            EffectKey("liveprog", Constants.PREF_LIVEPROG, R.string.key_liveprog_enable),
            EffectKey("convolver", Constants.PREF_CONVOLVER, R.string.key_convolver_enable)
        )
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        dispatch(
            context?.applicationContext ?: error("Provider is detached"),
            method,
            extras,
            Binder.getCallingUid()
        )

    private fun dispatch(context: Context, method: String, extras: Bundle?, callerUid: Int): Bundle {
        val callerPackages = context.packageManager.getPackagesForUid(callerUid).orEmpty()
        if (AUTHORIZED_PACKAGE !in callerPackages) {
            throw SecurityException("VolumeManager is the only authorized integration client")
        }
        val appContext = context.applicationContext
        val integrationEnabled = appContext.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            .getBoolean(appContext.getString(R.string.key_volume_manager_integration_api), false)
        if (!integrationEnabled) {
            throw IllegalStateException("VolumeManager integration is disabled in JamesDSP settings")
        }
        return when (method) {
            METHOD_GET_STATE -> getState(appContext)
            METHOD_SET_POST_GAIN -> setPostGain(appContext, extras)
            METHOD_SET_EFFECT -> setEffect(appContext, extras)
            METHOD_SET_PARAMETER -> setParameter(appContext, extras)
            METHOD_SET_POWERED_ON -> setPoweredOn(appContext, extras)
            else -> throw IllegalArgumentException("Unsupported integration method")
        }
    }

    private fun getState(context: Context): Bundle {
        val outputPrefs = PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT)
        val state = Bundle().apply {
            putBoolean("active", context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
                .getBoolean(context.getString(R.string.key_powered_on), true))
            putFloat(
                EXTRA_POST_GAIN,
                outputPrefs.getFloat(context.getString(R.string.key_output_postgain), 0f)
                    .coerceIn(-15f, 15f)
            )
        }
        effects.forEach { effect ->
            val preferences = PreferenceCache.getPreferences(context, effect.namespace)
            state.putBoolean(
                "effect_${effect.id}",
                preferences.getBoolean(context.getString(effect.key), false)
            )
        }
        parameters.forEach { parameter ->
            state.putFloat("parameter_${parameter.id}",
                PreferenceCache.getPreferences(context, parameter.namespace)
                    .getFloat(context.getString(parameter.key), parameter.default)
                    .coerceIn(parameter.min, parameter.max))
        }
        return state
    }

    private fun setPostGain(context: Context, extras: Bundle?): Bundle {
        val value = extras?.getFloat(EXTRA_POST_GAIN, Float.NaN) ?: Float.NaN
        if (!value.isFinite() || value !in -15f..15f) {
            throw IllegalArgumentException("Post gain must be between -15 and +15 dB")
        }
        val saved = PreferenceCache.getPreferences(context, Constants.PREF_OUTPUT).edit()
            .putFloat(context.getString(R.string.key_output_postgain), value)
            .commit()
        check(saved) { "Could not save post gain" }
        notifyEngine(context)
        return Bundle().apply { putBoolean("success", true) }
    }

    private fun setEffect(context: Context, extras: Bundle?): Bundle {
        val id = extras?.getString(EXTRA_EFFECT_ID)
            ?: throw IllegalArgumentException("Missing effect id")
        val enabled = extras.getBoolean(EXTRA_ENABLED)
        val effect = effects.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported effect")
        val saved = PreferenceCache.getPreferences(context, effect.namespace).edit()
            .putBoolean(context.getString(effect.key), enabled)
            .commit()
        check(saved) { "Could not save effect state" }
        notifyEngine(context)
        return Bundle().apply { putBoolean("success", true) }
    }

    private fun setParameter(context: Context, extras: Bundle?): Bundle {
        val id = extras?.getString(EXTRA_PARAMETER_ID)
            ?: throw IllegalArgumentException("Missing parameter id")
        val parameter = parameters.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported parameter")
        val value = extras.getFloat(EXTRA_VALUE, Float.NaN)
        if (!value.isFinite() || value !in parameter.min..parameter.max) {
            throw IllegalArgumentException("Parameter out of range")
        }
        val saved = PreferenceCache.getPreferences(context, parameter.namespace).edit()
            .putFloat(context.getString(parameter.key), value)
            .commit()
        check(saved) { "Could not save parameter" }
        notifyEngine(context)
        return Bundle().apply { putBoolean("success", true) }
    }

    private fun setPoweredOn(context: Context, extras: Bundle?): Bundle {
        val enabled = extras?.getBoolean(EXTRA_ENABLED)
            ?: throw IllegalArgumentException("Missing enabled state")
        val saved = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
            .putBoolean(context.getString(R.string.key_powered_on), enabled)
            .commit()
        check(saved) { "Could not save JamesDSP power state" }
        notifyEngine(context)
        return Bundle().apply { putBoolean("success", true) }
    }

    private fun notifyEngine(context: Context) {
        context.sendLocalBroadcast(android.content.Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
