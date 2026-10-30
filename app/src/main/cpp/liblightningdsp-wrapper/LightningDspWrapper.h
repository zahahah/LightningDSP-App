#ifndef DSPHOST_H
#define DSPHOST_H

#include <jni.h>

typedef struct
{
    void* dsp;
    JNIEnv* env;
    jobject callbackInterface;
    jmethodID callbackOnLiveprogOutput;
    jmethodID callbackOnLiveprogExec;
    jmethodID callbackOnLiveprogResult;
    jmethodID callbackOnVdcParseError;
} LightningDspWrapper;

/* C interop function */
static void receiveLiveprogStdOut(const char* buffer, void* userData);

#endif // DSPHOST_H
