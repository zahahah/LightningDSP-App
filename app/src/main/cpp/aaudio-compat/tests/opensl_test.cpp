// Copyright (C) 2026 LightningDSP contributors
// SPDX-License-Identifier: Apache-2.0
#include "../opensl_compat.h"
#include <cassert>
#include <cstdio>
using namespace ldsp::opensl;
static SLInterfaceID_ iidEngine{}, iidObject{}, iidConfig{};
extern "C" {
const SLInterfaceID SL_IID_ENGINE = &iidEngine;
const SLInterfaceID SL_IID_OBJECT = &iidObject;
const SLInterfaceID SL_IID_ANDROIDCONFIGURATION = &iidConfig;
}
static SLObjectItf_ objectFunctions{}, playerFunctions{};
static SLEngineItf_ engineFunctions{};
static SLAndroidConfigurationItf_ configFunctions{};
static const SLObjectItf_* realObject = &objectFunctions;
static const SLObjectItf_* realPlayer = &playerFunctions;
static const SLEngineItf_* realEngine = &engineFunctions;
static const SLAndroidConfigurationItf_* realConfig = &configFunctions;
static bool optIn = true, failAmended = false, dirtyFailure = false, noConfig = false;
static int createCalls = 0, configCalls = 0, recorderCalls = 0, forwarded = 0;
static bool setterFails = false, latencyEffectsUnsupported = false, successNull = false, nullConfiguration = false;
static SLuint32 lastCount = 0, performance = SL_ANDROID_PERFORMANCE_LATENCY;
static slObjectCallback registered = nullptr;
static void* registeredContext = nullptr;
static SLObjectItf callbackSelf = nullptr;
static const void* callbackContext = nullptr;
static SLresult fakeCreateEngine(SLObjectItf* out, SLuint32, const SLEngineOption*, SLuint32, const SLInterfaceID*, const SLboolean*) {
    *out = &realObject; return SL_RESULT_SUCCESS;
}
static SLresult fakeInterface(SLObjectItf self, SLInterfaceID id, void* out) {
    if (self == &realPlayer && iidEqual(id, SL_IID_ANDROIDCONFIGURATION)) {
        if (noConfig) return SL_RESULT_FEATURE_UNSUPPORTED;
        *static_cast<SLAndroidConfigurationItf*>(out) = nullConfiguration ? nullptr : &realConfig;
    } else if (self == &realObject && iidEqual(id, SL_IID_ENGINE)) {
        *static_cast<SLEngineItf*>(out) = &realEngine;
    } else if (self == &realObject && iidEqual(id, SL_IID_OBJECT)) {
        *static_cast<SLObjectItf*>(out) = &realObject;
    } else { *static_cast<void**>(out) = reinterpret_cast<void*>(0x1234); }
    return SL_RESULT_SUCCESS;
}
static SLresult fakeRegister(SLObjectItf self, slObjectCallback fn, void* ctx) {
    assert(self == &realObject); registered = fn; registeredContext = ctx; return SL_RESULT_SUCCESS;
}
static void fakeDestroy(SLObjectItf self) {
    assert(self == &realObject);
    if (registered) registered(self, registeredContext, 0, SL_RESULT_SUCCESS, 0, nullptr);
}
static SLresult fakeRealize(SLObjectItf self, SLboolean async) {
    assert(self == &realObject); assert(async == SL_BOOLEAN_FALSE); return SL_RESULT_SUCCESS;
}
static SLresult fakePlayer(SLEngineItf self, SLObjectItf* out, SLDataSource*, SLDataSink*, SLuint32 count,
        const SLInterfaceID* ids, const SLboolean* requirements) {
    assert(self == &realEngine); ++createCalls; lastCount = count;
    if (count) { assert(iidEqual(ids[count - 1], SL_IID_ANDROIDCONFIGURATION)); assert(requirements[count - 1] == SL_BOOLEAN_FALSE); }
    if (failAmended && count) { *out = dirtyFailure ? &realPlayer : nullptr; return SL_RESULT_FEATURE_UNSUPPORTED; }
    *out = successNull ? nullptr : &realPlayer; return SL_RESULT_SUCCESS;
}
static SLresult fakeRecorder(SLEngineItf self, SLObjectItf* out, SLDataSource*, SLDataSink*, SLuint32, const SLInterfaceID*, const SLboolean*) {
    assert(self == &realEngine); ++recorderCalls; *out = &realPlayer; return SL_RESULT_SUCCESS;
}
static SLresult fakeSet(SLAndroidConfigurationItf self, const SLchar* key, const void* value, SLuint32 size) {
    assert(self == &realConfig); assert(!std::strcmp(reinterpret_cast<const char*>(key), reinterpret_cast<const char*>(SL_ANDROID_KEY_PERFORMANCE_MODE))); assert(size == sizeof(SLuint32));
    ++configCalls;
    const auto requestedPerformance = *static_cast<const SLuint32*>(value);
    if (setterFails || (latencyEffectsUnsupported && requestedPerformance == SL_ANDROID_PERFORMANCE_LATENCY_EFFECTS))
        return SL_RESULT_FEATURE_UNSUPPORTED;
    performance = requestedPerformance; return SL_RESULT_SUCCESS;
}
static void userCallback(SLObjectItf self, const void* context, SLuint32, SLresult, SLuint32, void*) {
    callbackSelf = self; callbackContext = context;
}
int main() {
    iidEngine.time_low = 1; iidObject.time_low = 2; iidConfig.time_low = 3;
    objectFunctions.Realize = fakeRealize; objectFunctions.GetInterface = fakeInterface;
    objectFunctions.RegisterCallback = fakeRegister; objectFunctions.Destroy = fakeDestroy;
    playerFunctions.GetInterface = fakeInterface; engineFunctions.CreateAudioPlayer = fakePlayer;
    engineFunctions.CreateAudioRecorder = fakeRecorder; configFunctions.SetConfiguration = fakeSet;
    originalCreate = fakeCreateEngine; requested = [] { return optIn; };
    SLObjectItf wrapped = nullptr;
    optIn = false; assert(createEngine(&wrapped, 0, nullptr, 0, nullptr, nullptr) == SL_RESULT_SUCCESS); assert(wrapped == &realObject);
    optIn = true; createEngine(&wrapped, 0, nullptr, 0, nullptr, nullptr); assert(wrapped != &realObject);
    assert((*wrapped)->Realize(wrapped, SL_BOOLEAN_FALSE) == SL_RESULT_SUCCESS);
    SLEngineItf e = nullptr; SLInterfaceID_ copiedId = iidEngine;
    (*wrapped)->GetInterface(wrapped, &copiedId, &e); assert(e && e != &realEngine);
    SLObjectItf identity = nullptr; (*wrapped)->GetInterface(wrapped, SL_IID_OBJECT, &identity); assert(identity == wrapped);
    void* unknown = nullptr; SLInterfaceID_ other{}; other.time_low = 9;
    (*wrapped)->GetInterface(wrapped, &other, &unknown); assert(unknown == reinterpret_cast<void*>(0x1234));
    SLDataLocator_AndroidSimpleBufferQueue queue{SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM pcm{SL_DATAFORMAT_PCM, 2, 48000000, 16, 16, SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT, SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource source{&queue, &pcm}; SLDataLocator_OutputMix mix{SL_DATALOCATOR_OUTPUTMIX, nullptr}; SLDataSink sink{&mix, nullptr};
    SLObjectItf player = nullptr;
    auto call = [&] { createCalls = configCalls = 0; return (*e)->CreateAudioPlayer(e, &player, &source, &sink, 0, nullptr, nullptr); };
    assert(call() == SL_RESULT_SUCCESS && createCalls == 1 && configCalls == 1 && lastCount == 1);
    assert(player == &realPlayer && performance == SL_ANDROID_PERFORMANCE_LATENCY_EFFECTS);
    preferNoPerformanceRequirement = true;
    assert(call() == SL_RESULT_SUCCESS && configCalls == 1 && performance == SL_ANDROID_PERFORMANCE_NONE);
    setterFails = true; assert(call() == SL_RESULT_SUCCESS && configCalls == 1); setterFails = false;
    preferNoPerformanceRequirement = false;
    // The real player/config interface remains intact: caller can explicitly override later.
    const SLuint32 latency = SL_ANDROID_PERFORMANCE_LATENCY;
    realConfig->SetConfiguration(&realConfig, SL_ANDROID_KEY_PERFORMANCE_MODE, &latency, sizeof(latency)); assert(performance == latency);
    const SLInterfaceID ids[]{&iidConfig}; const SLboolean optional[]{SL_BOOLEAN_FALSE};
    (*e)->CreateAudioPlayer(e, &player, &source, &sink, 1, ids, optional); assert(lastCount == 1);
    optIn = false; assert(call() == SL_RESULT_SUCCESS && lastCount == 0 && configCalls == 0); optIn = true;
    pcm.numChannels = 6; assert(call() == SL_RESULT_SUCCESS && lastCount == 0 && configCalls == 0); pcm.numChannels = 2;
    queue.locatorType = SL_DATALOCATOR_URI; assert(call() == SL_RESULT_SUCCESS && lastCount == 0 && configCalls == 0); queue.locatorType = SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE;
    pcm.formatType = SL_DATAFORMAT_MIME; assert(call() == SL_RESULT_SUCCESS && lastCount == 0 && configCalls == 0); pcm.formatType = SL_DATAFORMAT_PCM;
    (*e)->CreateAudioRecorder(e, &player, &source, &sink, 0, nullptr, nullptr); assert(recorderCalls == 1);
    failAmended = true; assert(call() == SL_RESULT_SUCCESS && createCalls == 2 && configCalls == 0);
    dirtyFailure = true; assert(call() == SL_RESULT_FEATURE_UNSUPPORTED && createCalls == 1 && player == &realPlayer);
    failAmended = dirtyFailure = false; noConfig = true; assert(call() == SL_RESULT_SUCCESS && configCalls == 0); noConfig = false;
    latencyEffectsUnsupported = true;
    assert(call() == SL_RESULT_SUCCESS && configCalls == 2 && player == &realPlayer);
    assert(performance == SL_ANDROID_PERFORMANCE_NONE);
    latencyEffectsUnsupported = false;
    setterFails = true; assert(call() == SL_RESULT_SUCCESS && configCalls == 2 && player == &realPlayer); setterFails = false;
    successNull = true; assert(call() == SL_RESULT_SUCCESS && configCalls == 0 && player == nullptr); successNull = false;
    nullConfiguration = true; assert(call() == SL_RESULT_SUCCESS && configCalls == 0 && player == &realPlayer); nullConfiguration = false;
    objectFunctions.Resume = [](SLObjectItf self, auto...) -> SLresult { assert(self == &realObject); ++forwarded; return SL_RESULT_SUCCESS; };
    objectFunctions.GetState = [](SLObjectItf self, auto...) -> SLresult { assert(self == &realObject); ++forwarded; return SL_RESULT_SUCCESS; };
    objectFunctions.AbortAsyncOperation = [](SLObjectItf self) { assert(self == &realObject); ++forwarded; };
    objectFunctions.SetPriority = [](SLObjectItf self, auto...) -> SLresult { assert(self == &realObject); ++forwarded; return SL_RESULT_SUCCESS; };
    objectFunctions.GetPriority = [](SLObjectItf self, auto...) -> SLresult { assert(self == &realObject); ++forwarded; return SL_RESULT_SUCCESS; };
    objectFunctions.SetLossOfControlInterfaces = [](SLObjectItf self, auto...) -> SLresult { assert(self == &realObject); ++forwarded; return SL_RESULT_SUCCESS; };
    (*wrapped)->Resume(wrapped, SL_BOOLEAN_FALSE); (*wrapped)->GetState(wrapped, nullptr);
    (*wrapped)->AbortAsyncOperation(wrapped); (*wrapped)->SetPriority(wrapped, 0, SL_BOOLEAN_FALSE);
    (*wrapped)->GetPriority(wrapped, nullptr, nullptr); (*wrapped)->SetLossOfControlInterfaces(wrapped, 0, nullptr, SL_BOOLEAN_FALSE);
    assert(forwarded == 6);
    engineFunctions.CreateOutputMix = [](SLEngineItf self, auto...) -> SLresult { assert(self == &realEngine); ++forwarded; return SL_RESULT_SUCCESS; };
    engineFunctions.QuerySupportedInterfaces = [](SLEngineItf self, auto...) -> SLresult { assert(self == &realEngine); ++forwarded; return SL_RESULT_SUCCESS; };
    engineFunctions.IsExtensionSupported = [](SLEngineItf self, auto...) -> SLresult { assert(self == &realEngine); ++forwarded; return SL_RESULT_SUCCESS; };
    (*e)->CreateOutputMix(e, &player, 0, nullptr, nullptr); (*e)->QuerySupportedInterfaces(e, 0, 0, nullptr);
    (*e)->IsExtensionSupported(e, nullptr, nullptr); assert(forwarded == 9);
    int context = 42; (*wrapped)->RegisterCallback(wrapped, userCallback, &context);
    registered(&realObject, registeredContext, 0, SL_RESULT_SUCCESS, 0, nullptr); assert(callbackSelf == wrapped && callbackContext == &context);
    (*wrapped)->Destroy(wrapped); assert(callbackSelf == wrapped && callbackContext == &context);
    for (size_t i = 1; i < proxies.size(); ++i) { SLObjectItf next; createEngine(&next, 0, nullptr, 0, nullptr, nullptr); assert(next != &realObject && next != wrapped); }
    createEngine(&identity, 0, nullptr, 0, nullptr, nullptr); assert(identity == &realObject);
    std::puts("OpenSL opt-in, forwarding, callback identity, lifetime and fallback tests passed");
}
