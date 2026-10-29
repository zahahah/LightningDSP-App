package lightning.dsp.volumemanager.backend

import java.util.UUID

data class VolumeProfile(val id: String, val name: String)

/** Flat, portable profile keys; runtime ownership and presentation order stay outside profiles. */
internal object VolumeProfiles {
    const val SELECTED = "selected_profile"
    const val DEFAULT_ID = "default"
    private val id = Regex("default|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val pkg = Regex("android|[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    fun isRule(key: String): Boolean = listOf("volume:", "muted:", "pan:").any {
        key.startsWith(it) && pkg.matches(key.removePrefix(it))
    }
    fun profileKey(profile: String, rule: String): String {
        require(id.matches(profile) && isRule(rule))
        return "profile_$rule".replaceFirst(":", ":$profile:")
    }
    fun isSetting(key: String): Boolean = key == SELECTED ||
        (key.startsWith("profile_name:") && id.matches(key.removePrefix("profile_name:"))) ||
        parseRule(key) != null
    private fun parseRule(key: String): Pair<String, String>? {
        if (!key.startsWith("profile_")) return null
        val parts = key.removePrefix("profile_").split(':', limit = 3)
        if (parts.size != 3 || !id.matches(parts[1])) return null
        val rule = "${parts[0]}:${parts[2]}"
        return if (isRule(rule)) parts[1] to rule else null
    }
    fun validate(key: String, value: Any?): Boolean = when {
        key == SELECTED -> value is String && id.matches(value)
        key.startsWith("profile_name:") -> isSetting(key) && value is String &&
            value.isNotBlank() && value == value.trim() && value.length <= 48 && value.none { it.isISOControl() }
        else -> parseRule(key)?.second?.let { rule -> when {
            rule.startsWith("muted:") -> value is Boolean
            rule.startsWith("volume:") -> value is Float && value.isFinite() && value in 0f..1f
            else -> value is Float && value.isFinite() && value in -1f..1f
        } } ?: false
    }
    fun list(values: Map<String, *>): List<VolumeProfile> = values.entries.mapNotNull { (key, value) ->
        if (key.startsWith("profile_name:") && validate(key, value))
            VolumeProfile(key.removePrefix("profile_name:"), value as String) else null
    }.sortedWith(compareBy<VolumeProfile> { it.id != DEFAULT_ID }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    fun initialize(values: Map<String, *>): Map<String, Any> = buildMap {
        values.filterKeys { isSetting(it) || isRule(it) }.forEach { (k, v) -> if (v != null) put(k, v) }
        if (list(this).isEmpty()) {
            put("profile_name:$DEFAULT_ID", "Profile 1")
            values.filterKeys(::isRule).forEach { (k, v) -> if (v != null) put(profileKey(DEFAULT_ID, k), v) }
        }
        if (list(this).none { it.id == get(SELECTED) }) put(SELECTED, list(this).first().id)
    }
    fun create(values: Map<String, *>, name: String): Pair<String, Map<String, Any>> {
        val current = initialize(values).toMutableMap()
        val normalized = name.trim()
        require(validate("profile_name:$DEFAULT_ID", normalized)) { "Use a profile name with 1–48 characters" }
        require(list(current).none { it.name.equals(normalized, ignoreCase = true) }) { "A profile with this name already exists" }
        val newId = UUID.randomUUID().toString()
        current["profile_name:$newId"] = normalized
        return newId to select(current, newId)
    }
    fun rename(values: Map<String, *>, profile: String, name: String): Map<String, Any> {
        val current = initialize(values).toMutableMap()
        require(list(current).any { it.id == profile }) { "Unknown volume profile" }
        val normalized = name.trim()
        require(validate("profile_name:$profile", normalized)) { "Use a profile name with 1–48 characters" }
        require(list(current).none { it.id != profile && it.name.equals(normalized, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        current["profile_name:$profile"] = normalized
        return current
    }
    fun select(values: Map<String, *>, profile: String): Map<String, Any> {
        val current = initialize(values).toMutableMap()
        require(list(current).any { it.id == profile }) { "Unknown volume profile" }
        val old = current[SELECTED] as String
        current.filterKeys(::isRule).toMap().forEach { (k, v) -> current[profileKey(old, k)] = v }
        current.keys.filter(::isRule).toList().forEach(current::remove)
        current.toMap().forEach { (k, v) -> parseRule(k)?.takeIf { it.first == profile }?.let { current[it.second] = v } }
        current[SELECTED] = profile
        return current
    }
}
