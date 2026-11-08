package lightning.dsp.session.dump.provider

import android.content.Context
import lightning.dsp.model.AudioSessionDumpEntry
import lightning.dsp.session.dump.data.AudioServiceDump
import lightning.dsp.session.dump.data.ISessionInfoDump
import lightning.dsp.session.dump.utils.AudioFlingerServiceDumpUtils
import lightning.dsp.session.dump.utils.AudioServicePlaybackActivity
import lightning.dsp.session.dump.utils.DumpUtils
import lightning.dsp.utils.extensions.ContextExtensions.getPackageNameFromUid
import timber.log.Timber

class AudioServiceDumpProvider : ISessionDumpProvider {

    override fun dump(context: Context): ISessionInfoDump? {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        dump ?: return null

        val processed = process(context, dump)
        // A parser miss is not evidence that playback disappeared. Only accept
        // an empty result when the complete current-player section is empty.
        return processed.takeIf {
            it.sessions.isNotEmpty() || AudioServicePlaybackActivity.hasCompleteEmptyPlayers(dump)
        }
    }

    private fun process(context: Context, dump: String): ISessionInfoDump {

        val sidPidLookupMap = mutableMapOf<Int /* pid */, Int /* sid */>()
        val globalSessionRefs = AudioFlingerServiceDumpUtils.dump(context)
        globalSessionRefs?.forEach {
            if(sidPidLookupMap.contains(it.pid))
            {
                Timber.w("SID/PID map: Duplicated PID (pid=${it.pid}; sid=${it.sid})")
            }
            else
            {
                Timber.d("SID/PID map: AudioFlinger: pid=${it.pid}; sid=${it.sid}")
            }

            sidPidLookupMap[it.pid] = it.sid
        }

        val sessions = hashMapOf<Int, AudioSessionDumpEntry>()

        AudioServicePlaybackActivity.parse(dump).forEach { player ->
            // Legacy configuration rows lack a session ID. Keep the old discovery
            // fallback, but do not use PID correlation to prove that session's activity.
            val sid = player.sessionId ?: sidPidLookupMap[player.pid]
            if (sid == null) {
                Timber.w("Failed to determine session for pid=%d uid=%d", player.pid, player.uid)
                return@forEach
            }
            val pkg = context.getPackageNameFromUid(player.uid) ?: player.uid.toString()
            val active = if (player.sessionId != null) player.active else null
            val previous = sessions[sid]
            val merged = if (previous == null) active else if (previous.uid == player.uid) {
                AudioServicePlaybackActivity.merge(previous.playbackActive, active)
            } else null
            sessions[sid] = AudioSessionDumpEntry(player.uid, pkg, player.usage, player.content, merged)
        }

        Timber.d("Dump processed")
        return AudioServiceDump(sessions)
    }

    override fun dumpString(context: Context): String {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        val sb = StringBuilder("=====> $TARGET_SERVICE raw dump\n")
        sb.append(dump)
        sb.append("\n\n")
        sb.append("=====> $TARGET_SERVICE processed dump\n")
        sb.append(process(context, dump ?: ""))
        sb.append("\n\n")
        sb.append(AudioFlingerServiceDumpUtils.dumpString(context))

        return sb.toString()
    }

    companion object {
        const val TARGET_SERVICE = "audio"
    }
}
