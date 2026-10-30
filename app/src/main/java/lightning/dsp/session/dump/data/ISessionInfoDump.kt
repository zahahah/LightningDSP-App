package lightning.dsp.session.dump.data

import lightning.dsp.model.AudioSessionDumpEntry

interface ISessionInfoDump : IDump {
    val sessions: HashMap<Int /* sid */, AudioSessionDumpEntry>
}