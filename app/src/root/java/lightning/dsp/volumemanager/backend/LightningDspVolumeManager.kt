package lightning.dsp.volumemanager.backend

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import lightning.dsp.volumemanager.ui.LightningVolumeOverlay
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

/** Persisted opt-in and serialized ownership of the private root playback process. */
enum class VolumeAccessMode { ROOT, SHIZUKU }

object LightningDspVolumeManager {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "LightningDSP-volume-control").apply { isDaemon = true }
    }
    private val mutableState = MutableLiveData(VolumeManagerState())
    val state: LiveData<VolumeManagerState> = mutableState
    @Volatile private var process: Process? = null
    private var session: VolumePipeSession? = null
    @Volatile private var commandSession: VolumePipeSession? = null
    private var serviceOwner: Any? = null
    private var restartPending = false
    private var cleanupUncertain = false
    private var cleanupRecoveryWatch: ScheduledFuture<*>? = null
    private val cleanupLedger by lazy {
        VolumeCleanupLedger(
            dirtyNonce = {
                if (preferences.getBoolean("cleanup_uncertain", false)) preferences.getString("cleanup_receipt_nonce", "") ?: ""
                else null
            },
            persist = { nonce ->
                preferences.edit().putBoolean("cleanup_uncertain", nonce != null)
                    .putString("cleanup_receipt_nonce", nonce).commit()
            },
            readReceipt = { nonce -> readCleanupReceipt(nonce) }
        )
    }
    private var applications: List<ApplicationInfo> = emptyList()
    private var labels: Map<String, String> = emptyMap()
    private var uidCounts: Map<Int, Int> = emptyMap()
    private var activeUids: Set<Int> = emptySet()
    private var seenUids: Set<Int> = emptySet()
    private var connected = false
    private var error: String? = null
    @Volatile private var systemVolumeError: String? = null
    private var systemVolumeOperationId = 0L
    private var generation = 0
    private var lastResponseAt = 0L
    private var heartbeatWatch: ScheduledFuture<*>? = null
    private var lastPublished: VolumeManagerState? = null
    private var foregroundPackage: String? = null
    private val operationSequence = java.util.concurrent.atomic.AtomicLong(
        SystemClock.elapsedRealtime().coerceAtLeast(1L) * 1_000L
    )
    @Volatile private var overlayReady = false
    val isOverlayReady: Boolean get() = overlayReady
    private var overlayError: String? = null

    fun reportOverlayUnavailable(reason: String) {
        worker.execute {
            overlayReady = false; overlayError = reason
            sendSettings(); publish()
        }
    }

    fun reportOverlayAvailable() {
        worker.execute {
            overlayReady = true; overlayError = null
            sendSettings(); publish()
        }
    }
    val isEnabled: Boolean get() = ::preferences.isInitialized && preferences.getBoolean("enabled", false)

    val accessMode: VolumeAccessMode get() = if (::preferences.isInitialized)
        runCatching { VolumeAccessMode.valueOf(preferences.getString("access_mode", "ROOT")!!) }.getOrDefault(VolumeAccessMode.ROOT)
        else VolumeAccessMode.ROOT

    fun setAccessMode(mode: VolumeAccessMode) {
        preferences.edit().putString("access_mode", mode.name).apply()
        worker.execute {
            val owner = serviceOwner
            closeProcess()
            restartPending = isEnabled && owner != null
            if (process == null && restartPending) { restartPending = false; attach(owner!!, requireCurrentOwner = true) }
            publish()
        }
    }

    /** Permission prompts are explicitly initiated by the user, never by startup/boot. */
    fun requestShizukuPermission() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                check(rikka.shizuku.Shizuku.pingBinder()) { "Shizuku is not running" }
                rikka.shizuku.Shizuku.requestPermission(7392)
            }.onFailure { failure -> worker.execute { error = failure.message; publish() } }
        }
    }

    val perAppEnabled: Boolean get() = ::preferences.isInitialized && preferences.getBoolean("per_app_enabled", true)

    fun setPerAppEnabled(enabled: Boolean) {
        check(::preferences.isInitialized)
        preferences.edit().putBoolean("per_app_enabled", enabled).apply()
        worker.execute { sendSettings(); publish() }
    }

    val startupOnBoot: Boolean get() = ::preferences.isInitialized && preferences.getBoolean("startup_on_boot", false)

    fun setStartupOnBoot(enabled: Boolean) {
        check(::preferences.isInitialized)
        preferences.edit().putBoolean("startup_on_boot", enabled).apply()
    }

    @Synchronized
    fun initialize(context: Context) {
        if (::preferences.isInitialized) return
        this.context = context.applicationContext
        preferences = this.context.getSharedPreferences("lightningdsp_volume_manager", Context.MODE_PRIVATE)
        cleanupUncertain = preferences.getBoolean("cleanup_uncertain", false)
        ContextCompat.registerReceiver(this.context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                worker.execute { publish(force = true) }
            }
        }, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            this.context.getSystemService(AppOpsManager::class.java).startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, this.context.packageName
            ) { _, packageName ->
                if (packageName == this.context.packageName) worker.execute { sendSettings() }
            }
        } // The heartbeat also rechecks permission if firmware denies app-op observation.
        ContextCompat.registerReceiver(this.context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener { code, _ ->
            worker.execute {
                if (code == 7392 && accessMode == VolumeAccessMode.SHIZUKU && isEnabled)
                    serviceOwner?.let { attach(it, requireCurrentOwner = true) }
            }
        }
        refresh()
    }

    /** Call from app startup or an authorized boot receiver, not from a static initializer. */
    fun resume() {
        check(::preferences.isInitialized)
        if (isEnabled) startService()
    }

    fun setEnabled(enabled: Boolean) {
        check(::preferences.isInitialized)
        preferences.edit().putBoolean("enabled", enabled).apply()
        if (!enabled) worker.execute { overlayReady = false; overlayError = null }
        if (enabled) startService() else {
            detach()
            context.stopService(Intent(context, LightningDspVolumeService::class.java))
        }
        refresh()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, LightningDspVolumeService::class.java))
        } catch (exception: Exception) {
            worker.execute {
                error = "Cannot start volume management: ${exception.javaClass.simpleName}"
                publish()
            }
        }
    }

    fun refresh() {
        worker.execute {
            applications = context.packageManager.getInstalledApplications(0)
            uidCounts = applications.groupingBy { it.uid }.eachCount()
            labels = applications.associate { application ->
                application.packageName to runCatching { application.loadLabel(context.packageManager).toString() }
                    .getOrDefault(application.packageName)
            }
            publish()
            sendSettings()
        }
    }

    fun setAppVolume(packageName: String, volume: Float) {
        update(packageName, "volume", PlaybackVolumePolicy.volume(volume))
    }

    fun setAppMuted(packageName: String, muted: Boolean) = update(packageName, "muted", muted)

    fun setAppPan(packageName: String, pan: Float) {
        update(packageName, "pan", PlaybackVolumePolicy.pan(pan))
    }

    /** Optional accessibility metadata supplements privileged task discovery. */
    fun setForegroundPackage(packageName: String?) {
        worker.execute {
            if (foregroundPackage == packageName) return@execute
            foregroundPackage = packageName
            sendSettings()
        }
    }

    fun setAppVolumeButtonsIgnored(packageName: String, ignored: Boolean) = update(packageName, "ignore_buttons", ignored)

    /** Changes the actual media stream, never an additional player gain stage. */
    fun setMasterVolume(volume: Float) {
        val normalized = PlaybackVolumePolicy.volume(volume)
        if (::context.isInitialized) {
            val audio = context.getSystemService(AudioManager::class.java)
            setSystemStreamVolume(AudioManager.STREAM_MUSIC,
                (normalized * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt())
        }
    }

    /** Uses the already-authorized helper first, with the regular platform call as fallback. */
    fun setSystemStreamVolume(stream: Int, index: Int): Boolean {
        if (!::context.isInitialized) return false
        if (!SystemVolumeOperation.isSupportedStream(stream)) return false
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return false
        val bounds = runCatching {
            val minimum = if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
            minimum to audio.getStreamMaxVolume(stream)
        }.getOrNull() ?: return false
        val operation = runCatching {
            SystemVolumeOperation.set(nextOperationId(), stream, index, bounds.first, bounds.second)
        }.getOrNull() ?: return false
        val activeSession = commandSession
        if (activeSession?.sideEffectsAuthorized == true) return activeSession.offerVolumeOperation(operation)
        return try {
            audio.setStreamVolume(stream, index, 0)
            systemVolumeError = null
            worker.execute { publish() }
            true
        } catch (exception: Exception) {
            systemVolumeError = "System volume change unavailable: ${exception.javaClass.simpleName}"
            worker.execute { publish() }
            false
        }
    }

    /** Returns false rather than consuming a hardware volume key when no route accepts it. */
    fun adjustSystemVolume(direction: Int): Boolean {
        if (!::context.isInitialized) return false
        if (context.getSystemService(AudioManager::class.java).isVolumeFixed) return false
        val operation = runCatching {
            SystemVolumeOperation.adjust(nextOperationId(), direction)
        }.getOrNull() ?: return false
        val activeSession = commandSession
        if (activeSession?.sideEffectsAuthorized == true) return activeSession.offerVolumeOperation(operation)
        return try {
            context.getSystemService(AudioManager::class.java)
                .adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
            systemVolumeError = null
            worker.execute { publish() }
            true
        } catch (exception: Exception) {
            systemVolumeError = "System volume adjustment unavailable: ${exception.javaClass.simpleName}"
            worker.execute { publish() }
            false
        }
    }

    /** Modes controls use the owned privileged pipe without granting persistent app access. */
    fun setSystemRingerMode(mode: Int): Boolean {
        if (!::context.isInitialized) return false
        val operation = runCatching { SystemVolumeOperation.ringer(nextOperationId(), mode) }.getOrNull() ?: return false
        return submitSystemModeOperation(operation) {
            context.getSystemService(AudioManager::class.java).ringerMode = mode
        }
    }

    fun setSystemInterruptionFilter(filter: Int): Boolean {
        if (!::context.isInitialized) return false
        val operation = runCatching { SystemVolumeOperation.interruptionFilter(nextOperationId(), filter) }.getOrNull() ?: return false
        return submitSystemModeOperation(operation) {
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            check(notifications.isNotificationPolicyAccessGranted) { "Privileged volume manager or Modes access is required" }
            notifications.setInterruptionFilter(filter)
        }
    }

    /** True means queued on the privileged route, not proof that Android applied it. */
    private fun submitSystemModeOperation(operation: SystemVolumeOperation, fallback: () -> Unit): Boolean {
        val activeSession = commandSession
        if (activeSession?.sideEffectsAuthorized == true) {
            val queued = activeSession.offerVolumeOperation(operation)
            if (!queued) {
                systemVolumeError = "Mode change could not be queued"
                worker.execute { publish() }
            }
            return queued
        }
        return try {
            fallback()
            systemVolumeError = null
            worker.execute { publish() }
            true
        } catch (exception: Exception) {
            systemVolumeError = "Mode change unavailable: ${exception.message ?: exception.javaClass.simpleName}"
            worker.execute { publish() }
            false
        }
    }

    private fun nextOperationId(): Long = operationSequence.incrementAndGet().coerceAtLeast(1L)

    private fun handleVolumeOperationResult(response: JSONObject) {
        val operationId = response.optLong("volume_operation_result", -1L)
        if (operationId <= 0L) return
        systemVolumeOperationId = operationId
        systemVolumeError = if (response.optBoolean("ok")) null else
            response.optString("volume_operation_error").ifEmpty { "System volume change was rejected" }
        publish()
    }

    private fun update(packageName: String, name: String, value: Any) {
        worker.execute {
            val application = applications.find { it.packageName == packageName } ?: return@execute
            // Android player ownership is UID-scoped. Do not guess between shared-UID apps.
            if (name in setOf("volume", "muted", "pan") && uidCounts[application.uid] != 1) {
                error = "Per-app control is unavailable for packages sharing an Android UID"
                publish()
                return@execute
            }
            val edit = preferences.edit()
            applications.filter { it.packageName == packageName }.forEach {
                when (value) {
                    is Float -> edit.putFloat("$name:${it.packageName}", value)
                    is Boolean -> edit.putBoolean("$name:${it.packageName}", value)
                }
            }
            edit.apply()
            sendSettings()
            publish()
        }
    }

    internal fun attach(owner: Any, requireCurrentOwner: Boolean = false) {
        worker.execute {
            if (requireCurrentOwner && serviceOwner !== owner) return@execute
            serviceOwner = owner
            if (!isEnabled) return@execute
            if (cleanupUncertain && !recoverCleanup()) {
                error = "Waiting for verified cleanup of the previous playback owner"
                scheduleCleanupRecovery()
                publish()
                return@execute
            }
            if (process != null) { restartPending = session?.phase == VolumePipeSession.Phase.STOPPING; return@execute }
            error = null
            val epoch = ++generation
            try {
                val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
                val nonce = newCleanupNonce()
                val receipt = prepareCleanupReceipt(nonce)
                val command = "CLASSPATH=$apk exec /system/bin/app_process /system/bin lightning.dsp.volumemanager.backend.LightningDspVolumeRoot $nonce"
                val child = if (accessMode == VolumeAccessMode.ROOT)
                    ProcessBuilder("su", "-c", command).redirectError(receipt).start()
                else {
                    check(rikka.shizuku.Shizuku.pingBinder()) { "Shizuku is not running" }
                    check(rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Authorize Shizuku to use per-app volume control" }
                    VolumeShizukuProcess(context, receipt, nonce)
                }
                process = child
                val responsesDrained = java.util.concurrent.CountDownLatch(1)
                session = VolumePipeSession(child, onExited = {
                    responsesDrained.await(1, TimeUnit.SECONDS)
                    worker.execute {
                        if (process === child) {
                            cleanupUncertain = session?.cleanupUncertain == true
                            if (!cleanupUncertain) {
                                // An acknowledged cleanup (or never-authorized child) is safe.
                                cleanupUncertain = !cleanupLedger.confirmLiveCleanup()
                            } else recoverCleanup()
                            if (cleanupUncertain) {
                                error = "Waiting for verified cleanup of the previous playback owner"
                                scheduleCleanupRecovery()
                            } else runCatching { receipt.delete() }
                            generation++
                            process = null; session = null; commandSession = null
                            connected = false; activeUids = emptySet()
                            heartbeatWatch?.cancel(false); heartbeatWatch = null
                            publish()
                            if (restartPending && isEnabled && !cleanupUncertain) {
                                restartPending = false
                                serviceOwner?.let { attach(it, requireCurrentOwner = true) }
                            }
                        }
                    }
                }, onWriteFailure = { failure -> worker.execute {
                    if (process === child) {
                        error = "Playback connection failed: ${failure.javaClass.simpleName}"
                        closeProcess(); publish()
                    }
                } })
                // Drain stderr, but never forward privileged framework logs into app state.
                Thread({ child.errorStream.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) != -1) { /* discard */ } } }, "LightningDSP-volume-errors")
                    .apply { isDaemon = true; start() }
                Thread({
                    try {
                        child.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val response = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                                if (response.optBoolean("stopped")) {
                                    // Teardown replies remain valid after command generation is invalidated.
                                    if (process === child) session?.confirmCleanup(response.optBoolean("cleanup_confirmed"))
                                    return@forEach
                                }
                                worker.execute {
                                    if (epoch != generation || process !== child) return@execute
                                    lastResponseAt = SystemClock.elapsedRealtime()
                                    if (response.optBoolean("engine_ready")) {
                                        if (!isEnabled || serviceOwner == null) return@execute
                                        // Record possible side effects durably before permitting any.
                                        // A dead app cannot run its exit callback to write this later.
                                        if (!cleanupLedger.authorize(nonce)) {
                                            error = "Cannot persist playback ownership; activation was refused"
                                            closeProcess(); publish(); return@execute
                                        }
                                        session?.authorizeSideEffects()
                                        commandSession = session
                                        sendSettings()
                                        return@execute
                                    }
                                    if (response.optBoolean("heartbeat")) {
                                        // Recheck overlay permission even on firmware without app-op callbacks.
                                        sendSettings()
                                        return@execute
                                    }
                                    if (response.optBoolean("show_panel")) {
                                        if (isEnabled && Settings.canDrawOverlays(context)) LightningVolumeOverlay.show(context)
                                        return@execute
                                    }
                                    if (response.has("volume_operation_result")) {
                                        handleVolumeOperationResult(response)
                                        return@execute
                                    }
                                    val players = response.optJSONArray("players")
                                    connected = players != null
                                    error = if (response.isNull("error")) null else response.optString("error").ifEmpty { null }
                                    if (players != null) {
                                        val observed = (0 until players.length()).map { players.getJSONObject(it).getInt("uid") }.toSet()
                                        seenUids = seenUids + observed
                                        val edit = preferences.edit()
                                        applications.filter { it.uid in observed }.forEach { edit.putBoolean("had_player:${it.packageName}", true) }
                                        edit.apply()
                                    }
                                    activeUids = if (players == null) emptySet() else (0 until players.length())
                                        .map { players.getJSONObject(it) }.filter { it.optBoolean("active") }
                                        .map { it.getInt("uid") }.toSet()
                                    publish()
                                }
                            }
                        }
                    } finally {
                        responsesDrained.countDown()
                        worker.execute {
                            if (epoch == generation && process === child) {
                                closeProcess()
                                connected = false
                                activeUids = emptySet()
                                error = error ?: "Privileged playback process disconnected"
                                publish()
                            }
                        }
                    }
                }, "LightningDSP-volume-playback").apply { isDaemon = true; start() }
                sendSettings()
                lastResponseAt = SystemClock.elapsedRealtime()
                heartbeatWatch = worker.scheduleWithFixedDelay({
                    // Renewal must not depend on root snapshot/heartbeat suppression.
                    if (epoch == generation && process != null) sendSettings()
                    val timeout = if (connected) 15_000 else 30_000
                    if (epoch == generation && process != null && SystemClock.elapsedRealtime() - lastResponseAt >= timeout) {
                        closeProcess()
                        error = error ?: "Privileged playback access timed out"
                        publish()
                    }
                }, 5, 5, TimeUnit.SECONDS)
            } catch (exception: Exception) {
                if (exception is VolumeShizukuStartException && exception.cleanupUncertain) {
                    cleanupUncertain = true
                    preferences.edit().putBoolean("cleanup_uncertain", true).apply()
                }
                closeProcess()
                error = "Cannot obtain ${accessMode.name.lowercase()} playback access: ${exception.message ?: exception.javaClass.simpleName}"
                publish()
            }
        }
    }

    internal fun detach(owner: Any? = null) {
        worker.execute {
            if (owner != null && serviceOwner !== owner) { return@execute }
            serviceOwner = null
            restartPending = false
            cleanupRecoveryWatch?.cancel(false)
            cleanupRecoveryWatch = null
            closeProcess()
            error = null
            publish()
        }
    }

    private fun newCleanupNonce(): String = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun cleanupReceiptFile(nonce: String): java.io.File {
        require(VolumeCleanupReceipt.validNonce(nonce))
        return java.io.File(java.io.File(context.noBackupFilesDir, "volume-cleanup"), "$nonce.receipt")
    }

    private fun prepareCleanupReceipt(nonce: String): java.io.File {
        val file = cleanupReceiptFile(nonce)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        android.system.Os.chmod(file.parentFile!!.absolutePath, 448) // 0700
        check(file.createNewFile())
        android.system.Os.chmod(file.absolutePath, 384) // 0600, opened by app before privileges
        return file
    }

    private fun readCleanupReceipt(nonce: String): String? = runCatching {
        val file = cleanupReceiptFile(nonce)
        if (!file.isFile) return@runCatching null
        java.io.RandomAccessFile(file, "r").use { input ->
            // Root stderr may include framework/su diagnostics. Read bounded tail only.
            val count = input.length().coerceAtMost(4096).toInt()
            input.seek(input.length() - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            // A tail starting in the middle of a diagnostic line is not a record boundary.
            if (input.length() > count) text.substringAfter('\n', "") else text
        }
    }.getOrNull()

    private fun recoverCleanup(): Boolean {
        if (!cleanupUncertain) return true
        if (!cleanupLedger.recover()) return false
        cleanupUncertain = false
        error = null
        cleanupRecoveryWatch?.cancel(false)
        cleanupRecoveryWatch = null
        return true
    }

    private fun scheduleCleanupRecovery() {
        if (cleanupRecoveryWatch != null || !isEnabled || serviceOwner == null) return
        cleanupRecoveryWatch = worker.scheduleWithFixedDelay({
            if (!isEnabled || serviceOwner == null) {
                cleanupRecoveryWatch?.cancel(false); cleanupRecoveryWatch = null
            } else if (process == null && recoverCleanup()) {
                publish()
                serviceOwner?.let { attach(it, requireCurrentOwner = true) }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private fun closeProcess() {
        generation++
        heartbeatWatch?.cancel(false)
        heartbeatWatch = null
        commandSession = null
        session?.stop()
        connected = false
        activeUids = emptySet()
    }

    private fun sendSettings() {
        val output = session ?: return
        val ignored = org.json.JSONArray()
        applications.filter { preferences.getBoolean("ignore_buttons:${it.packageName}", false) }.forEach { ignored.put(it.packageName) }
        val rules = JSONObject().put("activate", output.sideEffectsAuthorized).put("foreground_known", foregroundPackage != null).put("ignored_packages", ignored).put("panel_enabled", isEnabled && Settings.canDrawOverlays(context))
            .put("panel_bypass", foregroundPackage?.let { preferences.getBoolean("ignore_buttons:$it", false) } ?: false)
        applications.filter { application -> isEnabled && perAppEnabled && PlaybackVolumePolicy.canControl(uidCounts[application.uid] ?: 0) }.forEach { application ->
            val name = application.packageName
            val muted = preferences.getBoolean("muted:$name", false)
            val volume = preferences.getFloat("volume:$name", 1f).coerceIn(0f, 1f)
            val pan = preferences.getFloat("pan:$name", 0f).coerceIn(-1f, 1f)
            if (muted || volume != 1f || pan != 0f) {
                rules.put(application.uid.toString(), JSONObject()
                    .put("package", name).put("volume", PlaybackVolumePolicy.multiplier(perAppEnabled, muted, volume)).put("pan", pan))
            }
        }
        output.offer(rules.toString())
    }

    private fun publish(force: Boolean = false) {
        val apps = applications.map { application ->
            val name = application.packageName
            AppVolume(name, labels[name] ?: name,
                application.uid, application.uid in activeUids,
                preferences.getFloat("volume:$name", 1f), preferences.getBoolean("muted:$name", false),
                preferences.getFloat("pan:$name", 0f), application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                preferences.getBoolean("ignore_buttons:$name", false), uidCounts[application.uid] == 1, preferences.getBoolean("had_player:$name", false) || application.uid in seenUids)
        }.sortedWith(compareByDescending<AppVolume> { it.active }.thenBy { it.label.lowercase() })
        val audio = context.getSystemService(AudioManager::class.java)
        val mediaVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }.getOrDefault(1f)
        val next = VolumeManagerState(isEnabled, connected, error ?: overlayError, apps, mediaVolume,
            perAppEnabled, overlayReady, systemVolumeError, systemVolumeOperationId)
        if (force || next != lastPublished) {
            lastPublished = next
            mutableState.postValue(next)
        }
    }
}

data class AppVolume(
    val packageName: String,
    val label: String,
    val uid: Int,
    val active: Boolean,
    val volume: Float,
    val muted: Boolean,
    val pan: Float,
    val systemApp: Boolean = false,
    val volumeButtonsIgnored: Boolean = false,
    val controllable: Boolean = true,
    val hadPlayer: Boolean = false
)

data class VolumeManagerState(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val apps: List<AppVolume> = emptyList(),
    val masterVolume: Float = 1f,
    val perAppEnabled: Boolean = true,
    val overlayReady: Boolean = false,
    val systemVolumeError: String? = null,
    val systemVolumeOperationId: Long = 0L
)
