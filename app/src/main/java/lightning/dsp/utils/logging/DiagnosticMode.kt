package lightning.dsp.utils.logging

import android.content.Context
import android.content.SharedPreferences
import lightning.dsp.utils.Constants
import lightning.dsp.utils.isRoot
import timber.log.Timber

/** App-local preference; scoped hooks and the root relay read the root-owned property. */
object DiagnosticMode {
    const val KEY = "diagnostic_mode"
    const val PROPERTY = "debug.ldsp.diagnostics"
    @Volatile private var localEnabled: Boolean? = null
    @Volatile private var cachedProperty = false
    @Volatile private var propertyCheckedAt = 0L
    private val propertyGetter by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/os/SystemProperties;")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY) localEnabled = isRoot() && prefs.getBoolean(KEY, false)
    }

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        localEnabled = isRoot() && prefs.getBoolean(KEY, false)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    @JvmStatic fun isEnabled(): Boolean {
        localEnabled?.let { return it }
        val now = System.nanoTime()
        if (now - propertyCheckedAt >= 500_000_000L) synchronized(this) {
            if (now - propertyCheckedAt >= 500_000_000L) {
                cachedProperty = runCatching {
                    propertyGetter?.invoke(null, PROPERTY, "0") == "1"
                }.getOrDefault(false)
                propertyCheckedAt = now
            }
        }
        return cachedProperty
    }
}

/** Filter before formatting/writing; toggles take effect without replacing file writers. */
internal class DiagnosticTree(
    private val delegate: Timber.Tree,
    private val detailed: () -> Boolean = DiagnosticMode::isEnabled,
) : Timber.DebugTree() {
    override fun isLoggable(tag: String?, priority: Int) =
        DiagnosticLogPolicy.shouldLog(detailed(), priority)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        delegate.log(priority, t, if (tag == null) message else "[$tag] $message")
    }
}
