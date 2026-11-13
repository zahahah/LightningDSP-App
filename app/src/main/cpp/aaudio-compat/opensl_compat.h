// Copyright (C) 2026 LightningDSP contributors
// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <array>
#include <atomic>
#include <cstring>

namespace ldsp::opensl {
using CreateEngine = decltype(&slCreateEngine);
using Requested = bool (*)();
using Report = void (*)(const char*, SLresult);
inline CreateEngine originalCreate = nullptr;
inline Requested requested = nullptr;
inline Report report = nullptr;
// Selected once at native initialization; all other apps retain existing behavior.
inline bool preferNoPerformanceRequirement = false;
inline bool enabled() { return requested && requested(); }
inline bool iidEqual(SLInterfaceID a, SLInterfaceID b) {
    return a && b && std::memcmp(a, b, sizeof(*a)) == 0;
}
struct Proxy;
struct EngineView { const SLEngineItf_* table = nullptr; Proxy* owner = nullptr; };
struct Proxy {
    const SLObjectItf_* table = nullptr;
    SLObjectItf real = nullptr;
    EngineView view;
    std::atomic<SLEngineItf> engine{nullptr};
};
// Never reuse/free proxy or callback storage: delayed platform object callbacks
// cannot observe a different engine or freed trampoline context. Exhaustion
// bypasses new engine wrapping; genuine player/recorder objects are never proxied.
inline std::array<Proxy, 32> proxies{};
inline std::atomic<size_t> proxyCount{0};
struct Callback { Proxy* owner = nullptr; slObjectCallback function = nullptr; void* context = nullptr; };
inline std::array<Callback, 1024> callbacks{};
inline std::atomic<size_t> callbackCount{0};
inline Proxy* object(SLObjectItf self) { return reinterpret_cast<Proxy*>(const_cast<SLObjectItf_**>(self)); }
inline Proxy* engine(SLEngineItf self) { return reinterpret_cast<EngineView*>(const_cast<SLEngineItf_**>(self))->owner; }
inline SLObjectItf handle(Proxy* p) { return reinterpret_cast<SLObjectItf>(&p->table); }
inline void callbackBridge(SLObjectItf, const void* context, SLuint32 event, SLresult result, SLuint32 param, void* data) {
    const auto* callback = static_cast<const Callback*>(context);
    callback->function(handle(callback->owner), callback->context, event, result, param, data);
}
inline SLresult registerCallback(SLObjectItf self, slObjectCallback callback, void* context) {
    auto* p = object(self);
    if (!callback) return (*p->real)->RegisterCallback(p->real, nullptr, context);
    const size_t index = callbackCount.fetch_add(1, std::memory_order_relaxed);
    if (index >= callbacks.size()) return SL_RESULT_RESOURCE_ERROR;
    auto* record = &callbacks[index];
    *record = {p, callback, context};
    return (*p->real)->RegisterCallback(p->real, callbackBridge, record);
}
inline SLresult createPlayer(SLEngineItf self, SLObjectItf* player, SLDataSource* source, SLDataSink* sink,
        SLuint32 count, const SLInterfaceID* ids, const SLboolean* required) {
    auto real = engine(self)->engine.load(std::memory_order_acquire);
    auto original = (*real)->CreateAudioPlayer;
    auto unchanged = [&] { return original(real, player, source, sink, count, ids, required); };
    if (!enabled() || !player || !source || !source->pLocator || !source->pFormat || !sink || !sink->pLocator ||
        count > 32 || (count && (!ids || !required))) return unchanged();
    const auto locator = *static_cast<const SLuint32*>(source->pLocator);
    const auto format = static_cast<const SLDataFormat_PCM*>(source->pFormat);
    if ((locator != SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE && locator != SL_DATALOCATOR_BUFFERQUEUE) ||
        format->formatType != SL_DATAFORMAT_PCM || (format->numChannels != 1 && format->numChannels != 2) ||
        *static_cast<const SLuint32*>(sink->pLocator) != SL_DATALOCATOR_OUTPUTMIX) return unchanged();
    std::array<SLInterfaceID, 33> interfaces{};
    std::array<SLboolean, 33> requirements{};
    bool hasConfiguration = false;
    for (size_t i = 0; i < count; ++i) {
        interfaces[i] = ids[i]; requirements[i] = required[i];
        hasConfiguration |= iidEqual(ids[i], SL_IID_ANDROIDCONFIGURATION);
    }
    SLuint32 amendedCount = count;
    if (!hasConfiguration) {
        interfaces[amendedCount] = SL_IID_ANDROIDCONFIGURATION;
        requirements[amendedCount++] = SL_BOOLEAN_FALSE;
    }
    SLObjectItf candidate = nullptr;
    const auto result = original(real, &candidate, source, sink, amendedCount, interfaces.data(), requirements.data());
    if (result != SL_RESULT_SUCCESS && !candidate) {
        if (report) report("optional configuration creation failed; retry original", result);
        return unchanged(); // Only a clean-null failure gets one unchanged retry.
    }
    *player = candidate;
    if (result == SL_RESULT_SUCCESS && candidate) {
        SLAndroidConfigurationItf configuration = nullptr;
        auto status = (*candidate)->GetInterface(candidate, SL_IID_ANDROIDCONFIGURATION, &configuration);
        if (status == SL_RESULT_SUCCESS && !configuration) status = SL_RESULT_FEATURE_UNSUPPORTED;
        SLuint32 performance = preferNoPerformanceRequirement ? SL_ANDROID_PERFORMANCE_NONE : SL_ANDROID_PERFORMANCE_LATENCY_EFFECTS;
        if (status == SL_RESULT_SUCCESS) {
            status = (*configuration)->SetConfiguration(configuration, SL_ANDROID_KEY_PERFORMANCE_MODE,
                &performance, sizeof(performance));
            if (status != SL_RESULT_SUCCESS && !preferNoPerformanceRequirement) {
                // Some older/vendor OpenSL implementations do not recognize the
                // latency-with-effects mode. Retry with NONE, which still permits
                // effects and is safer than leaving the default latency-only path.
                performance = SL_ANDROID_PERFORMANCE_NONE;
                status = (*configuration)->SetConfiguration(configuration,
                    SL_ANDROID_KEY_PERFORMANCE_MODE, &performance, sizeof(performance));
                if (report) report("OpenSL latency-effects unsupported; fallback to no performance requirement", status);
            } else if (report) {
                report(preferNoPerformanceRequirement ? "OpenSL no-performance request" : "OpenSL latency-effects request", status);
            }
        } else if (report) {
            report("OpenSL configuration unavailable", status);
        }
    }
    return result;
}
inline SLresult forwardObjectRealize(SLObjectItf self, SLboolean async) { auto* p = object(self); auto real = p->real; return (*real)->Realize(real, async); }
inline SLresult forwardObjectResume(SLObjectItf self, SLboolean async) { auto* p = object(self); auto real = p->real; return (*real)->Resume(real, async); }
inline SLresult forwardObjectGetState(SLObjectItf self, SLuint32 * pState) { auto* p = object(self); auto real = p->real; return (*real)->GetState(real, pState); }
inline void forwardObjectAbortAsyncOperation(SLObjectItf self) { auto* p = object(self); auto real = p->real; (*real)->AbortAsyncOperation(real); }
inline void forwardObjectDestroy(SLObjectItf self) { auto* p = object(self); auto real = p->real; (*real)->Destroy(real); }
inline SLresult forwardObjectSetPriority(SLObjectItf self, SLint32 priority, SLboolean preemptable) { auto* p = object(self); auto real = p->real; return (*real)->SetPriority(real, priority, preemptable); }
inline SLresult forwardObjectGetPriority(SLObjectItf self, SLint32 *pPriority, SLboolean *pPreemptable) { auto* p = object(self); auto real = p->real; return (*real)->GetPriority(real, pPriority, pPreemptable); }
inline SLresult forwardObjectSetLossOfControlInterfaces(SLObjectItf self, SLint16 numInterfaces, SLInterfaceID * pInterfaceIDs, SLboolean enabled) { auto* p = object(self); auto real = p->real; return (*real)->SetLossOfControlInterfaces(real, numInterfaces, pInterfaceIDs, enabled); }
inline SLresult forwardEngineCreateLEDDevice(SLEngineItf self, SLObjectItf * pDevice, SLuint32 deviceID, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateLEDDevice(real, pDevice, deviceID, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateVibraDevice(SLEngineItf self, SLObjectItf * pDevice, SLuint32 deviceID, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateVibraDevice(real, pDevice, deviceID, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateAudioRecorder(SLEngineItf self, SLObjectItf * pRecorder, SLDataSource *pAudioSrc, SLDataSink *pAudioSnk, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateAudioRecorder(real, pRecorder, pAudioSrc, pAudioSnk, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateMidiPlayer(SLEngineItf self, SLObjectItf * pPlayer, SLDataSource *pMIDISrc, SLDataSource *pBankSrc, SLDataSink *pAudioOutput, SLDataSink *pVibra, SLDataSink *pLEDArray, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateMidiPlayer(real, pPlayer, pMIDISrc, pBankSrc, pAudioOutput, pVibra, pLEDArray, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateListener(SLEngineItf self, SLObjectItf * pListener, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateListener(real, pListener, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreate3DGroup(SLEngineItf self, SLObjectItf * pGroup, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->Create3DGroup(real, pGroup, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateOutputMix(SLEngineItf self, SLObjectItf * pMix, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateOutputMix(real, pMix, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateMetadataExtractor(SLEngineItf self, SLObjectItf * pMetadataExtractor, SLDataSource * pDataSource, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateMetadataExtractor(real, pMetadataExtractor, pDataSource, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineCreateExtensionObject(SLEngineItf self, SLObjectItf * pObject, void * pParameters, SLuint32 objectID, SLuint32 numInterfaces, const SLInterfaceID * pInterfaceIds, const SLboolean * pInterfaceRequired) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->CreateExtensionObject(real, pObject, pParameters, objectID, numInterfaces, pInterfaceIds, pInterfaceRequired); }
inline SLresult forwardEngineQueryNumSupportedInterfaces(SLEngineItf self, SLuint32 objectID, SLuint32 * pNumSupportedInterfaces) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->QueryNumSupportedInterfaces(real, objectID, pNumSupportedInterfaces); }
inline SLresult forwardEngineQuerySupportedInterfaces(SLEngineItf self, SLuint32 objectID, SLuint32 index, SLInterfaceID * pInterfaceId) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->QuerySupportedInterfaces(real, objectID, index, pInterfaceId); }
inline SLresult forwardEngineQueryNumSupportedExtensions(SLEngineItf self, SLuint32 * pNumExtensions) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->QueryNumSupportedExtensions(real, pNumExtensions); }
inline SLresult forwardEngineQuerySupportedExtension(SLEngineItf self, SLuint32 index, SLchar * pExtensionName, SLint16 * pNameLength) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->QuerySupportedExtension(real, index, pExtensionName, pNameLength); }
inline SLresult forwardEngineIsExtensionSupported(SLEngineItf self, const SLchar * pExtensionName, SLboolean * pSupported) { auto real = engine(self)->engine.load(std::memory_order_acquire); return (*real)->IsExtensionSupported(real, pExtensionName, pSupported); }
extern const SLEngineItf_ engineTable;
inline SLresult getInterface(SLObjectItf self, const SLInterfaceID iid, void* result) {
    auto* p = object(self);
    const auto status = (*p->real)->GetInterface(p->real, iid, result);
    if (status != SL_RESULT_SUCCESS || !result) return status;
    if (iidEqual(iid, SL_IID_ENGINE)) {
        auto real = *static_cast<SLEngineItf*>(result);
        if (real) { p->engine.store(real, std::memory_order_release); *static_cast<SLEngineItf*>(result) = reinterpret_cast<SLEngineItf>(&p->view.table); }
    } else if (iidEqual(iid, SL_IID_OBJECT)) {
        *static_cast<SLObjectItf*>(result) = self;
    }
    return status;
}
inline const SLObjectItf_ objectTable = {forwardObjectRealize, forwardObjectResume, forwardObjectGetState, getInterface, registerCallback, forwardObjectAbortAsyncOperation, forwardObjectDestroy, forwardObjectSetPriority, forwardObjectGetPriority, forwardObjectSetLossOfControlInterfaces};
inline const SLEngineItf_ engineTable = {forwardEngineCreateLEDDevice, forwardEngineCreateVibraDevice, createPlayer, forwardEngineCreateAudioRecorder, forwardEngineCreateMidiPlayer, forwardEngineCreateListener, forwardEngineCreate3DGroup, forwardEngineCreateOutputMix, forwardEngineCreateMetadataExtractor, forwardEngineCreateExtensionObject, forwardEngineQueryNumSupportedInterfaces, forwardEngineQuerySupportedInterfaces, forwardEngineQueryNumSupportedExtensions, forwardEngineQuerySupportedExtension, forwardEngineIsExtensionSupported};
inline SLresult createEngine(SLObjectItf* output, SLuint32 optionsCount, const SLEngineOption* options,
        SLuint32 count, const SLInterfaceID* ids, const SLboolean* required) {
    const auto status = originalCreate(output, optionsCount, options, count, ids, required);
    if (status != SL_RESULT_SUCCESS || !output || !*output || !enabled()) return status;
    const size_t index = proxyCount.fetch_add(1, std::memory_order_relaxed);
    if (index >= proxies.size()) return status;
    auto* proxy = &proxies[index];
    proxy->table = &objectTable; proxy->real = *output;
    proxy->view = {&engineTable, proxy};
    *output = handle(proxy);
    return status;
}
} // namespace ldsp::opensl
