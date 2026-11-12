package lightning.dsp.interop.appmixer

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A PID alone is not an owner identity: Linux may reuse it after app death. */
internal data class AppMixerOwnerIdentity(val pid: Int, val uid: Int, val startTicks: Long) {
    companion object {
        fun read(pid: Int): AppMixerOwnerIdentity? = runCatching {
            require(pid > 0)
            val stat = File("/proc/$pid/stat").readText()
            val uidLine = File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }
            parse(pid, stat, uidLine)
        }.getOrNull()

        internal fun parse(pid: Int, stat: String, uidLine: String): AppMixerOwnerIdentity {
            require(stat.substringBefore(' ').toInt() == pid)
            require(stat.indexOf('(') > 0 && stat.lastIndexOf(')') > stat.indexOf('('))
            require(uidLine.startsWith("Uid:"))
            // comm can contain spaces and parentheses. Field 22 is offset 19
            // after the final ')' (the remaining fields begin with state).
            val fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
            require(fields[0] != "Z" && fields[0] != "X")
            val ticks = fields[19].toLong()
            val uid = uidLine.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()
            require(ticks > 0 && uid >= 0)
            return AppMixerOwnerIdentity(pid, uid, ticks)
        }
    }
}

/** Pure state machine; procfs failures are tolerated briefly, then fail closed. */
internal class AppMixerOwnerWatchdog(private val expected: AppMixerOwnerIdentity) {
    private var missing = 0
    private var stopSince: Long? = null

    fun poll(observed: AppMixerOwnerIdentity?, stopRequested: Boolean, nowMs: Long): Boolean {
        missing = if (observed == expected) 0 else missing + 1
        if (stopRequested || missing >= 3) {
            if (stopSince == null) stopSince = nowMs
        }
        return stopSince != null
    }

    fun exitRequired(nowMs: Long): Boolean = stopSince?.let { nowMs - it >= 10_000 } == true

    companion object {
        /** Does not kill any other PID and never emits a false cleanup acknowledgement. */
        fun start(owner: AppMixerOwnerIdentity, stop: AtomicBoolean) {
            val watchdog = AppMixerOwnerWatchdog(owner)
            Thread {
                while (true) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (watchdog.poll(AppMixerOwnerIdentity.read(owner.pid), stop.get(), now)) stop.set(true)
                    if (watchdog.exitRequired(now)) kotlin.system.exitProcess(1)
                    Thread.sleep(1_000)
                }
            }.apply { isDaemon = true; name = "AppMixer-owner-watch"; start() }
        }
    }
}
