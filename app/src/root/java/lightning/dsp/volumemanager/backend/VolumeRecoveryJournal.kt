package lightning.dsp.volumemanager.backend

import java.io.Closeable
import java.io.File
import java.io.OutputStream

internal data class VolumeProcessIdentity(val pid: Int, val uid: Int, val start: Long) {
    fun valid() = pid > 0 && uid >= 0 && start > 0
    fun fields() = "$pid $uid $start"
}

internal sealed class VolumeProcessObservation {
    data class Present(val identity: VolumeProcessIdentity) : VolumeProcessObservation()
    object Missing : VolumeProcessObservation()
    object Unknown : VolumeProcessObservation()
}

/** Records are durable before mutation; incomplete evidence never authorizes recovery. */
internal object VolumeRecoveryJournal {
    const val LIMIT = 131072
    private const val PREFIX = "LDSP_VOLUME_JOURNAL_V2"
    data class Evidence(val boot: String, val owner: VolumeProcessIdentity,
                        val server: VolumeProcessIdentity, val clients: Set<VolumeProcessIdentity>)

    fun header(nonce: String, boot: String, owner: VolumeProcessIdentity, server: VolumeProcessIdentity): String {
        require(VolumeCleanupReceipt.validNonce(nonce) && boot.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(owner.valid() && owner.uid == 0 && server.valid() && server.uid == 1000)
        return "$PREFIX $nonce HEADER $boot ${owner.fields()} ${server.fields()}\n"
    }
    fun client(nonce: String, identity: VolumeProcessIdentity): String {
        require(VolumeCleanupReceipt.validNonce(nonce) && identity.valid())
        return "$PREFIX $nonce CLIENT ${identity.fields()}\n"
    }
    fun parse(text: String?, nonce: String): Evidence? = runCatching {
        require(VolumeCleanupReceipt.validNonce(nonce) && text != null && text.length <= LIMIT && text.endsWith('\n'))
        val lines = text.lineSequence().filter { it.startsWith(PREFIX) }.toList()
        require(lines.isNotEmpty() && lines.size <= 513)
        fun identity(parts: List<String>, offset: Int) = VolumeProcessIdentity(
            parts[offset].toInt(), parts[offset + 1].toInt(), parts[offset + 2].toLong()).also { require(it.valid()) }
        val head = lines.first().split(' ')
        require(head.size == 10 && head[0] == PREFIX && head[1] == nonce && head[2] == "HEADER")
        val owner = identity(head, 4); val server = identity(head, 7)
        require(header(nonce, head[3], owner, server).trimEnd() == lines.first())
        val clients = lines.drop(1).map { line ->
            val parts = line.split(' ')
            require(parts.size == 6 && parts[1] == nonce && parts[2] == "CLIENT")
            identity(parts, 3).also { require(client(nonce, it).trimEnd() == line) }
        }.toSet()
        Evidence(head[3], owner, server, clients)
    }.getOrNull()

    fun verify(evidence: Evidence, currentBoot: String?,
               observe: (Int) -> VolumeProcessObservation, exclusive: () -> Closeable): Boolean = runCatching {
        require(currentBoot == evidence.boot)
        exclusive().use {
            (evidence.clients + evidence.owner + evidence.server).all { old ->
                when (val current = observe(old.pid)) {
                    is VolumeProcessObservation.Present -> current.identity.valid() &&
                        current.identity.pid == old.pid && current.identity.start != old.start
                    VolumeProcessObservation.Missing -> true
                    VolumeProcessObservation.Unknown -> false
                }
            }
        }
    }.getOrDefault(false)

    fun proof(nonce: String) = "LDSP_VOLUME_RECOVERED_V2 $nonce"

    class Writer(private val output: OutputStream, private val sync: () -> Unit) {
        private var bytes = 0
        private var failed = false
        private val recorded = mutableSetOf<VolumeProcessIdentity>()
        fun append(record: String) {
            check(!failed)
            try {
                val data = record.toByteArray(Charsets.UTF_8)
                check(bytes + data.size <= LIMIT)
                output.write(data); output.flush(); sync()
                bytes += data.size
            } catch (failure: Exception) { failed = true; throw failure }
        }
        fun beforeClient(nonce: String, identity: VolumeProcessIdentity) {
            if (identity !in recorded) {
                check(recorded.size < 512)
                append(client(nonce, identity))
                recorded.add(identity)
            }
        }
    }
}

/** Root-only /proc reads. Missing requires a readable process-directory listing, not exists(). */
internal class VolumeRecoveryProc(private val root: File = File("/proc")) {
    fun boot(): String? = runCatching { File(root, "sys/kernel/random/boot_id").readText().trim() }.getOrNull()
    fun observe(pid: Int): VolumeProcessObservation = runCatching {
        val directory = File(root, pid.toString())
        val entries = root.list() ?: return VolumeProcessObservation.Unknown
        if (pid.toString() !in entries) return VolumeProcessObservation.Missing
        fun start(): Long {
            val stat = File(directory, "stat").readText()
            require(stat.substringBefore(' ').toInt() == pid)
            return stat.substringAfterLast(") ").trim().split(Regex("\\s+"))[19].toLong()
        }
        fun uid(): Int = File(directory, "status").readLines().single { it.startsWith("Uid:") }
            .substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()
        val firstStart = start()
        val firstUid = uid()
        val identity = VolumeProcessIdentity(pid, firstUid, firstStart)
        require(identity.valid())
        // CPU accounting changes on live processes; only identity fields must be stable.
        require(start() == firstStart && uid() == firstUid)
        VolumeProcessObservation.Present(identity)
    }.getOrDefault(VolumeProcessObservation.Unknown)

    fun identity(pid: Int): VolumeProcessIdentity =
        (observe(pid) as? VolumeProcessObservation.Present)?.identity ?: error("Process identity unavailable")

    fun systemServer(): VolumeProcessIdentity {
        val candidates = (root.listFiles() ?: error("Process list unavailable")).mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            val command = runCatching { File(entry, "cmdline").readText().substringBefore('\u0000') }.getOrNull()
            if (command != "system_server") return@mapNotNull null
            identity(pid).takeIf { it.uid == 1000 }
        }
        return candidates.single()
    }
}
