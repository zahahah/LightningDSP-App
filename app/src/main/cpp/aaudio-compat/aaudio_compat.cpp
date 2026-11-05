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
#include "opensl_compat.h"
#endif
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unistd.h>
#include <cstdio>
#include <type_traits>
#include <dlfcn.h>
#if defined(__aarch64__) || defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
#include "got_rebind.h"
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
#include <link.h>
#endif
#endif

namespace {
constexpr char kProperty[] = "debug.ldsp.aaudio_compat";
using HookFunction = int (*)(void*, void*, void**);
using LoadedCallback = void (*)(const char*, void*);
struct NativeApi { uint32_t version; HookFunction hook; int (*unhook)(void*); };
std::atomic<bool> ready{false};
std::atomic<bool> observerReady{false};
[[maybe_unused]] std::atomic<bool> unsafeFailure{false};
[[maybe_unused]] std::atomic<bool> initStarted{false};
std::atomic<unsigned> openDiagnostics{0};
std::mutex buildersMutex;
struct BuilderState {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    aaudio_performance_mode_t performance = AAUDIO_PERFORMANCE_MODE_NONE;
    bool directionObserved = false;
    bool performanceObserved = false;
};
// Bounded bookkeeping: unknown builders retain the original behavior.
std::array<BuilderState, 128> builders{};
decltype(&AAudio_createStreamBuilder) originalCreate = nullptr;
decltype(&AAudioStreamBuilder_setDirection) originalDirection = nullptr;
decltype(&AAudioStreamBuilder_setPerformanceMode) originalPerformance = nullptr;
decltype(&AAudioStreamBuilder_openStream) originalOpen = nullptr;
decltype(&AAudioStreamBuilder_delete) originalDelete = nullptr;
using SymbolResolver = void* (*)(void*, const char*);
[[maybe_unused]] SymbolResolver originalResolve = nullptr;

bool requested() {
    char value[PROP_VALUE_MAX]{};
    return __system_property_get(kProperty, value) == 1 && value[0] == '1';
}
BuilderState* findBuilder(AAudioStreamBuilder* builder) {
    for (auto& item : builders) if (item.builder == builder) return &item;
    return nullptr;
}
[[maybe_unused]] aaudio_result_t createBuilder(AAudioStreamBuilder** result) {
    const auto status = originalCreate(result);
    if (status == AAUDIO_OK && result && *result && ready.load(std::memory_order_acquire) &&
        !unsafeFailure.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lock(buildersMutex);
        // A destroy call through an unhooked DSO may leave a stale record. A new
        // builder at that address must never inherit the old setter observations.
        if (auto* state = findBuilder(*result)) *state = {*result};
        else for (auto& item : builders) if (!item.builder) { item = {*result}; break; }
    }
    return status;
}
[[maybe_unused]] void setDirection(AAudioStreamBuilder* builder, aaudio_direction_t direction) {
    originalDirection(builder, direction);
    std::lock_guard<std::mutex> lock(buildersMutex);
    if (auto* state = findBuilder(builder)) { state->direction = direction; state->directionObserved = true; }
}
[[maybe_unused]] void setPerformance(AAudioStreamBuilder* builder, aaudio_performance_mode_t mode) {
    originalPerformance(builder, mode);
    std::lock_guard<std::mutex> lock(buildersMutex);
    if (auto* state = findBuilder(builder)) { state->performance = mode; state->performanceObserved = true; }
}
[[maybe_unused]] aaudio_result_t deleteBuilder(AAudioStreamBuilder* builder) {
    // AAudio builders may not be concurrently used/deleted by the caller.
    const auto status = originalDelete(builder);
    if (status == AAUDIO_OK) {
        std::lock_guard<std::mutex> lock(buildersMutex);
        if (auto* state = findBuilder(builder)) *state = {};
    }
    return status;
}
[[maybe_unused]] aaudio_result_t openStream(AAudioStreamBuilder* builder, AAudioStream** stream) {
    BuilderState state;
    {
        std::lock_guard<std::mutex> lock(buildersMutex);
        if (auto* found = findBuilder(builder)) state = *found;
    }
    const bool optIn = requested();
    const bool diagnostic = optIn && openDiagnostics.fetch_add(1, std::memory_order_relaxed) < 12;
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio",
        "open enter pid=%d tracked=%d direction=%d mode=%d ready=%d", getpid(),
        state.builder != nullptr, static_cast<int>(state.direction), static_cast<int>(state.performance),
        ready.load(std::memory_order_acquire));
    if (!stream || !ready.load(std::memory_order_acquire) || unsafeFailure.load(std::memory_order_acquire) || !optIn ||
        !state.builder || !state.directionObserved || !state.performanceObserved || state.direction != AAUDIO_DIRECTION_OUTPUT ||
        state.performance != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
        const auto status = originalOpen(builder, stream);
        if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio",
            "open original returned=%d pid=%d", status, getpid());
        return status;
    }
    // The builder is restored even on failure. A clean failure gets exactly one
    // retry using the application's original request; no routing/restart loop.
    originalPerformance(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream* candidate = nullptr;
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "open normal enter pid=%d", getpid());
    const auto status = originalOpen(builder, &candidate);
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "open normal returned=%d pid=%d", status, getpid());
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

