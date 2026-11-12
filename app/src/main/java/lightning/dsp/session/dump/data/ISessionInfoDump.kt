package lightning.dsp.session.dump.data

import lightning.dsp.model.AudioSessionDumpEntry

interface ISessionInfoDump : IDump {
    val sessions: HashMap<Int /* sid */, AudioSessionDumpEntry>
    val ownerRefs: Set<SessionOwnerRef>? get() = null
}

data class SessionOwnerRef(val sessionId: Int, val pid: Int, val uid: Int)
