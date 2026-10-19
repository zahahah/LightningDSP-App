package lightning.dsp;

import lightning.dsp.ContextHandle;

parcelable ControlRequest {
    int protocolVersion = 1;
    ContextHandle handle;
    long expectedAcceptedGeneration = -1;
}