// Oboe resolves AAudio through an explicit dlopen handle instead of ELF imports.
// Rebind only its app-local dlsym slot. Never intercept libdl itself. The special
// handles use caller-relative lookup in Bionic, so a guaranteed tail call must
// preserve the original return address rather than calling from our namespace.
[[maybe_unused]] void* resolveOboeSymbol(void* handle, const char* symbol) {
    if (handle == RTLD_DEFAULT || handle == RTLD_NEXT) {
        [[clang::musttail]] return originalResolve(handle, symbol);
    }
    // Resolve first and keep the linker's dlerror state intact. Concrete handles
    // use handle-relative lookup; do not perform extra linker queries or logging.
    void* resolved = originalResolve(handle, symbol);
    // The GOT slot becomes visible before libraryLoaded publishes ready. Oboe
    // caches the returned pointers, so use the initialized observer gate here;
    // create/open wrappers still pass through until ready is published.
    if (!resolved || !symbol || !requested() || !observerReady.load(std::memory_order_acquire) ||
        unsafeFailure.load(std::memory_order_acquire)) return resolved;
    struct Mapping { const char* name; void* original; void* replacement; };
    const std::array<Mapping, 5> mappings{{
        {"AAudio_createStreamBuilder", reinterpret_cast<void*>(originalCreate), reinterpret_cast<void*>(createBuilder)},
        {"AAudioStreamBuilder_setDirection", reinterpret_cast<void*>(originalDirection), reinterpret_cast<void*>(setDirection)},
        {"AAudioStreamBuilder_setPerformanceMode", reinterpret_cast<void*>(originalPerformance), reinterpret_cast<void*>(setPerformance)},
        {"AAudioStreamBuilder_openStream", reinterpret_cast<void*>(originalOpen), reinterpret_cast<void*>(openStream)},
        {"AAudioStreamBuilder_delete", reinterpret_cast<void*>(originalDelete), reinterpret_cast<void*>(deleteBuilder)},
    }};
    for (const auto& mapping : mappings) {
        if (resolved == mapping.original && !std::strcmp(symbol, mapping.name)) return mapping.replacement;
    }
    return resolved;
}

[[maybe_unused]] bool allowedProcess() {
#ifdef LDSP_AAUDIO_COMPAT_HOST_TEST
    return true;
#else
    const auto appId = getuid() % 100000;
    if (appId < 10000 || appId >= 20000) return false;
    return true; // Java entry additionally rejects framework/module/system apps.
#endif
}
}

