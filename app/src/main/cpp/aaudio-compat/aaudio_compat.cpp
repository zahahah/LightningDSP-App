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
#include "../diagnostic_mode.h"
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unistd.h>
#include <cstdio>
#include <type_traits>
#include <dlfcn.h>
#include <string>
#include <vector>
#if defined(__aarch64__) || defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
#include "got_rebind.h"
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
#include <link.h>
#endif
#endif

namespace {
constexpr char kProperty[] = "debug.ldsp.aaudio_compat";
constexpr char kReuseSessionProperty[] = "debug.ldsp.aaudio_reuse_session";
using HookFunction = int (*)(void*, void*, void**);
using LoadedCallback = void (*)(const char*, void*);
struct NativeApi { uint32_t version; HookFunction hook; int (*unhook)(void*); };
std::atomic<bool> ready{false};
std::atomic<bool> aaudioReady{false};
std::atomic<bool> observerReady{false};
// Session reuse must not override an explicit app ID. If a direct session
// setter import exists but cannot be safely observed, fail closed for reuse.
std::atomic<bool> sessionReuseSafe{true};
[[maybe_unused]] std::atomic<bool> unsafeFailure{false};
[[maybe_unused]] std::atomic<bool> initStarted{false};
std::atomic<unsigned> openDiagnostics{0};
using SessionSetter = void (*)(AAudioStreamBuilder*, int32_t);
using SessionGetter = int32_t (*)(AAudioStream*);
SessionSetter originalSession = nullptr;
SessionGetter originalGetSession = nullptr;
std::mutex pinnedSessionMutex;
aaudio_session_id_t pinnedSession = AAUDIO_SESSION_ID_NONE;
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
std::mutex preparationRegistration;
std::atomic<bool> preparationReady{false};
JavaVM* preparationVm = nullptr;
jclass preparationClass = nullptr;
jmethodID preparationMethod = nullptr;
jmethodID retentionMethod = nullptr;
bool canPrepareStream() { return preparationReady.load(std::memory_order_acquire); }
void prepareOpenedStream(AAudioStream* stream, bool reusedSession) {
    if (!stream || !originalGetSession || !preparationReady.load(std::memory_order_acquire)) return;
    const auto id = originalGetSession(stream);
    if (id <= 0) return;
    JNIEnv* env = nullptr;
    const bool attach = preparationVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attach && preparationVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    if (!env) return;
    env->CallStaticVoidMethod(preparationClass, preparationMethod, static_cast<jint>(id), static_cast<jboolean>(reusedSession));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attach) preparationVm->DetachCurrentThread();
}
void renewOpenedStream(AAudioStream* stream) {
    if (!stream || !originalGetSession || !preparationReady.load(std::memory_order_acquire)) return;
    const auto id = originalGetSession(stream);
    if (id <= 0) return;
    JNIEnv* env = nullptr;
    const bool attach = preparationVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attach && preparationVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    if (!env) return;
    // This Java callback only schedules bounded background renewal, never provider IPC.
    env->CallStaticVoidMethod(preparationClass, retentionMethod, static_cast<jint>(id));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attach) preparationVm->DetachCurrentThread();
}

