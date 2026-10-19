package lightning.dsp;

import lightning.dsp.ContextHandle;

parcelable ContextStatus {
    ContextHandle handle;
    int sampleRate;
    int channels;
    boolean enabled;
    boolean started;
    boolean startedKnown = false;
    boolean startCommandAccepted = false;
    boolean bypassKnown = false;
    boolean bypassed = false;
    long framesSeen;
    long framesProcessed;
    long acceptedGeneration;
    long appliedGeneration;
    long resetSequence;
    long legacyUpdateSequence;
    boolean appliedGenerationKnown = false;
    int lastError;
    float postGainDb;
    boolean postGainKnown = false;
    boolean legacyOnly = true;
    boolean sessionMetadataAvailable = false;
    boolean routeMetadataAvailable = false;
    float internalSampleRate;
    int blockFrames;
    long configurationGeneration;
    long rejectedGeneration;
    boolean firEqEnabledKnown = false;
    boolean firEqEnabled = false;
    int lastControlError;
}