// Rebind only a complete, eager-resolved import set in a user app library.
// No instruction bytes or system libraries are ever modified.
#if defined(__aarch64__) || defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
namespace {
bool eligibleLibrary(const char* path) {
    if (!path || std::strstr(path, "libldsp_aaudio_compat.so") || std::strstr(path, "/com.lightning.dsp-")) return false;
    const bool appPath = !std::strncmp(path, "/data/app/", 10) || !std::strncmp(path, "/data/user/", 11) ||
        !std::strncmp(path, "/data/user_de/", 14) || !std::strncmp(path, "/data/data/", 11) ||
        !std::strncmp(path, "/mnt/expand/", 12);
    const size_t length = std::strlen(path);
    return appPath && length > 3 && !std::strcmp(path + length - 3, ".so");
}
[[maybe_unused]] bool eligibleOboeLibrary(const char* path) {
    if (!eligibleLibrary(path)) return false;
    const char* basename = std::strrchr(path, '/');
    return basename && !std::strcmp(basename + 1, "liboboe.so");
}
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
std::mutex rebindMutex;
bool protectPage(void*, uintptr_t page, size_t size, int protection) {
    return mprotect(reinterpret_cast<void*>(page), size, protection) == 0;
}
bool pageProtection(uintptr_t page, size_t size, int& protection) {
    FILE* maps = std::fopen("/proc/self/maps", "re");
    if (!maps) return false;
    char line[1024]; bool found = false;
    while (std::fgets(line, sizeof(line), maps)) {
        unsigned long begin, end; char flags[5]{};
        if (std::sscanf(line, "%lx-%lx %4s", &begin, &end, flags) != 3) continue;
        if (page < begin || page > UINTPTR_MAX - size || page + size > end) continue;
        // Reject executable, shared, tagged, or otherwise unexpected protections.
        if (!std::strcmp(flags, "r--p")) { protection = PROT_READ; found = true; }
        else if (!std::strcmp(flags, "rw-p")) { protection = PROT_READ | PROT_WRITE; found = true; }
        break;
    }
    std::fclose(maps); return found;
}
std::atomic<unsigned> rejectionDiagnostics{0};
void reportRejection(const char* reason) {
    if (rejectionDiagnostics.fetch_add(1, std::memory_order_relaxed) < 12)
        __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio", "app imports skipped: %s", reason);
}
struct Lookup {
    const char* name;
    unsigned matches = 0;
    ldsp::got::Result result = ldsp::got::Result::Rejected;
    const char* reason = "image unavailable";
};
int countLibrary(dl_phdr_info* info, size_t, void* opaque) {
    auto& lookup = *static_cast<Lookup*>(opaque);
    if (info->dlpi_name && !std::strcmp(info->dlpi_name, lookup.name)) ++lookup.matches;
    return 0;
}
int inspectLibrary(dl_phdr_info* info, size_t, void* opaque) {
    auto& lookup = *static_cast<Lookup*>(opaque);
    if (!info->dlpi_name || std::strcmp(info->dlpi_name, lookup.name)) return 0;
    lookup.reason = "unsupported image layout";
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize < 4096) return 1;
    const std::array<ldsp::got::Target, 5> targets{{
        {"AAudio_createStreamBuilder", reinterpret_cast<uintptr_t>(originalCreate), reinterpret_cast<uintptr_t>(createBuilder)},
        {"AAudioStreamBuilder_setDirection", reinterpret_cast<uintptr_t>(originalDirection), reinterpret_cast<uintptr_t>(setDirection)},
        {"AAudioStreamBuilder_setPerformanceMode", reinterpret_cast<uintptr_t>(originalPerformance), reinterpret_cast<uintptr_t>(setPerformance)},
        {"AAudioStreamBuilder_openStream", reinterpret_cast<uintptr_t>(originalOpen), reinterpret_cast<uintptr_t>(openStream)},
        {"AAudioStreamBuilder_delete", reinterpret_cast<uintptr_t>(originalDelete), reinterpret_cast<uintptr_t>(deleteBuilder)},
    }};
    const auto applyImports = [&](const auto& imports, const char* backend) {
        constexpr size_t count = std::tuple_size<std::decay_t<decltype(imports)>>::value;
        ldsp::got::BasicPlan<count> plan;
        if (!ldsp::got::prepare({info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum}, imports,
                static_cast<size_t>(pageSize), plan)) return;
        int protection = 0;
        if (!pageProtection(plan.page, plan.pageSize, protection) || protection != plan.expectedProtection) {
            lookup.reason = "unexpected import page protection";
            return;
        }
        const auto result = ldsp::got::apply(plan, protection, protectPage, nullptr);
        if (result == ldsp::got::Result::Unsafe || result == ldsp::got::Result::Applied ||
                result == ldsp::got::Result::AlreadyApplied || lookup.result == ldsp::got::Result::Rejected)
            lookup.result = result;
        lookup.reason = "import transaction rejected or rolled back";
        if (result == ldsp::got::Result::Applied)
            __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "%s imports ready pid=%d", backend, getpid());
    };
    applyImports(targets, "AAudio");
    if (lookup.result == ldsp::got::Result::Unsafe) return 1;
    // Independent one-symbol transaction, also considered in mixed-backend DSOs.
    const std::array<ldsp::got::Target, 1> openSlTargets{{
        {"slCreateEngine", reinterpret_cast<uintptr_t>(ldsp::opensl::originalCreate),
            reinterpret_cast<uintptr_t>(ldsp::opensl::createEngine)}
    }};
    applyImports(openSlTargets, "OpenSL");
    if (lookup.result == ldsp::got::Result::Unsafe) return 1;
    if (eligibleOboeLibrary(info->dlpi_name)) {
        const std::array<ldsp::got::Target, 1> resolverTargets{{
            {"dlsym", reinterpret_cast<uintptr_t>(originalResolve), reinterpret_cast<uintptr_t>(resolveOboeSymbol)}
        }};
        applyImports(resolverTargets, "Oboe dynamic AAudio");
    }
    return 1;
}
#endif
void libraryLoaded(const char* name, void*) {
    if (!requested() || unsafeFailure.load(std::memory_order_acquire) || !eligibleLibrary(name)) return;
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
    std::lock_guard<std::mutex> lock(rebindMutex);
    Lookup lookup{name};
    // Count without recursive linker enumeration. Reject known ambiguous names.
    // The second enumeration independently validates the current image under the
    // linker lock; there is intentionally no cached image pointer across passes.
    dl_iterate_phdr(countLibrary, &lookup);
    if (lookup.matches != 1) {
        reportRejection(lookup.matches ? "ambiguous image" : "image unavailable");
        return;
    }
    dl_iterate_phdr(inspectLibrary, &lookup);
    if (lookup.result == ldsp::got::Result::Applied || lookup.result == ldsp::got::Result::AlreadyApplied) {
        ready.store(true, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "app imports ready pid=%d", getpid());
    } else if (lookup.result == ldsp::got::Result::Unsafe) {
        unsafeFailure.store(true, std::memory_order_release);
        ready.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio", "import transaction unsafe; compatibility disabled pid=%d", getpid());
    } else {
        reportRejection(lookup.reason);
    }
#endif
}
}
#endif