#else
bool canPrepareStream() { return true; }
unsigned preparationsForTest = 0;
bool preparedReuseForTest = false;
unsigned renewalsForTest = 0;
void renewOpenedStream(AAudioStream*) { ++renewalsForTest; }
void prepareOpenedStream(AAudioStream* stream, bool reusedSession) { if (originalGetSession && originalGetSession(stream) > 0) { ++preparationsForTest; preparedReuseForTest = reusedSession; } }
#endif
std::mutex buildersMutex;
struct BuilderState {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    aaudio_performance_mode_t performance = AAUDIO_PERFORMANCE_MODE_NONE;
    bool directionObserved = false;
    bool performanceObserved = false;
    aaudio_session_id_t session = AAUDIO_SESSION_ID_NONE;
    // AAudio specifies NONE when the application does not call setSessionId.
    // Treat that documented builder default as an observed request so the
    // opt-in reuse path also covers apps that leave the session unset.
    bool sessionObserved = true;
    // Keep the older pre-start allocation path limited to callers that invoked
    // setSessionId; only the separate reuse opt-in consumes the default above.
    bool sessionSetterObserved = false;
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
bool reuseSessionEnabled() {
    char value[PROP_VALUE_MAX]{};
    return __system_property_get(kReuseSessionProperty, value) == 1 && value[0] == '1';
}
bool hookRequested() { return requested() || reuseSessionEnabled(); }
bool reuseSessionRequested() {
    return reuseSessionEnabled() && sessionReuseSafe.load(std::memory_order_acquire);
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
[[maybe_unused]] void setSession(AAudioStreamBuilder* builder, aaudio_session_id_t session) {
    originalSession(builder, session);
    std::lock_guard<std::mutex> lock(buildersMutex);
    if (auto* state = findBuilder(builder)) {
        state->session = session;
        state->sessionObserved = true;
        state->sessionSetterObserved = true;
    }
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
    const bool optIn = hookRequested();
    const bool compatibilityOptIn = requested();
    const bool diagnostic = optIn && ldsp_diagnostics::enabled() && openDiagnostics.fetch_add(1, std::memory_order_relaxed) < 12;
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio",
        "open enter pid=%d tracked=%d direction=%d mode=%d ready=%d", getpid(),
        state.builder != nullptr, static_cast<int>(state.direction), static_cast<int>(state.performance),
        ready.load(std::memory_order_acquire));
    // Session reuse is independent of the FAST/low-latency compatibility
    // downgrade. Players such as Spotify may use POWER_SAVING or NONE, but
    // still create a fresh AAudio session for each stream. Preserve their
    // requested performance mode while pinning only an unassigned session.
    const bool reuseRequested = reuseSessionRequested() && originalSession && originalGetSession &&
        state.sessionObserved && state.session == AAUDIO_SESSION_ID_NONE;
    const bool downgradeLowLatency = compatibilityOptIn && state.directionObserved && state.performanceObserved &&
        state.direction == AAUDIO_DIRECTION_OUTPUT &&
        state.performance == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    if (!stream || !ready.load(std::memory_order_acquire) || unsafeFailure.load(std::memory_order_acquire) || !optIn ||
        !state.builder || state.direction != AAUDIO_DIRECTION_OUTPUT || (!downgradeLowLatency && !reuseRequested)) {
        const auto status = originalOpen(builder, stream);
        if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio",
            "open original returned=%d pid=%d", status, getpid());
        return status;
    }
    // The builder is restored even on failure. A clean failure gets exactly one
    // retry using the application's original request; no routing/restart loop.
    if (downgradeLowLatency) originalPerformance(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    // In the opt-in session-reuse experiment, serialize first allocation so
    // concurrent streams in one app process cannot claim different session IDs.
    // Explicit app-requested session IDs are never replaced.
    std::unique_lock<std::mutex> sessionLock(pinnedSessionMutex, std::defer_lock);
    if (reuseRequested) sessionLock.lock();
    const bool reuseExistingSession = reuseRequested && pinnedSession > 0;
    // Only an observed NONE request may be temporarily allocated or pinned.
    // Allocation remains available for the existing pre-start preparation path.
    const bool allocateSession = originalSession && originalGetSession && state.sessionObserved &&
        state.session == AAUDIO_SESSION_ID_NONE &&
        ((canPrepareStream() && state.sessionSetterObserved) || reuseRequested) && !reuseExistingSession;
    if (reuseExistingSession) originalSession(builder, pinnedSession);
    else if (allocateSession) originalSession(builder, AAUDIO_SESSION_ID_ALLOCATE);
    AAudioStream* candidate = nullptr;
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "open normal enter pid=%d", getpid());
    const auto status = originalOpen(builder, &candidate);
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "open normal returned=%d pid=%d", status, getpid());
    if (downgradeLowLatency) originalPerformance(builder, state.performance);
    if (allocateSession) originalSession(builder, state.session);
    else if (reuseExistingSession) originalSession(builder, state.session);
    if (status != AAUDIO_OK && candidate == nullptr) {
        // A rejected reused ID must not poison later opens. The fallback below
        // retries exactly once with the original session and performance mode.
        if (reuseExistingSession) pinnedSession = AAUDIO_SESSION_ID_NONE;
        const auto fallback = originalOpen(builder, stream);
        __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                            "normal request failed=%d; original retry=%d pid=%d", status, fallback, getpid());
        if (sessionLock.owns_lock()) sessionLock.unlock();
        return fallback;
    }
    bool firstPinnedSession = false;
    if (status == AAUDIO_OK && allocateSession && reuseRequested) {
        const auto allocated = originalGetSession(candidate);
        if (allocated > 0) {
            pinnedSession = allocated;
            firstPinnedSession = true;
        }
    }
    // Keep the first allocation serialized through its bounded preparation, so
    // another eligible open cannot start on the pinned ID before this barrier.
    // Subsequent streams share the established chain. Reissuing preparation on
    // every brief sound can synchronously stall each open, so only the first
    // stream asks the app/controller to prewarm this stable session.
    if (status == AAUDIO_OK && (!reuseRequested || firstPinnedSession)) prepareOpenedStream(candidate, firstPinnedSession);
    if (status == AAUDIO_OK && reuseExistingSession) renewOpenedStream(candidate);
    if (sessionLock.owns_lock()) sessionLock.unlock();
    *stream = candidate;
    if (diagnostic) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio",
        "normal request pid=%d result=%d session=%d reused=%d", getpid(), status,
        originalGetSession ? originalGetSession(candidate) : AAUDIO_SESSION_ID_NONE, reuseExistingSession);
    return status;
}

