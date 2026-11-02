// Copyright (C) 2026 LightningDSP contributors
// SPDX-License-Identifier: Apache-2.0
// Runs only at stream construction, never in the PCM callback.
#ifdef LDSP_AAUDIO_COMPAT_HOST_TEST
#include "tests/platform.h"
#else
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <sys/system_properties.h>
#endif
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unistd.h>

namespace {
constexpr char kProperty[] = "debug.ldsp.aaudio_compat";
using HookFunction = int (*)(void*, void*, void**);
using LoadedCallback = void (*)(const char*, void*);
struct NativeApi { uint32_t version; HookFunction hook; int (*unhook)(void*); };
std::atomic<bool> ready{false};
std::atomic<bool> initStarted{false};
std::mutex buildersMutex;
struct BuilderState {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    aaudio_performance_mode_t performance = AAUDIO_PERFORMANCE_MODE_NONE;
};
// Bounded bookkeeping: unknown builders retain the original behavior.
std::array<BuilderState, 128> builders{};
decltype(&AAudio_createStreamBuilder) originalCreate = nullptr;
decltype(&AAudioStreamBuilder_setDirection) originalDirection = nullptr;
decltype(&AAudioStreamBuilder_setPerformanceMode) originalPerformance = nullptr;
decltype(&AAudioStreamBuilder_openStream) originalOpen = nullptr;
decltype(&AAudioStreamBuilder_delete) originalDelete = nullptr;

bool requested() {
    char value[PROP_VALUE_MAX]{};
    return __system_property_get(kProperty, value) == 1 && value[0] == '1';
}
BuilderState* findBuilder(AAudioStreamBuilder* builder) {
    for (auto& item : builders) if (item.builder == builder) return &item;
    return nullptr;
}
aaudio_result_t createBuilder(AAudioStreamBuilder** result) {
    const auto status = originalCreate(result);
    if (status == AAUDIO_OK && result && *result && ready.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(buildersMutex);
        for (auto& item : builders) if (!item.builder) { item = {*result}; break; }
    }
    return status;
}
void setDirection(AAudioStreamBuilder* builder, aaudio_direction_t direction) {
    originalDirection(builder, direction);
    std::lock_guard<std::mutex> lock(buildersMutex);
    if (auto* state = findBuilder(builder)) state->direction = direction;
}
void setPerformance(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode) {
    originalPerformance(builder, mode);
    std::lock_guard<std::mutex> lock(buildersMutex);
    if (auto* state = findBuilder(builder)) state->performance = mode;
}
aaudio_result_t deleteBuilder(AAudioStreamBuilder* builder) {
    // AAudio builders may not be concurrently used/deleted by the caller.
    const auto status = originalDelete(builder);
    if (status == AAUDIO_OK) {
        std::lock_guard<std::mutex> lock(buildersMutex);
        if (auto* state = findBuilder(builder)) *state = {};
    }
    return status;
}
aaudio_result_t openStream(AAudioStreamBuilder* builder, AAudioStream** stream) {
    BuilderState state;
    {
        std::lock_guard<std::mutex> lock(buildersMutex);
        if (auto* found = findBuilder(builder)) state = *found;
    }
    if (!stream || !ready.load(std::memory_order_acquire) || !requested() ||
        !state.builder || state.direction != AAUDIO_DIRECTION_OUTPUT ||
        state.performance != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
        return originalOpen(builder, stream);
    }
    // The builder is restored even on failure. A clean failure gets exactly one
    // retry using the application's original request; no routing/restart loop.
    originalPerformance(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream* candidate = nullptr;
    const auto status = originalOpen(builder, &candidate);
    originalPerformance(builder, state.performance);
    if (status != AAUDIO_OK && candidate == nullptr) {
        const auto fallback = originalOpen(builder, stream);
        __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                            "normal request failed=%d; original retry=%d pid=%d", status, fallback, getpid());
        return fallback;
    }
    *stream = candidate;
    __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "normal request pid=%d result=%d", getpid(), status);
    return status;
}

bool allowedProcess() {
#ifdef LDSP_AAUDIO_COMPAT_HOST_TEST
    return true;
#else
    const auto appId = getuid() % 100000;
    if (appId < 10000 || appId >= 20000) return false;
    return true; // Java entry additionally rejects framework/module/system apps.
#endif
}
}

// Public native ABI documented by LSPosed/Vector Native Hook. No hook engine is bundled.
extern "C" __attribute__((visibility("default"), used))
LoadedCallback native_init(const NativeApi* api) {
    if (!allowedProcess() || !api || (api->version != 1 && api->version != 2) || !api->hook) return nullptr;
    if (initStarted.exchange(true, std::memory_order_acq_rel)) return nullptr;
    // Vector invokes native_init while holding its native-library registry mutex.
    // Calling dlopen here can re-enter that hook and deadlock. These references
    // are resolved through this library's DT_NEEDED on the public NDK AAudio ABI.
    struct Entry { void* function; void* replacement; void** original; };
    const Entry entries[] = {
        {reinterpret_cast<void*>(AAudio_createStreamBuilder), reinterpret_cast<void*>(createBuilder), reinterpret_cast<void**>(&originalCreate)},
        {reinterpret_cast<void*>(AAudioStreamBuilder_setDirection), reinterpret_cast<void*>(setDirection), reinterpret_cast<void**>(&originalDirection)},
        {reinterpret_cast<void*>(AAudioStreamBuilder_setPerformanceMode), reinterpret_cast<void*>(setPerformance), reinterpret_cast<void**>(&originalPerformance)},
        {reinterpret_cast<void*>(AAudioStreamBuilder_openStream), reinterpret_cast<void*>(openStream), reinterpret_cast<void**>(&originalOpen)},
        {reinterpret_cast<void*>(AAudioStreamBuilder_delete), reinterpret_cast<void*>(deleteBuilder), reinterpret_cast<void**>(&originalDelete)},
    };
    for (const auto& entry : entries) {
        if (api->hook(entry.function, entry.replacement, entry.original) != 0 || !*entry.original) {
            __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio", "hook incomplete pid=%d; original performance retained", getpid());
            return nullptr; // Partial hooks pass through while ready remains false.
        }
    }
    ready.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "native hook ready pid=%d", getpid());
    return nullptr;
}
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
extern "C" JNIEXPORT jboolean JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeReady(JNIEnv*, jclass) {
    return ready.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
#endif