// Avoid inline patches on short AAudio entry points.
extern "C" __attribute__((visibility("default"), used))
LoadedCallback native_init(const NativeApi* api) {
#if defined(__aarch64__) || defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
    if (!allowedProcess() || !api || (api->version != 1 && api->version != 2) || !requested()) return nullptr;
    if (initStarted.exchange(true, std::memory_order_acq_rel)) return nullptr;
    originalCreate = AAudio_createStreamBuilder;
    originalDirection = AAudioStreamBuilder_setDirection;
    originalPerformance = AAudioStreamBuilder_setPerformanceMode;
    originalOpen = AAudioStreamBuilder_openStream;
    originalDelete = AAudioStreamBuilder_delete;
    originalResolve = dlsym;
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
    ldsp::opensl::originalCreate = slCreateEngine;
    ldsp::opensl::requested = [] { return requested() && !unsafeFailure.load(std::memory_order_acquire); };
    ldsp::opensl::report = [](const char* message, SLresult result) {
        static std::atomic<unsigned> reports{0};
        if (reports.fetch_add(1, std::memory_order_relaxed) >= 12) return;
        __android_log_print(ANDROID_LOG_INFO, "LightningDSP_OpenSL", "%s result=%u pid=%d", message, result, getpid());
    };
#endif
    observerReady.store(true, std::memory_order_release);
    return libraryLoaded;
#else
    (void)api;
    return nullptr;
#endif
}
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
extern "C" JNIEXPORT jboolean JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeReady(JNIEnv*, jclass) {
    return ready.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeObserverReady(JNIEnv*, jclass) {
    return observerReady.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
#endif