// Oboe resolves AAudio through an explicit dlopen handle instead of ELF imports.
// Some apps statically link Oboe into their app-specific audio DSO, so rebind
// that DSO's app-local dlsym slot as well as liboboe.so. Never intercept libdl
// itself. The special handles use caller-relative lookup in Bionic, so a
// guaranteed tail call must preserve the original return address.
[[maybe_unused]] void* resolveAppAudioSymbol(void* handle, const char* symbol) {
    if (handle == RTLD_DEFAULT || handle == RTLD_NEXT) {
        [[clang::musttail]] return originalResolve(handle, symbol);
    }
    // Resolve first and keep the linker's dlerror state intact. Concrete handles
    // use handle-relative lookup; do not perform extra linker queries or logging.
    void* resolved = originalResolve(handle, symbol);
    // The GOT slot becomes visible before libraryLoaded publishes ready. Oboe
    // caches the returned pointers, so use the initialized observer gate here;
    // create/open wrappers still pass through until ready is published.
    if (!resolved || !symbol || !hookRequested() || !observerReady.load(std::memory_order_acquire) ||
        unsafeFailure.load(std::memory_order_acquire)) return resolved;
    struct Mapping { const char* name; void* original; void* replacement; };
    const std::array<Mapping, 5> mappings{{
        {"AAudio_createStreamBuilder", reinterpret_cast<void*>(originalCreate), reinterpret_cast<void*>(createBuilder)},
        {"AAudioStreamBuilder_setDirection", reinterpret_cast<void*>(originalDirection), reinterpret_cast<void*>(setDirection)},
        {"AAudioStreamBuilder_setPerformanceMode", reinterpret_cast<void*>(originalPerformance), reinterpret_cast<void*>(setPerformance)},
        {"AAudioStreamBuilder_openStream", reinterpret_cast<void*>(originalOpen), reinterpret_cast<void*>(openStream)},
        {"AAudioStreamBuilder_delete", reinterpret_cast<void*>(originalDelete), reinterpret_cast<void*>(deleteBuilder)},
    }};
    if (originalSession && resolved == reinterpret_cast<void*>(originalSession) &&
        !std::strcmp(symbol, "AAudioStreamBuilder_setSessionId")) return reinterpret_cast<void*>(setSession);
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
[[maybe_unused]] bool eligibleDynamicAAudioLibrary(const char* path) {
    if (!eligibleLibrary(path)) return false;
    const char* basename = std::strrchr(path, '/');
    if (!basename) return false;
    const char* name = basename + 1;
    // Oboe's Android loader resolves AAudio with dlsym. It can live in a
    // standalone liboboe.so, in JL Mod's soundfont DSOs, or statically inside
    // ARMSX2's page-size-specific libemucore_4k/16k.so. Restrict this resolver hook to known app-local
    // audio backends; the wrapper still substitutes only matching AAudio
    // symbols and addresses.
    return !std::strcmp(name, "liboboe.so") || !std::strcmp(name, "libmmapi_tsf.so") ||
        !std::strcmp(name, "libmmapi_eas.so") || !std::strcmp(name, "libemucore_4k.so") ||
        !std::strcmp(name, "libemucore_16k.so");
}
template <typename Paths, typename Callback>
size_t visitEligibleLibraries(const Paths& paths, Callback&& callback) {
    size_t visited = 0;
    for (const auto& path : paths) {
        if (!eligibleLibrary(path.c_str())) continue;
        callback(path.c_str());
        ++visited;
    }
    return visited;
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
        if (result == ldsp::got::Result::Applied || result == ldsp::got::Result::AlreadyApplied) {
            if (std::strcmp(backend, "OpenSL") != 0) aaudioReady.store(true, std::memory_order_release);
        }
        lookup.reason = "import transaction rejected or rolled back";
        if (result == ldsp::got::Result::Applied)
            if (ldsp_diagnostics::enabled()) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "%s imports ready pid=%d", backend, getpid());
    };
    applyImports(targets, "AAudio");
    if (lookup.result == ldsp::got::Result::Unsafe) return 1;
    // Track direct imports too. The dynamic dlsym wrapper covers Oboe-style
    // lookups, but without this optional single-symbol transaction an app's
    // ordinary PLT call could set an explicit ID that the default-NONE reuse
    // path would otherwise mistake for an unset request.
    if (originalSession) {
        const std::array<ldsp::got::Target, 1> sessionTargets{{
            {"AAudioStreamBuilder_setSessionId", reinterpret_cast<uintptr_t>(originalSession),
                reinterpret_cast<uintptr_t>(setSession), true}
        }};
        ldsp::got::BasicPlan<1> sessionPlan;
        if (!ldsp::got::prepare({info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum},
                sessionTargets, static_cast<size_t>(pageSize), sessionPlan)) {
            sessionReuseSafe.store(false, std::memory_order_release);
            __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                "session reuse disabled: setter import could not be verified pid=%d", getpid());
        } else if (sessionPlan.page) {
            int protection = 0;
            if (!pageProtection(sessionPlan.page, sessionPlan.pageSize, protection) ||
                    protection != sessionPlan.expectedProtection) {
                sessionReuseSafe.store(false, std::memory_order_release);
                __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                    "session reuse disabled: setter import protection unsupported pid=%d", getpid());
            } else {
                const auto result = ldsp::got::apply(sessionPlan, protection, protectPage, nullptr);
                if (result == ldsp::got::Result::Unsafe) {
                    sessionReuseSafe.store(false, std::memory_order_release);
                    unsafeFailure.store(true, std::memory_order_release);
                    ready.store(false, std::memory_order_release);
                    __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                        "session setter import transaction unsafe pid=%d", getpid());
                    return 1;
                }
                if (result != ldsp::got::Result::Applied && result != ldsp::got::Result::AlreadyApplied) {
                    sessionReuseSafe.store(false, std::memory_order_release);
                    __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio",
                        "session reuse disabled: setter import transaction rejected pid=%d", getpid());
                }
            }
        }
    } else {
        sessionReuseSafe.store(false, std::memory_order_release);
    }
    // Independent one-symbol transaction, also considered in mixed-backend DSOs.
    const std::array<ldsp::got::Target, 1> openSlTargets{{
        {"slCreateEngine", reinterpret_cast<uintptr_t>(ldsp::opensl::originalCreate),
            reinterpret_cast<uintptr_t>(ldsp::opensl::createEngine)}
    }};
    applyImports(openSlTargets, "OpenSL");
    if (lookup.result == ldsp::got::Result::Unsafe) return 1;
    if (eligibleDynamicAAudioLibrary(info->dlpi_name)) {
        const std::array<ldsp::got::Target, 1> resolverTargets{{
            {"dlsym", reinterpret_cast<uintptr_t>(originalResolve), reinterpret_cast<uintptr_t>(resolveAppAudioSymbol)}
        }};
        applyImports(resolverTargets, "dynamic AAudio backend");
    }
    return 1;
}
#endif
void libraryLoaded(const char* name, void*) {
    if (!hookRequested() || unsafeFailure.load(std::memory_order_acquire) || !eligibleLibrary(name)) return;
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
        if (ldsp_diagnostics::enabled()) __android_log_print(ANDROID_LOG_INFO, "LightningDSP_AAudio", "app imports ready pid=%d", getpid());
    } else if (lookup.result == ldsp::got::Result::Unsafe) {
        unsafeFailure.store(true, std::memory_order_release);
        ready.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_WARN, "LightningDSP_AAudio", "import transaction unsafe; compatibility disabled pid=%d", getpid());
    } else {
        reportRejection(lookup.reason);
    }
