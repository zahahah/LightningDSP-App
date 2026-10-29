package lightning.dsp.volumemanager.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import lightning.dsp.volumemanager.backend.VolumeProfiles

internal data class VolumeManagerBackupSettings(
    val manager: Map<String, Any> = emptyMap(),
    val panel: Map<String, Any> = emptyMap(),
)

/** A deliberately allowlisted format: runtime ownership/cleanup receipts are never portable. */
internal object VolumeManagerBackupCodec {
    const val MAX_BYTES = 1_048_576

    private enum class Kind { BOOLEAN, VOLUME, PAN, ACCESS, THEME, STRING, PACKAGES, PROFILE_STRING }

    private val packageId = Regex("android|[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val managerBooleans = setOf("enabled", "per_app_enabled", "startup_on_boot")
    private val panelBooleans = setOf(
        "show_dsp_card", "hide_system_apps", "group_system_streams", "hide_inactive_dsp_card",
        "media_icon_hold_expand", "dsp_bar_monochrome",
        "stream_0", "stream_2", "stream_3", "stream_4", "stream_5",
    )
    private val panelStrings = setOf(
        "order_streams", "order_apps", "order_active", "order_inactive", "dsp_effect_display_order",
    )

    private fun dynamicPackage(key: String, prefix: String): Boolean =
        key.startsWith(prefix) && packageId.matches(key.removePrefix(prefix))

    private fun managerKind(key: String): Kind? = when {
        VolumeProfiles.isSetting(key) -> when {
            key.startsWith("profile_volume:") -> Kind.VOLUME
            key.startsWith("profile_pan:") -> Kind.PAN
            key.startsWith("profile_muted:") -> Kind.BOOLEAN
            else -> Kind.PROFILE_STRING
        }
        key in managerBooleans -> Kind.BOOLEAN
        key == "access_mode" -> Kind.ACCESS
        dynamicPackage(key, "volume:") -> Kind.VOLUME
        dynamicPackage(key, "pan:") -> Kind.PAN
        listOf("muted:", "ignore_buttons:", "had_player:").any { dynamicPackage(key, it) } -> Kind.BOOLEAN
        else -> null
    }

    private fun panelKind(key: String): Kind? = when {
        key in panelBooleans || dynamicPackage(key, "hidden_") -> Kind.BOOLEAN
        key in panelStrings -> Kind.STRING
        key == "floating_panel_theme" -> Kind.THEME
        key == "pinned_apps" -> Kind.PACKAGES
        else -> null
    }

    fun isManagerSetting(key: String): Boolean = managerKind(key) != null
    fun isPanelSetting(key: String): Boolean = panelKind(key) != null

    fun filterManager(values: Map<String, *>): Map<String, Any> = filter(values, ::managerKind)
    fun filterPanel(values: Map<String, *>): Map<String, Any> = filter(values, ::panelKind)

    private fun filter(values: Map<String, *>, kindFor: (String) -> Kind?): Map<String, Any> =
        buildMap {
            values.forEach { (key, value) ->
                val kind = kindFor(key) ?: return@forEach
                put(key, validate(key, value, kind))
            }
        }

    private fun validate(key: String, value: Any?, kind: Kind): Any {
        val valid = when (kind) {
            Kind.BOOLEAN -> value is Boolean
            Kind.VOLUME -> value is Float && value.isFinite() && value in 0f..1f
            Kind.PAN -> value is Float && value.isFinite() && value in -1f..1f
            Kind.ACCESS -> value is String && value in setOf("ROOT", "SHIZUKU")
            Kind.THEME -> value is String && value in setOf("system", "light", "dark")
            Kind.STRING -> value is String
            Kind.PROFILE_STRING -> VolumeProfiles.validate(key, value)
            Kind.PACKAGES -> value is Set<*> && value.all { it is String && packageId.matches(it) }
        }
        require(valid && (!VolumeProfiles.isSetting(key) || VolumeProfiles.validate(key, value))) { "Invalid Volume Manager backup setting: $key" }
        // Do not retain the mutable StringSet owned by SharedPreferences.
        return if (kind == Kind.PACKAGES) (value as Set<*>).map { it as String }.toSet() else value!!
    }

    fun encode(settings: VolumeManagerBackupSettings): ByteArray {
        val properties = Properties().apply { setProperty("version", "1") }
        fun add(prefix: String, values: Map<String, Any>) {
            values.forEach { (key, value) ->
                val encoded = if (value is Set<*>) value.joinToString("|") else value.toString()
                properties.setProperty(prefix + key, encoded)
            }
        }
        add("manager.", filterManager(settings.manager))
        add("panel.", filterPanel(settings.panel))
        return ByteArrayOutputStream().use { output ->
            properties.store(output, "LightningDSP Volume Manager settings")
            output.toByteArray().also { require(it.size <= MAX_BYTES) { "Volume Manager backup is too large" } }
        }
    }

    fun decode(bytes: ByteArray): VolumeManagerBackupSettings {
        require(bytes.size <= MAX_BYTES) { "Volume Manager backup is too large" }
        val properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
        require(properties.getProperty("version") == "1") { "Unsupported Volume Manager backup version" }
        fun read(prefix: String, kindFor: (String) -> Kind?): Map<String, Any> = buildMap {
            properties.stringPropertyNames().forEach { property ->
                if (!property.startsWith(prefix)) return@forEach
                val key = property.removePrefix(prefix)
                val kind = kindFor(key) ?: return@forEach
                val text = properties.getProperty(property)
                val value: Any = when (kind) {
                    Kind.BOOLEAN -> when (text) {
                        "true" -> true
                        "false" -> false
                        else -> throw IllegalArgumentException("Invalid Volume Manager backup boolean: $key")
                    }
                    Kind.VOLUME, Kind.PAN -> text.toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid Volume Manager backup number: $key")
                    Kind.PACKAGES -> if (text.isEmpty()) emptySet<String>() else text.split('|').toSet()
                    else -> text
                }
                put(key, validate(key, value, kind))
            }
        }
        return VolumeManagerBackupSettings(read("manager.", ::managerKind), read("panel.", ::panelKind))
    }
}
