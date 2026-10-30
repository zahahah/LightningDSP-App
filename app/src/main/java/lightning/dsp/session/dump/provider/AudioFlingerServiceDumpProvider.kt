package lightning.dsp.session.dump.provider

import android.content.Context
import lightning.dsp.session.dump.data.ISessionInfoDump
import lightning.dsp.session.dump.utils.DumpUtils

class AudioFlingerServiceDumpProvider : ISessionDumpProvider {

    override fun dump(context: Context): ISessionInfoDump? {
        return null
    }

    override fun dumpString(context: Context): String {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        val sb = StringBuilder("=====> $TARGET_SERVICE raw dump\n")
        sb.append(dump)
        sb.append("\n\n")
        sb.append("=====> $TARGET_SERVICE processed dump\n")
        sb.append("Not implemented")

        return sb.toString()
    }

    companion object
    {
        const val TARGET_SERVICE = "media.audio_flinger"
    }
}
