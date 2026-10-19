package lightning.dsp;

import lightning.dsp.ContextHandle;
import lightning.dsp.ContextList;
import lightning.dsp.ControlRequest;
import lightning.dsp.ControlResult;
import lightning.dsp.FirEq;
import lightning.dsp.ProtocolInfo;
import lightning.dsp.StatusResponse;

interface IJamesDspControl {
    const int PROTOCOL_VERSION = 1;
    const int PROTOCOL_VERSION_V2 = 2;
    const int PROTOCOL_VERSION_V3 = 3;
    const long CAP_STATUS = 1;
    const long CAP_ENABLE = 2;
    const long CAP_POST_GAIN = 4;
    const long CAP_FIR_EQ = 8;

    ProtocolInfo getProtocolInfo(int protocolVersion);
    ContextList listContexts(int protocolVersion);
    StatusResponse getStatus(int protocolVersion, in ContextHandle handle);
    ControlResult setEnabled(in ControlRequest request, boolean enabled);
    ControlResult setPostGain(in ControlRequest request, float gainDb);
    ControlResult setFirEq(in ControlRequest request, in FirEq eq);
}
