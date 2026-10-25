package lightning.dsp.volumemanager.backend

import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Isolated UID-0 process. Its only input is our private pipe, never a published service. */
object LightningDspVolumeRoot {
    @JvmStatic
    fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 0) { "Root is required" }
        val nonce = args.single()
        require(VolumeCleanupReceipt.validNonce(nonce))
        val receipt = java.io.FileOutputStream(java.io.FileDescriptor.err)
        run(System.`in`, System.out) { clean ->
            VolumeCleanupReceipt.write(receipt, nonce, clean) { receipt.fd.sync() }
        }
    }

    /** Explicit owned streams: never redirects global stdout in the Shizuku JVM. */
    internal fun run(source: java.io.InputStream, output: java.io.PrintStream, recordCleanup: (Boolean) -> Unit) {
        val uid = android.os.Process.myUid()
        check(VolumeBridgeIdentity.privileged(uid)) { "Privileged playback identity required" }
        val wakeups = LinkedBlockingQueue<Unit>(1)
        val latestSettings = AtomicReference<String?>(null)
        val operations = SystemVolumeOperationQueue(capacity = 32)
        val rejectedOperations = LinkedBlockingQueue<Long>(32)
        val stopRequested = AtomicBoolean(false)
        val refreshRequested = AtomicBoolean(false)
        val hostLease = VolumeHostLease(SystemClock.elapsedRealtime())
        val input = source.bufferedReader()
        val reader = Thread {
            try {
                while (true) {
                    val line = input.readLine() ?: break
                    hostLease.renew(SystemClock.elapsedRealtime())
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when {
                        json.optBoolean("stop") -> stopRequested.set(true)
                        json.optBoolean("refresh") -> refreshRequested.set(true)
                        json.has("volume_operation") -> {
                            val operation = SystemVolumeOperation.parse(json)
                            if (operation != null && !operations.offer(operation))
                                rejectedOperations.offer(operation.id)
                        }
                        else -> latestSettings.set(line)
                    }
                    wakeups.offer(Unit)
                }
            } finally {
                stopRequested.set(true)
                wakeups.offer(Unit)
            }
        }.apply { isDaemon = true; start() }
        val ownership = VolumeEngineOwnership {
            // Abstract Unix socket is a name-only exclusion guard; no connections,
            // commands or Binder relays are ever accepted on this socket.
            android.net.LocalServerSocket("com.lightning.dsp.volume-manager.owner")
        }
        var audio: AudioManager? = null
        var settings = JSONObject()
        val changedPlayers = mutableMapOf<Int, Any>()
        val pannedPlayers = mutableSetOf<Int>()
        val snapshots = PlaybackSnapshotGate()
        val replayGuard = SystemVolumeOperationReplayGuard()
        val panelBypass = AtomicBoolean(false)
        var panelController: LightningDspRootVolumeController? = null
        var panelControllerAttempted = false
        var panelError: String? = null
        var callback: AudioManager.AudioPlaybackCallback? = null
        val callbackThread = android.os.HandlerThread("LightningDSP-playback-callback").apply { start() }
        var rootForegroundError: String? = null
        var callbackWarning: String? = null
        var lastPlaybackScanAt = 0L
        try {
            ownership.acquire()
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            if (PlaybackVolumePolicy.requiresHiddenApiExemption(Build.VERSION.SDK_INT)) {
                val runtimeClass = Class.forName("dalvik.system.VMRuntime")
                val runtime = runtimeClass.getDeclaredMethod("getRuntime").invoke(null)
                runtimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .invoke(runtime, arrayOf("L"))
            }
            val context = PrivilegedVolumeContext.obtain()
            audio = context.getSystemService(AudioManager::class.java)
            callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    refreshRequested.set(true)
                    wakeups.offer(Unit)
                }
            }
            runCatching { audio.registerAudioPlaybackCallback(callback, android.os.Handler(callbackThread.looper)) }
                .onFailure { callbackWarning = "Playback callback unavailable; using periodic discovery" }
            // No gain/controller operation is permitted until this private handshake
            // is acknowledged by an explicit activate=true command from the host.
            output.println("{\"engine_ready\":true}")
            output.flush()
            while (true) {
                // A disappeared host must not leave a forgotten child attenuating audio.
                // Recovery scans/callbacks do not renew this private-pipe ownership lease.
                if (hostLease.expired(SystemClock.elapsedRealtime())) break
                wakeups.poll(500, TimeUnit.MILLISECONDS)
                if (stopRequested.get()) break
                var settingsChanged = false
                latestSettings.getAndSet(null)?.let { raw ->
                    val next = runCatching { JSONObject(raw) }.getOrNull()
                    if (next != null) {
                        settings = next
                        settingsChanged = true
                        panelBypass.set(settings.optBoolean("panel_bypass"))
                    }
                }
                if (!settings.optBoolean("activate")) {
                    while (true) {
                        val operation = operations.poll() ?: break
                        output.println(volumeOperationResult(operation.id, false, "Volume manager is not active"))
                        output.flush()
                    }
                    operations.clear()
                    while (true) {
                        val rejected = rejectedOperations.poll() ?: break
                        output.println(volumeOperationResult(rejected, false, "Volume manager is not active"))
                        output.flush()
                    }
                    continue
                }
                while (true) {
                    val rejected = rejectedOperations.poll() ?: break
                    output.println(volumeOperationResult(rejected, false, "Too many pending volume operations"))
                    output.flush()
                }
                while (true) {
                    val operation = operations.poll() ?: break
                    val result = try {
                        check(replayGuard.accept(operation.id)) { "Duplicate volume operation" }
                        val currentAudio = audio ?: error("Audio service unavailable")
                        check(!currentAudio.isVolumeFixed) { "System volume is fixed by the device" }
                        when (operation.kind) {
                            SystemVolumeOperation.Kind.SET -> {
                                check(SystemVolumeOperation.isSupportedStream(operation.stream)) { "Unsupported system stream" }
                                val minimum = if (Build.VERSION.SDK_INT >= 28) currentAudio.getStreamMinVolume(operation.stream) else 0
                                val maximum = currentAudio.getStreamMaxVolume(operation.stream)
                                check(operation.value in minimum..maximum) { "Requested volume is outside stream bounds" }
                                currentAudio.setStreamVolume(operation.stream, operation.value, 0)
                            }
                            SystemVolumeOperation.Kind.ADJUST -> {
                                check(operation.value == AudioManager.ADJUST_RAISE || operation.value == AudioManager.ADJUST_LOWER) {
                                    "Unsupported volume direction"
                                }
                                check(operation.stream == AudioManager.USE_DEFAULT_STREAM_TYPE ||
                                    SystemVolumeOperation.isSupportedStream(operation.stream)) { "Unsupported system stream" }
                                currentAudio.adjustSuggestedStreamVolume(operation.value, operation.stream, 0)
                            }
                        }
                        null
                    } catch (error: Exception) {
                        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    }
                    output.println(volumeOperationResult(operation.id, result == null, result))
                    output.flush()
                }
                val refresh = refreshRequested.getAndSet(false)
                val now = SystemClock.elapsedRealtime()
                if (!settingsChanged && !refresh && now - lastPlaybackScanAt < 500L) continue
                lastPlaybackScanAt = now
                val activityByUid = sortedMapOf<Int, Boolean>()
                var enumerationComplete = true
                var applyError: String? = panelError ?: callbackWarning
                if (uid == 0) {
                    try {
                        @Suppress("DEPRECATION")
                        val foreground = context.getSystemService(android.app.ActivityManager::class.java)
                            .getRunningTasks(1).firstOrNull()?.topActivity?.packageName
                        if (foreground == null) {
                            rootForegroundError = "Foreground app unavailable; volume-button bypass uses accessibility fallback"
                        } else {
                            rootForegroundError = null
                            panelBypass.set(settings.optJSONArray("ignored_packages")?.let { ignored ->
                                (0 until ignored.length()).any { ignored.optString(it) == foreground }
                            } ?: false)
                        }
                    } catch (_: Exception) {
                        rootForegroundError = "Foreground app unavailable; volume-button bypass uses accessibility fallback"
                    }
                }
                if (rootForegroundError != null) {
                    applyError = rootForegroundError
                    // Unknown foreground identity must not swallow excluded apps' keys.
                    if (!settings.optBoolean("foreground_known") && (settings.optJSONArray("ignored_packages")?.length() ?: 0) > 0)
                        panelBypass.set(true)
                }
                if (uid == 0 && settings.optBoolean("panel_enabled")) {
                    if (panelController == null && !panelControllerAttempted) {
                        panelControllerAttempted = true
                        try {
                            panelController = LightningDspRootVolumeController.install(audio, shouldBypassPanel = { panelBypass.get() }) {
                                output.println("{\"show_panel\":true}")
                                output.flush()
                            }
                        } catch (error: Exception) {
                            panelError = "Volume panel replacement unavailable: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}"
                            applyError = panelError
                        }
                    }
                } else {
                    val restoreError = runCatching { panelController?.close() }.exceptionOrNull()
                    if (restoreError == null) {
                        panelController = null
                        panelControllerAttempted = false
                        panelError = null
                    } else {
                        panelError = "Cannot restore system volume panel: ${restoreError.cause?.javaClass?.simpleName ?: restoreError.javaClass.simpleName}"
                    }
                    applyError = panelError ?: rootForegroundError ?: callbackWarning
                }
                val present = mutableSetOf<Int>()
                for (configuration in audio.activePlaybackConfigurations) {
                    try {
                        val uid = PlaybackPlatformAccess.invokeNoArg(configuration, "getClientUid") as Int
                        val id = PlaybackPlatformAccess.invokeNoArg(configuration, "getPlayerInterfaceId") as Int
                        val player = PlaybackPlatformAccess.invokeNoArg(configuration, "getIPlayer") ?: continue
                        present += id
                        val proposed = settings.optJSONObject(uid.toString())
                        val rule = proposed?.takeIf {
                            PlaybackVolumePolicy.matchesPackage(it.optString("package"),
                                context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())
                        }
                        if (proposed != null && rule == null) applyError = "Player package identity changed; waiting for catalog refresh"
                        // Touch only explicitly managed players, never all system audio by default.
                        if (rule != null || changedPlayers.containsKey(id)) {
                            try {
                                val volume = rule?.optDouble("volume", 1.0)?.toFloat() ?: 1f
                                val pan = rule?.optDouble("pan", 0.0)?.toFloat() ?: 0f
                                require(volume.isFinite() && volume in 0f..1f)
                                require(pan.isFinite() && pan in -1f..1f)
                                // Track before IPC: a failed reply does not prove no side effect.
                                if (rule != null) changedPlayers[id] = player
                                player.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                                    .apply { isAccessible = true }.invoke(player, volume)
                                // Track immediately: a later unsupported pan call must not skip cleanup.
                                if (rule != null) changedPlayers[id] = player
                                if (pan != 0f || id in pannedPlayers) {
                                    if (pan != 0f) pannedPlayers.add(id)
                                    player.javaClass.getMethod("setPan", Float::class.javaPrimitiveType)
                                        .apply { isAccessible = true }.invoke(player, pan)
                                    if (pan != 0f) pannedPlayers.add(id) else pannedPlayers.remove(id)
                                }
                                if (rule != null) changedPlayers[id] = player else changedPlayers.remove(id)
                            } catch (error: Exception) {
                                applyError = "Player control unavailable: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}"
                            }
                        }
                        val active = PlaybackPlatformAccess.invokeNoArg(configuration, "isActive") as Boolean
                        activityByUid[uid] = activityByUid[uid] == true || active
                    } catch (error: Exception) {
                        enumerationComplete = false
                        applyError = "Player discovery unavailable: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}"
                    }
                }
                if (enumerationComplete) {
                    changedPlayers.keys.retainAll(present)
                    pannedPlayers.retainAll(present)
                }
                val players = JSONArray()
                activityByUid.forEach { (uid, active) -> players.put(JSONObject().put("uid", uid).put("active", active)) }
                val response = JSONObject().put("players", players).put("error", applyError ?: JSONObject.NULL).toString()
                when (snapshots.next(response, SystemClock.elapsedRealtime())) {
                    PlaybackSnapshotGate.Update.SNAPSHOT -> output.println(response)
                    PlaybackSnapshotGate.Update.HEARTBEAT -> output.println("{\"heartbeat\":true}")
                    PlaybackSnapshotGate.Update.NONE -> continue
                }
                output.flush()
            }
        } catch (error: Throwable) {
            output.println(JSONObject().put("error", "Privileged playback access unavailable: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}"))
            output.flush()
        } finally {
            // Remove our multipliers on opt-out, EOF, or a failed bridge. No services are restarted.
            var cleanupConfirmed = true
            changedPlayers.values.forEach { player ->
                if (!PlaybackCleanupPolicy.restore(
                    binderAlive = { (player as? android.os.IInterface)?.asBinder()?.isBinderAlive },
                    operation = {
                        player.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(player, 1f)
                    }
                )) cleanupConfirmed = false
            }
            pannedPlayers.forEach { id ->
                changedPlayers[id]?.let { player ->
                    if (!PlaybackCleanupPolicy.restore(
                        binderAlive = { (player as? android.os.IInterface)?.asBinder()?.isBinderAlive },
                        operation = {
                            player.javaClass.getMethod("setPan", Float::class.javaPrimitiveType)
                                .apply { isAccessible = true }.invoke(player, 0f)
                        }
                    )) cleanupConfirmed = false
                }
            }
            if (runCatching { panelController?.close() }.isFailure) cleanupConfirmed = false
            runCatching { callback?.let { audio?.unregisterAudioPlaybackCallback(it) } }
            callbackThread.quitSafely()
            // Receipt is written through an already-owned FD, never a privileged path.
            // Retain global ownership until the final cleanup proof is durable.
            if (runCatching { recordCleanup(cleanupConfirmed) }.isFailure) cleanupConfirmed = false
            if (runCatching { ownership.close() }.isFailure) cleanupConfirmed = false
            output.println(JSONObject().put("stopped", true).put("cleanup_confirmed", cleanupConfirmed))
            output.flush()
            reader.interrupt()
        }
    }

    private fun volumeOperationResult(id: Long, ok: Boolean, error: String?): String =
        JSONObject().put("volume_operation_result", id).put("ok", ok)
            .put("volume_operation_error", error ?: JSONObject.NULL).toString()
}
