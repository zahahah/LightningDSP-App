package lightning.dsp;

import lightning.dsp.ControlResult;

parcelable ProtocolInfo {
    ControlResult result;
    int protocolVersion = 1;
    long capabilities = 1;
    long processGeneration;
    String buildId;
    int contextCapacity = 64;
    long contextAttachmentFailures;
    boolean writable = false;
    boolean realtimeWriteHandoffReady = false;
    int publicationState;
    boolean administrativeOnly = true;
    boolean directAppAccessSupported = false;
}
