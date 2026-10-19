package lightning.dsp;

import lightning.dsp.ErrorCode;

parcelable ControlResult {
    ErrorCode code = ErrorCode.OK;
    long acceptedGeneration;
    long appliedGeneration;
}
