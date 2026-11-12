package lightning.dsp.interop.appmixer

/** Parses only dumps with a recognizable AudioFlinger effect-chain section. */
internal object AppMixerEffectInventory {
    const val EFFECT_UUID = "f27317f4-c984-4de6-9a90-545759495bf2"

    /** Returns null when the dump is incomplete or its effect layout is unknown. */
    fun count(dump: String): Int? {
        val hasEffectInventory = dump.contains("Effect Chains") ||
            dump.contains("AudioFlinger", ignoreCase = true)
        if (!hasEffectInventory) return null
        return Regex(EFFECT_UUID, RegexOption.IGNORE_CASE).findAll(dump).count()
    }
}
