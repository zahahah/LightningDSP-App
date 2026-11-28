package lightning.dsp.interop.appmixer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.lang.reflect.Array

/**
 * API 37 adapter for updating the relay's registered policy mix through the
 * platform AIDL service. Keeping this in the root app_process avoids loading
 * private framework libraries into the APK's isolated native linker namespace.
 */
internal object AppMixerPolicyMixAidl {
    private const val TAG = "LightningDSP_FusionMode"
    private const val POLICY_SERVICE = "media.audio_policy"
    private const val REMOTE_SUBMIX_OUT = 0x8000
    private const val MAX_CRITERIA = AppMixerExperimentConfig.MAX_DIRECT_POLICY_EXCLUSIONS + 16

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT == 37 && Process.is64Bit() &&
        Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"

    /**
     * Replaces the complete criterion list in one AudioPolicyService AIDL call.
     * Only accepts the relay's own, still-registered LOOP_BACK remote-submix mix.
     * A successful Binder return means the service accepted the update; it is
     * not proof that an individual app produced audio or reached the relay.
     */
    fun replace(
        mix: Any,
        address: String,
        ownerUid: Int,
        exclusions: IntArray,
        includeSystemSounds: Boolean,
        users: IntArray,
    ): Boolean = try {
        check(Build.VERSION.SDK_INT == 37 && Process.myUid() == 0)
        check(address.isNotBlank() && address.length <= 63)
        check(ownerUid in 10_000..2_147_435_647 && ownerUid % 100_000 in 10_000..19_999)
        check(exclusions.isNotEmpty() && exclusions.size <= AppMixerExperimentConfig.MAX_DIRECT_POLICY_EXCLUSIONS)
        check(exclusions.toList() == exclusions.sorted() && exclusions.distinct().size == exclusions.size)
        check(users.isNotEmpty() && users.size <= 8 && users.toList() == users.sorted() && users.distinct().size == users.size)
        check(exclusions.first() == 0 && ownerUid in exclusions)
        check(ownerUid / 100_000 in users && users.all { it in 0..21_473 })
        check(exclusions.all { uid -> uid == 0 ||
            (uid % 100_000 in 10_000..19_999 && uid / 100_000 in users) })

        val mixClass = mix.javaClass
        check(invokeInt(mix, "getRouteFlags") == mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        check(invokeInt(mix, "getMixType") == mixClass.getField("MIX_TYPE_PLAYERS").getInt(null))
        check(invokeString(mix, "getRegistration") == address)
        check(readInt(mix, "mDeviceSystemType") == REMOTE_SUBMIX_OUT)
        check(readString(mix, "mDeviceAddress") == address)
        check(readField(mix, "mToken") is IBinder)
        check(readInt(mix, "mVirtualDeviceId") == 0)
        check(!readBoolean(mix, "mIsPersistent"))
        check(readFormat(mix))

        val rule = readField(mix, "mRule")
        val initialCriteria = readField(rule, "mCriteria") as? List<*>
            ?: error("AudioMix criteria are unavailable")
        check(initialCriteria.size == 1)
        val sentinel = checkNotNull(initialCriteria.single())
        check(readInt(sentinel, "mRule") ==
            rule.javaClass.getField("RULE_MATCH_UID").getInt(null))
        check(readInt(sentinel, "mIntProp") == Int.MAX_VALUE)

        val aidlMix = makeAidlMix(mix, address, rule)
        val criteria = buildCriteria(exclusions, includeSystemSounds, users)
        check(Array.getLength(criteria) in 1..MAX_CRITERIA)
        val updateClass = Class.forName("android.media.AudioMixUpdate")
        val update = updateClass.getDeclaredConstructor().newInstance()
        updateClass.getField("audioMix").set(update, aidlMix)
        updateClass.getField("newCriteria").set(update, criteria)
        val updates = Array.newInstance(updateClass, 1).also { Array.set(it, 0, update) }

        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("checkService", String::class.java)
            .invoke(null, POLICY_SERVICE) as? IBinder ?: error("AudioPolicyService is unavailable")
        val serviceClass = Class.forName("android.media.IAudioPolicyService")
        val stubClass = Class.forName("android.media.IAudioPolicyService\$Stub")
        val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("AudioPolicyService interface is unavailable")
        val updatesType = Array.newInstance(updateClass, 0).javaClass
        serviceClass.getMethod("updatePolicyMixes", updatesType).invoke(service, updates)
        true
    } catch (error: Throwable) {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        val detail = cause.message?.replace(Regex("[\\r\\n]"), " ")?.take(160)?.let { ": $it" }.orEmpty()
        android.util.Log.e(TAG, "Direct AudioPolicyService rule update failed: ${cause.javaClass.simpleName}$detail")
        false
    }

    private fun buildCriteria(exclusions: IntArray, includeSystemSounds: Boolean, users: IntArray): Any {
        val valueClass = Class.forName("android.media.AudioMixMatchCriterionValue")
        val criterionClass = Class.forName("android.media.AudioMixMatchCriterion")
        val rules = buildList {
            add(Triple("usage", audioUsage("UNKNOWN"), false))
            add(Triple("usage", audioUsage("MEDIA"), false))
            add(Triple("usage", audioUsage("GAME"), false))
            if (includeSystemSounds) {
                add(Triple("usage", audioUsage("NOTIFICATION"), false))
                add(Triple("usage", audioUsage("NOTIFICATION_EVENT"), false))
                add(Triple("usage", audioUsage("ASSISTANCE_SONIFICATION"), false))
            }
            users.forEach { add(Triple("userId", it, false)) }
            exclusions.forEach { add(Triple("uid", it, true)) }
        }
        check(rules.size <= MAX_CRITERIA)
        val array = Array.newInstance(criterionClass, rules.size)
        rules.forEachIndexed { index, (kind, value, invert) ->
            Array.set(array, index, newCriterion(valueClass, criterionClass, kind, value, invert))
        }
        return array
    }

    private fun newCriterion(
        valueClass: Class<*>,
        criterionClass: Class<*>,
        kind: String,
        value: Int,
        invert: Boolean,
    ): Any {
        val criterionValue = valueClass.getMethod(kind, Int::class.javaPrimitiveType).invoke(null, value)
        val criterion = criterionClass.getDeclaredConstructor().newInstance()
        criterionClass.getField("value").set(criterion, criterionValue)
        criterionClass.getField("invert").setBoolean(criterion, invert)
        return criterion
    }

    private fun audioUsage(name: String): Int =
        Class.forName("android.media.audio.common.AudioUsage").getField(name).getInt(null)

    private fun makeAidlMix(mix: Any, address: String, rule: Any): Any {
        val aidlMixClass = Class.forName("android.media.AudioMix")
        val aidlMix = aidlMixClass.getDeclaredConstructor().newInstance()
        aidlMixClass.getField("mixType").setInt(aidlMix, invokeInt(mix, "getMixType"))
        aidlMixClass.getField("routeFlags").setInt(aidlMix, invokeInt(mix, "getRouteFlags"))
        aidlMixClass.getField("cbFlags").setInt(aidlMix, readInt(mix, "mCallbackFlags"))
        aidlMixClass.getField("mToken").set(aidlMix, readField(mix, "mToken"))
        aidlMixClass.getField("mVirtualDeviceId").setInt(aidlMix, readInt(mix, "mVirtualDeviceId"))
        aidlMixClass.getField("mIsPersistent").setBoolean(aidlMix, readBoolean(mix, "mIsPersistent"))
        aidlMixClass.getField("allowPrivilegedMediaPlaybackCapture").setBoolean(
            aidlMix, readBoolean(rule, "mAllowPrivilegedPlaybackCapture"))
        aidlMixClass.getField("voiceCommunicationCaptureAllowed").setBoolean(
            aidlMix, readBoolean(rule, "mVoiceCommunicationCaptureAllowed"))
        aidlMixClass.getField("device").set(aidlMix, makeAidlDevice(address))
        aidlMixClass.getField("format").set(aidlMix, makeAidlFormat())
        val valueClass = Class.forName("android.media.AudioMixMatchCriterionValue")
        val criterionClass = Class.forName("android.media.AudioMixMatchCriterion")
        val initialCriteria = Array.newInstance(criterionClass, 1).also {
            Array.set(it, 0, newCriterion(valueClass, criterionClass, "uid", Int.MAX_VALUE, false))
        }
        aidlMixClass.getField("criteria").set(aidlMix, initialCriteria)
        return aidlMix
    }

    private fun makeAidlDevice(address: String): Any {
        val descriptionClass = Class.forName("android.media.audio.common.AudioDeviceDescription")
        val description = descriptionClass.getDeclaredConstructor().newInstance()
        val deviceTypeClass = Class.forName("android.media.audio.common.AudioDeviceType")
        descriptionClass.getField("type").setInt(description, deviceTypeClass.getField("OUT_SUBMIX").getInt(null))
        descriptionClass.getField("connection").set(description,
            descriptionClass.getField("CONNECTION_VIRTUAL").get(null))
        val addressClass = Class.forName("android.media.audio.common.AudioDeviceAddress")
        val deviceAddress = addressClass.getMethod("id", String::class.java).invoke(null, address)
        val deviceClass = Class.forName("android.media.audio.common.AudioDevice")
        val device = deviceClass.getDeclaredConstructor().newInstance()
        deviceClass.getField("type").set(device, description)
        deviceClass.getField("address").set(device, deviceAddress)
        return device
    }

    private fun makeAidlFormat(): Any {
        val formatDescriptionClass = Class.forName("android.media.audio.common.AudioFormatDescription")
        val formatDescription = formatDescriptionClass.getDeclaredConstructor().newInstance()
        formatDescriptionClass.getField("type").setByte(formatDescription,
            Class.forName("android.media.audio.common.AudioFormatType").getField("PCM").getByte(null))
        formatDescriptionClass.getField("pcm").setByte(formatDescription,
            Class.forName("android.media.audio.common.PcmType").getField("INT_16_BIT").getByte(null))
        formatDescriptionClass.getField("encoding").set(formatDescription, "")

        val channelLayoutClass = Class.forName("android.media.audio.common.AudioChannelLayout")
        val stereoLayout = channelLayoutClass.getMethod("layoutMask", Int::class.javaPrimitiveType)
            // AIDL uses the positional layout mask (3); AudioFormat.CHANNEL_OUT_STEREO is 12.
            .invoke(null, channelLayoutClass.getField("LAYOUT_STEREO").getInt(null))
        val configBaseClass = Class.forName("android.media.audio.common.AudioConfigBase")
        val configBase = configBaseClass.getDeclaredConstructor().newInstance()
        configBaseClass.getField("sampleRate").setInt(configBase, 48_000)
        configBaseClass.getField("format").set(configBase, formatDescription)
        configBaseClass.getField("channelMask").set(configBase, stereoLayout)

        val offloadBase = configBaseClass.getDeclaredConstructor().newInstance()
        val defaultFormat = formatDescriptionClass.getDeclaredConstructor().newInstance().also {
            formatDescriptionClass.getField("encoding").set(it, "")
        }
        configBaseClass.getField("format").set(offloadBase, defaultFormat)
        configBaseClass.getField("channelMask").set(offloadBase,
            channelLayoutClass.getMethod("none", Int::class.javaPrimitiveType).invoke(null, 0))
        val offloadClass = Class.forName("android.media.audio.common.AudioOffloadInfo")
        val offload = offloadClass.getDeclaredConstructor().newInstance()
        offloadClass.getField("base").set(offload, offloadBase)
        // Generated AIDL constructors leave these enum fields at INVALID sentinels;
        // the legacy AudioConfig converter requires concrete values even for PCM mixes.
        offloadClass.getField("streamType").setInt(offload,
            Class.forName("android.media.audio.common.AudioStreamType").getField("VOICE_CALL").getInt(null))
        offloadClass.getField("usage").setInt(offload,
            Class.forName("android.media.audio.common.AudioUsage").getField("UNKNOWN").getInt(null))
        offloadClass.getField("encapsulationMode").setByte(offload,
            Class.forName("android.media.audio.common.AudioEncapsulationMode").getField("NONE").getByte(null))

        val configClass = Class.forName("android.media.audio.common.AudioConfig")
        val config = configClass.getDeclaredConstructor().newInstance()
        configClass.getField("base").set(config, configBase)
        configClass.getField("offloadInfo").set(config, offload)
        return config
    }

    private fun readFormat(mix: Any): Boolean {
        val format = invoke(mix, "getFormat")
        return invokeInt(format, "getEncoding") == AudioFormat.ENCODING_PCM_16BIT &&
            invokeInt(format, "getSampleRate") == 48_000 &&
            invokeInt(format, "getChannelMask") == AudioFormat.CHANNEL_OUT_STEREO
    }

    private fun invoke(target: Any, method: String): Any =
        target.javaClass.getMethod(method).invoke(target) ?: error("$method returned null")

    private fun invokeInt(target: Any, method: String): Int = (invoke(target, method) as Number).toInt()

    private fun invokeString(target: Any, method: String): String = invoke(target, method) as String

    private fun readField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            ?: error("$name is null")

    private fun readInt(target: Any, name: String): Int = (readField(target, name) as Number).toInt()

    private fun readString(target: Any, name: String): String = readField(target, name) as String

    private fun readBoolean(target: Any, name: String): Boolean = readField(target, name) as Boolean
}
