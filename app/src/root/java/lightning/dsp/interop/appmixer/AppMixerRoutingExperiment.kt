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
import android.util.Base64
import lightning.dsp.volumemanager.backend.PrivilegedVolumeContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root app_process relay for the manual experiment and supervised Fusion Mode.
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
            android.util.Log.e("LightningDSP_FusionMode", "Helper initialization or routing failed", error)
            System.err.println("Fusion Mode helper failed: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
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
            println("Fusion Mode routing helper. See poc/app-mixer-mode/ROUTING-EXPERIMENT.md")
            return
        }
        if (args.size == 3 && args[0] == "--local-dsp-files-preflight") {
            check(Process.myUid() == 0)
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
            val path = String(Base64.decode(args[2], Base64.URL_SAFE), Charsets.UTF_8)
            AppMixerLocalDsp.verifyFileAccess(PrivilegedVolumeContext.obtain(), args[1].toInt(), path)
            println("APP_MIXER:FILES_OK (read-only; no DSP instance or route created)")
            return
        }
        val supervised = args.size == 9 && args[7] == "--local-dsp" && args[8].startsWith("--owner-external-b64=") && args[0] == "--supervised" && args[1].startsWith("--exclude-uids=") && args[2].startsWith("--user=") && args[3].startsWith("--owner=") && args[4].startsWith("--owner-pid=") && args[5].startsWith("--owner-start=") && args[6].matches(Regex("--include-system-sounds=[01]"))
        val config = if (supervised) AppMixerExperimentConfig(
            AppMixerExperimentConfig.parseExcludedUids(args[1], args[6].endsWith("=1")), 90, excludeUids = true,
            userId = args[2].removePrefix("--user=").toInt().also { require(it in 0..21473) },
            ownerUid = args[3].removePrefix("--owner=").toInt(),
            includeSystemSounds = args[6].endsWith("=1"), localDsp = true,
            ownerExternalFiles = String(Base64.decode(args[8].removePrefix("--owner-external-b64="), Base64.URL_SAFE), Charsets.UTF_8))
        else AppMixerExperimentConfig.parse(args)
        if (supervised) require(config.ownerUid in config.uids && config.ownerUid / 100000 == config.userId && config.ownerUid % 100000 in 10000..19999)
        check(Process.myUid() == 0) { "A separately authorized root app_process is required" }
        val stop = AtomicBoolean(false)
        if (supervised) {
            val owner = AppMixerOwnerIdentity(args[4].removePrefix("--owner-pid=").toInt(),
                config.ownerUid, args[5].removePrefix("--owner-start=").toLong())
            check(AppMixerOwnerIdentity.read(owner.pid) == owner) { "Fusion Mode owner is no longer valid" }
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
        val updater = AtomicReference<((Set<Int>, Boolean) -> Boolean)?>(null)
        Thread {
            try {
                val input = System.`in`.bufferedReader()
                if (supervised) {
                    val command = AppMixerControlProtocol.readCommand(input)
                    if (command != "READY" || !commands.offer(command)) return@Thread
                    while (true) {
                        val line = AppMixerControlProtocol.readCommand(input, 256) ?: break
                        if (line == "STOP") break
                        val parts = line.split(' ', limit = 4)
                        if (parts.size != 4 || parts[0] != "UPDATE" || parts[1].toLongOrNull() == null || parts[2] !in setOf("0", "1")) break
                        val ok = runCatching {
                            val includeSystemSounds = parts[2] == "1"
                            val excluded = AppMixerExperimentConfig.parseExcludedUids("--exclude-uids=${parts[3]}", includeSystemSounds)
                            require(config.ownerUid in excluded)
                            updater.get()?.invoke(excluded, includeSystemSounds) == true
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
                    updater: AtomicReference<((Set<Int>, Boolean) -> Boolean)?>,
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
        val relayFrames = AppMixerLatencyPolicy.framesPerBurst(
            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
        val ruleBuilderType = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val uidRule = ruleType.getField("RULE_MATCH_UID").getInt(null)
        fun buildRule(uids: Set<Int>, includeSystemSounds: Boolean = config.includeSystemSounds): Any {
        val ruleBuilder = ruleBuilderType.getConstructor().newInstance()
        // Some apps report PCM as UNKNOWN. Optional notification/sonification usage
        // matching covers notification tones and keyboard/system clicks, not calls or alarms.
        val usages = buildList {
            add(AudioAttributes.USAGE_MEDIA)
            add(AudioAttributes.USAGE_GAME)
            add(AudioAttributes.USAGE_UNKNOWN)
            if (includeSystemSounds) {
                add(AudioAttributes.USAGE_NOTIFICATION)
                add(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                add(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            }
        }
        for (usage in usages) {
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
        var localDsp: AppMixerLocalDsp? = null
        try {
            check(!stop.get()) { "Owner pipe closed before routing" }
            if (config.localDsp) localDsp = AppMixerLocalDsp.open(context, config.ownerUid, stop, relayFrames * AppMixerLatencyPolicy.CHANNELS, config.ownerExternalFiles)
            // Build the return path first, but do not start playback or enable DSP yet.
            val minimumBytes = AudioTrack.getMinBufferSize(48000,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimumBytes > 0) { "PCM playback configuration unavailable: $minimumBytes" }
            val bytes = AppMixerLatencyPolicy.playbackCapacityBytes(minimumBytes, relayFrames)
            player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                // Local DSP has no framework insert and may request FAST. The
                // legacy framework-effect backend must retain normal playback.
                .setPerformanceMode(if (config.localDsp) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
                .setBufferSizeInBytes(bytes).build()
            check(player.state == AudioTrack.STATE_INITIALIZED && player.audioSessionId > 0)
            val minimumFrames = (minimumBytes + 3) / 4
            val recoveryFloor = AppMixerLatencyPolicy.recoveryFloorFrames(minimumFrames, config.localDsp)
            val bufferTuner = AppMixerPlaybackBufferTuner(recoveryFloor)
            var lastRequestedBuffer = -1
            var lastAcceptedBuffer = -1
            var lastBufferCapacity = -1
            fun tunePlaybackBuffer() {
                val target = bufferTuner.targetFrames(player.bufferCapacityInFrames, player.underrunCount)
                val capacity = player.bufferCapacityInFrames
                val current = player.bufferSizeInFrames
                // A valid platform clamp is not a failure: do not reconfigure the
                // same accepted size every second. A route/track restoration can
                // change capacity or size and must still trigger a fresh request.
                if (lastRequestedBuffer != target || lastBufferCapacity != capacity ||
                    lastAcceptedBuffer != current) {
                    val actual = player.setBufferSizeInFrames(target)
                    if (actual > 0) {
                        lastRequestedBuffer = target
                        lastAcceptedBuffer = actual
                        lastBufferCapacity = capacity
                    }
                    System.err.println("Fusion Mode playback buffer: constructionMinimum=$minimumFrames recoveryFloor=$recoveryFloor requested=$target actual=$actual capacity=${player.bufferCapacityInFrames}")
                }
            }
            // Capacity remains conservatively sized. The effective queue is tuned separately;
            // AudioTrack clamps unsupported sizes and underruns restore safe headroom.
            runCatching { tunePlaybackBuffer() }
            if (prepareExternalEffect == null) effect = createDiagnosticEffect(player.audioSessionId)
            else prepareExternalEffect(player.audioSessionId)
            checkNoExistingEffects(expectedCount = AppMixerEffectInventory.expectedAfterPreparation(config.localDsp))
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
            if (config.excludeUids) updater.set { excluded, includeSystemSounds ->
                val update = policyType.getMethod("updateMixingRules", List::class.java)
                val updates = listOf(android.util.Pair(mix, buildRule(excluded, includeSystemSounds)))
                (update.invoke(policy, updates) as Int) == 0
            }
            println("APP_MIXER:RUNNING session=${player.audioSessionId} uidCount=${config.uids.size} effect=${if (config.localDsp) "local" else if (prepareExternalEffect == null) "diagnostic" else "external"}; coverage NOT verified")
            System.out.flush()
            // Supervised lifetime is controlled by the owner's pipe; manual tests
            // remain time-limited. Rebuilding the mix periodically interrupts all tracks.
            val deadline = if (prepareExternalEffect == null)
                SystemClock.elapsedRealtime() + config.seconds * 1000L else Long.MAX_VALUE
            val source = recorder
            val target = player
            if (!AppMixerRelayScheduling.requestAudioPriority {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                }) System.err.println("Fusion Mode relay: audio scheduling unavailable; retaining default priority")
            var lastDiagnostic = System.nanoTime()
            var diagnosticReadGap = 0L
            val fixedDspCapture = localDsp?.let { dsp ->
                AppMixerFixedDspCapture(relayFrames * AppMixerLatencyPolicy.CHANNELS, dsp::process)
            }
            System.err.println("Fusion Mode relay: backend=${if (config.localDsp) "local-dsp" else "framework"} performanceMode=${target.performanceMode} sampleRate=${target.sampleRate}")
            val counts = AppMixerPcmRelay.runBuffered(ShortArray(relayFrames * AppMixerLatencyPolicy.CHANNELS),
                shouldContinue = { !stop.get() && SystemClock.elapsedRealtime() < deadline },
                read = { input ->
                    if (fixedDspCapture == null) source.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
                    else fixedDspCapture.read(input) { capture, offset, count ->
                        source.read(capture, offset, count, AudioRecord.READ_NON_BLOCKING)
                    }
                },
                write = { block, offset, count -> target.write(block, offset, count, AudioTrack.WRITE_NON_BLOCKING) },
                idle = { Thread.sleep(2) },
                progressIntervalNanos = 1_000_000_000L,
                onProgress = { now, progress, readGap ->
                    // Shared-memory AudioTrack metrics; no dumpsys/Binder topology
                    // query or parameter synchronization on the PCM hot path.
                    runCatching { tunePlaybackBuffer() }
                    diagnosticReadGap = maxOf(diagnosticReadGap, readGap)
                    if (now - lastDiagnostic >= 5_000_000_000L) {
                        System.err.println("Fusion Mode relay: read=${progress.readSamples} written=${progress.writtenSamples} queuedFrames=${(progress.readSamples - progress.writtenSamples) / 2} maxReadGapMs=${diagnosticReadGap / 1_000_000} underruns=${target.underrunCount} buffer=${target.bufferSizeInFrames} capacity=${target.bufferCapacityInFrames}")
                        lastDiagnostic = now
                        diagnosticReadGap = 0L
                    }
                })
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
            cleanup { localDsp?.close() }
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

    private fun checkNoExistingEffects(expectedCount: Int = 0) {
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
        check(count == expectedCount) { "Framework DSP inventory mismatch: expected=$expectedCount found=$count; duplicate processing is not permitted" }
    }
}
