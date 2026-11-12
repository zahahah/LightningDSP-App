package lightning.dsp.interop.appmixer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.os.Process
import android.os.SystemClock
import lightning.dsp.volumemanager.backend.PrivilegedVolumeContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root app_process relay for the manual experiment and supervised App Mixer mode.
 * Uses LOOP_BACK alone (never LOOP_BACK|RENDER), avoiding intentional duplicate rendering.
 * Unknown/denied platform APIs fail closed. No capture-policy changes or recording files.
 */
object AppMixerRoutingExperiment {
    private const val EFFECT_UUID = AppMixerEffectInventory.EFFECT_UUID

    @JvmStatic fun main(args: Array<String>) {
        var exitCode = 0
        try {
            runMain(args)
        } catch (error: Throwable) {
            exitCode = 1
            System.err.println("App Mixer helper failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
            println("APP_MIXER:FAILED ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
        } finally {
            System.out.flush()
            System.err.flush()
        }
        // This is a dedicated app_process; Binder threads must not keep an exited
        // relay alive after its cleanup (or an unsuccessful startup).
        kotlin.system.exitProcess(exitCode)
    }

    private fun runMain(args: Array<String>) {
        if (args.isEmpty()) {
            println("App Mixer routing helper. See poc/app-mixer-mode/ROUTING-EXPERIMENT.md")
            return
        }
        val supervised = args.size == 6 && args[0] == "--supervised" && args[1].startsWith("--exclude-uids=") && args[2].startsWith("--user=") && args[3].startsWith("--owner=") && args[4].startsWith("--owner-pid=") && args[5].startsWith("--owner-start=")
        val config = if (supervised) AppMixerExperimentConfig(AppMixerExperimentConfig.parseExcludedUids(args[1]), 90, excludeUids = true,
            userId = args[2].removePrefix("--user=").toInt().also { require(it in 0..21473) },
            ownerUid = args[3].removePrefix("--owner=").toInt())
        else AppMixerExperimentConfig.parse(args)
        if (supervised) require(config.ownerUid in config.uids && config.ownerUid / 100000 == config.userId && config.ownerUid % 100000 in 10000..19999)
        check(Process.myUid() == 0) { "A separately authorized root app_process is required" }
        val stop = AtomicBoolean(false)
        if (supervised) {
            val owner = AppMixerOwnerIdentity(args[4].removePrefix("--owner-pid=").toInt(),
                config.ownerUid, args[5].removePrefix("--owner-start=").toLong())
            check(AppMixerOwnerIdentity.read(owner.pid) == owner) { "App Mixer owner is no longer valid" }
            AppMixerOwnerWatchdog.start(owner, stop)
        }
        // Do not relax hidden-API enforcement: unavailable APIs abort before routing.
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val context = PrivilegedVolumeContext.obtain()
        try {
            checkNoExistingEffects()
        } catch (error: Throwable) {
            // This preflight runs before AudioPolicy registration. In supervised mode,
            // acknowledge that no route was created so the owner can safely release its
            // exclusive session lease instead of fencing DSP indefinitely.
            if (!supervised) throw error
            println("APP_MIXER:FAILED ${error.message.orEmpty().take(160)}")
            println("APP_MIXER:RELEASED (preflight failed before route registration)")
            System.out.flush()
            return
        }
        val commands = ArrayBlockingQueue<String>(1)
        val updater = AtomicReference<((Set<Int>) -> Boolean)?>(null)
        Thread {
            try {
                val input = System.`in`.bufferedReader()
                if (supervised) {
                    val command = AppMixerControlProtocol.readCommand(input)
                    if (command != "READY" || !commands.offer(command)) return@Thread
                    while (true) {
                        val line = AppMixerControlProtocol.readCommand(input, 256) ?: break
                        if (line == "STOP") break
                        val parts = line.split(' ', limit = 3)
                        if (parts.size != 3 || parts[0] != "UPDATE" || parts[1].toLongOrNull() == null) break
                        val ok = runCatching {
                            val excluded = AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=${parts[2]}")
                            require(config.ownerUid in excluded)
                            updater.get()?.invoke(excluded) == true
                        }.getOrDefault(false)
                        println("APP_MIXER:${if (ok) "UPDATED" else "UPDATE_FAILED"} ${parts[1]}")
                        System.out.flush()
                    }
                } else {
                    while (input.read() >= 0) { /* manual owner pipe */ }
                }
            } finally { stop.set(true); commands.offer("STOP") }
        }.apply { name = "AppMixer-owner-pipe"; isDaemon = true; start() }
        run(context, config, stop, updater, if (supervised) { session ->
            println("APP_MIXER:SESSION $session")
            System.out.flush()
            check(commands.poll(10, TimeUnit.SECONDS) == "READY" && !stop.get()) {
                "Owner did not confirm DSP readiness"
            }
        } else null)
    }

    private fun run(context: Context, config: AppMixerExperimentConfig, stop: AtomicBoolean,
                    updater: AtomicReference<((Set<Int>) -> Boolean)?>,
                    prepareExternalEffect: ((Int) -> Unit)?) {
        val audio = context.getSystemService(AudioManager::class.java)
        val policyType = Class.forName("android.media.audiopolicy.AudioPolicy")
        val mixType = Class.forName("android.media.audiopolicy.AudioMix")
        val ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule")
        // Resolve synchronous rollback before allowing any routing mutation.
        val unregister = AudioManager::class.java.getMethod("unregisterAudioPolicy", policyType)
        val register = AudioManager::class.java.getMethod("registerAudioPolicy", policyType)
        val createSink = policyType.getMethod("createAudioRecordSink", mixType)
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val ruleBuilderType = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val uidRule = ruleType.getField("RULE_MATCH_UID").getInt(null)
        fun buildRule(uids: Set<Int>): Any {
        val ruleBuilder = ruleBuilderType.getConstructor().newInstance()
        // Only media/game PCM is considered. No calls, alarms or system sonification.
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)) {
            ruleBuilderType.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
                .invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(),
                    ruleType.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null))
        }
        if (config.excludeUids) {
            val userRule = ruleType.getField("RULE_MATCH_USERID").getInt(null)
            ruleBuilderType.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(ruleBuilder, userRule, config.userId)
        }
        for (uid in uids.sorted()) {
            val method = if (config.excludeUids) "excludeMixRule" else "addMixRule"
            ruleBuilderType.getMethod(method, Int::class.javaPrimitiveType, Any::class.java)
                .invoke(ruleBuilder, uidRule, uid)
        }
        return ruleBuilderType.getMethod("build").invoke(ruleBuilder)
        }
        val rule = buildRule(config.uids)
        val mixBuilderType = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderType.getConstructor(ruleType).newInstance(rule)
        mixBuilderType.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderType.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        val mix = mixBuilderType.getMethod("build").invoke(mixBuilder)
        val policyBuilderType = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderType.getConstructor(Context::class.java).newInstance(context)
        policyBuilderType.getMethod("addMix", mixType).invoke(policyBuilder, mix)
        val policy = policyBuilderType.getMethod("build").invoke(policyBuilder)
        var registrationAttempted = false
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        var effect: AudioEffect? = null
        try {
            check(!stop.get()) { "Owner pipe closed before routing" }
            // Build the return path first, but do not start playback or enable DSP yet.
            val minimumBytes = AudioTrack.getMinBufferSize(48000,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimumBytes > 0) { "PCM playback configuration unavailable: $minimumBytes" }
            val bytes = maxOf(8192, minimumBytes)
            player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes).build()
            check(player.state == AudioTrack.STATE_INITIALIZED && player.audioSessionId > 0)
            if (prepareExternalEffect == null) effect = createDiagnosticEffect(player.audioSessionId)
            else prepareExternalEffect(player.audioSessionId)
            checkNoExistingEffects(allowOwnDiagnostic = true)
            check(!stop.get()) { "Owner pipe closed before routing" }
            // The supervisor treats all exits before this marker as pre-route failures,
            // where releasing the external effect and exclusive lease is safe.
            println("APP_MIXER:ROUTING")
            System.out.flush()
            registrationAttempted = true
            val result = register.invoke(audio, policy) as Int
            check(result == 0 /* AudioManager.SUCCESS, hidden in SDK stubs */) { "AudioPolicy registration denied: $result" }
            recorder = createSink.invoke(policy, mix) as AudioRecord
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Remote submix recorder unavailable" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            effect?.let { check(it.setEnabled(true) == AudioEffect.SUCCESS && it.enabled && it.hasControl()) }
            player.play()
            if (config.excludeUids) updater.set { excluded ->
                val update = policyType.getMethod("updateMixingRules", List::class.java)
                val updates = listOf(android.util.Pair(mix, buildRule(excluded)))
                (update.invoke(policy, updates) as Int) == 0
            }
            println("APP_MIXER:RUNNING session=${player.audioSessionId} uidCount=${config.uids.size} effect=${if (prepareExternalEffect == null) "diagnostic" else "external"}; coverage NOT verified")
            System.out.flush()
            // Supervised lifetime is controlled by the owner's pipe; manual tests
            // remain time-limited. Rebuilding the mix periodically interrupts all tracks.
            val deadline = if (prepareExternalEffect == null)
                SystemClock.elapsedRealtime() + config.seconds * 1000L else Long.MAX_VALUE
            val source = recorder
            val target = player
            val counts = AppMixerPcmRelay.run(ShortArray(960),
                shouldContinue = { !stop.get() && SystemClock.elapsedRealtime() < deadline },
                read = { source.read(it, 0, it.size, AudioRecord.READ_NON_BLOCKING) },
                write = { block, offset, count -> target.write(block, offset, count, AudioTrack.WRITE_NON_BLOCKING) },
                idle = { Thread.sleep(2) })
            println("APP_MIXER:STOP samplesRead=${counts.readSamples} samplesWritten=${counts.writtenSamples}; counters are NOT coverage proof")
        } finally {
            updater.set(null)
            // Every cleanup is attempted independently; rollback is never skipped after an error.
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) { try { action() } catch (e: Throwable) {
                if (cleanupFailure == null) cleanupFailure = e else cleanupFailure?.addSuppressed(e)
            } }
            cleanup { player?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() } }
            cleanup { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
            cleanup { effect?.let { check(it.setEnabled(false) == AudioEffect.SUCCESS) { "Effect disable failed" } } }
            cleanup { effect?.release() }
            cleanup { recorder?.release() }
            cleanup { player?.release() }
            if (registrationAttempted) cleanup { unregister.invoke(audio, policy) }
            if (cleanupFailure != null) throw IllegalStateException("APP_MIXER:CLEANUP_FAILED; verify policy/output state", cleanupFailure)
            println("APP_MIXER:RELEASED (verify dumps to confirm baseline restoration)")
            System.out.flush()
        }
    }

    private fun createDiagnosticEffect(session: Int): AudioEffect {
        val effect = AudioEffect::class.java.getConstructor(UUID::class.java, UUID::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2"), UUID.fromString(EFFECT_UUID), 0, session)
        try {
            check(effect.setEnabled(false) == AudioEffect.SUCCESS && effect.hasControl())
            val setParameter = AudioEffect::class.java.getMethod("setParameter", ByteArray::class.java, ByteArray::class.java)
            fun parameter(id: Int, bytes: ByteArray) {
                val key = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(id).array()
                check(setParameter.invoke(effect, key, bytes) == AudioEffect.SUCCESS) { "Diagnostic DSP parameter rejected: $id" }
            }
            for (id in intArrayOf(1200,1201,1202,1203,1204,1205,1206,1208,1210,1212,1213)) {
                parameter(id, ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort(0).array())
            }
            parameter(1500, ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
                .putFloat(-0.1f).putFloat(60f).putFloat(-6f).array())
            return effect
        } catch (e: Throwable) { effect.release(); throw e }
    }

    private fun checkNoExistingEffects(allowOwnDiagnostic: Boolean = false) {
        val process = ProcessBuilder("/system/bin/timeout", "3", "/system/bin/dumpsys", "media.audio_flinger")
            .redirectErrorStream(true).start()
        val bytes = process.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) {
                    process.destroyForcibly()
                    error("Effect inventory exceeded its safety bound")
                }
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        check(process.waitFor() == 0 && bytes.size <= 2 * 1024 * 1024) { "Complete effect inventory unavailable" }
        val dump = bytes.toString(Charsets.UTF_8)
        val count = AppMixerEffectInventory.count(dump)
            ?: error("AudioFlinger effect inventory layout unknown")
        check(count == if (allowOwnDiagnostic) 1 else 0) { "Stop existing LightningDSP processing before the manual mixer experiment" }
    }
}
