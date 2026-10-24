package lightning.dsp.model.preference

enum class AudioEncoding(val value: Int) {
    PcmShort(0),
    PcmFloat(1);

    companion object {
        fun fromInt(value: Int) = values().first { it.value == value }
    }
}