#endif
}

#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
struct LoadedLibraryPaths {
    std::vector<std::string> paths;
};
int collectLoadedLibrary(dl_phdr_info* info, size_t, void* opaque) {
    if (!info->dlpi_name || !eligibleLibrary(info->dlpi_name)) return 0;
    static_cast<LoadedLibraryPaths*>(opaque)->paths.emplace_back(info->dlpi_name);
    return 0;
}
size_t scanLoadedLibraries() {
    if (!observerReady.load(std::memory_order_acquire) || !hookRequested()) return 0;
    LoadedLibraryPaths loaded;
    dl_iterate_phdr(collectLoadedLibrary, &loaded);
    // Do not rebind while dl_iterate_phdr is walking the loader's image list.
    // Copy names first, then reuse the normal callback's duplicate-image checks.
    return visitEligibleLibraries(loaded.paths, [](const char* path) { libraryLoaded(path, nullptr); });
}
#endif
}
#endif

#if !defined(__aarch64__) && !defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
namespace {
size_t scanLoadedLibraries() { return 0; }
}
#endif

// Avoid inline patches on short AAudio entry points.
extern "C" __attribute__((visibility("default"), used))
LoadedCallback native_init(const NativeApi* api) {
#if defined(__aarch64__) || defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
    if (!allowedProcess() || !api || (api->version != 1 && api->version != 2) || !hookRequested()) return nullptr;
    if (initStarted.exchange(true, std::memory_order_acq_rel)) return nullptr;
    originalCreate = AAudio_createStreamBuilder;
    originalDirection = AAudioStreamBuilder_setDirection;
    originalPerformance = AAudioStreamBuilder_setPerformanceMode;
    originalOpen = AAudioStreamBuilder_openStream;
    originalDelete = AAudioStreamBuilder_delete;
    originalResolve = dlsym;
#ifdef LDSP_AAUDIO_COMPAT_HOST_TEST
    originalSession = AAudioStreamBuilder_setSessionId;
    originalGetSession = AAudioStream_getSessionId;
#else
    originalSession = reinterpret_cast<SessionSetter>(dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setSessionId"));
    originalGetSession = reinterpret_cast<SessionGetter>(dlsym(RTLD_DEFAULT, "AAudioStream_getSessionId"));
#endif
#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
    // Restrict this experimental policy to the examined RetroArch package.
    char processName[128]{};
    if (FILE* cmdline = std::fopen("/proc/self/cmdline", "re")) {
        std::fread(processName, 1, sizeof(processName) - 1, cmdline);
        std::fclose(cmdline);
    }
    ldsp::opensl::preferNoPerformanceRequirement = !std::strcmp(processName, "com.retroarch");
    ldsp::opensl::originalCreate = slCreateEngine;
    ldsp::opensl::requested = [] { return requested() && !unsafeFailure.load(std::memory_order_acquire); };
    ldsp::opensl::report = [](const char* message, SLresult result) {
        static std::atomic<unsigned> reports{0};
        if (!ldsp_diagnostics::enabled()) return;
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
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeAAudioReady(JNIEnv*, jclass) {
    return aaudioReady.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeObserverReady(JNIEnv*, jclass) {
    return observerReady.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeScanLoadedAudioLibraries(JNIEnv*, jclass) {
    try {
        return static_cast<jint>(scanLoadedLibraries());
    } catch (...) {
        return 0;
    }
}
#endif

#ifndef LDSP_AAUDIO_COMPAT_HOST_TEST
extern "C" JNIEXPORT void JNICALL
Java_lightning_dsp_interop_AudioCompatibilityHook_nativeSetPreparationCallback(JNIEnv* env, jclass cls) {
    // Installed once during package initialization, before app audio construction.
    std::lock_guard<std::mutex> lock(preparationRegistration);
    if (preparationReady.load(std::memory_order_acquire)) return;
    jmethodID method = env->GetStaticMethodID(cls, "prepareSession", "(IZ)V");
    if (!method || env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jmethodID renew = env->GetStaticMethodID(cls, "retainUnifiedSession", "(I)V");
    if (!renew || env->ExceptionCheck()) { env->ExceptionClear(); return; }
    jclass global = static_cast<jclass>(env->NewGlobalRef(cls));
    if (!global) return;
    env->GetJavaVM(&preparationVm);
    preparationMethod = method;
    retentionMethod = renew;
    preparationClass = global;
    preparationReady.store(true, std::memory_order_release);
}
#endif
