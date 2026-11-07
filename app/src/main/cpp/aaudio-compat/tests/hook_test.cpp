#define LDSP_AAUDIO_COMPAT_HOST_TEST
#include "../aaudio_compat.cpp"
#include <cassert>
#include <algorithm>
#include <cstdio>
#include <vector>
#include <string>

static const char* property = "";
static std::vector<int> openedModes;
static bool normalFails = false;
static int warnings = 0;
static AAudioStream audioStream;
extern "C" int __system_property_get(const char*, char* value) {
    std::strcpy(value, property); return std::strlen(property);
}
extern "C" int __android_log_print(int priority, const char*, const char*, ...) {
    if (priority == ANDROID_LOG_WARN) ++warnings;
    return 0;
}
static AAudioStreamBuilder* reusedBuilder = nullptr;
static int fakeCreate(AAudioStreamBuilder** out) {
    *out = reusedBuilder ? reusedBuilder : new AAudioStreamBuilder;
    reusedBuilder = nullptr;
    return 0;
}
static void fakeDirection(AAudioStreamBuilder* b, int d) { b->direction = d; }
static void fakePerformance(AAudioStreamBuilder* b, int p) { b->performance = p; }
static int fakeDelete(AAudioStreamBuilder* b) { delete b; return 0; }
static int fakeOpen(AAudioStreamBuilder* b, AAudioStream** out) {
    openedModes.push_back(b->performance);
    if (normalFails && b->performance == 10) { *out = nullptr; return -1; }
    audioStream.session = b->session == 0 ? 101 : b->session;
    *out = &audioStream; return 0;
}
extern "C" int AAudio_createStreamBuilder(AAudioStreamBuilder** b) { return fakeCreate(b); }
extern "C" void AAudioStreamBuilder_setDirection(AAudioStreamBuilder* b, int d) { fakeDirection(b, d); }
extern "C" void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder* b, int p) { fakePerformance(b, p); }
extern "C" int AAudioStreamBuilder_openStream(AAudioStreamBuilder* b, AAudioStream** out) { return fakeOpen(b, out); }
extern "C" int AAudioStreamBuilder_delete(AAudioStreamBuilder* b) { return fakeDelete(b); }
extern "C" void AAudioStreamBuilder_setSessionId(AAudioStreamBuilder* b, int session) { b->session = session; }
extern "C" int AAudioStream_getSessionId(AAudioStream* stream) { return stream->session; }
static int hookCount = 0;
static void* resolvedSymbol = nullptr;
static void* lastHandle = nullptr;
static const char* lastSymbol = nullptr;
static unsigned resolutionCount = 0;
static void* fakeResolve(void* handle, const char* symbol) {
    ++resolutionCount; lastHandle = handle; lastSymbol = symbol;
    return resolvedSymbol;
}
static int fakeHook(void* function, void*, void** backup) {
    ++hookCount;
    *backup = function;
    return 0;
}
static void expect(AAudioStreamBuilder* builder, std::initializer_list<int> modes) {
    openedModes.clear(); AAudioStream* stream = nullptr;
    assert(openStream(builder, &stream) == 0);
    assert(stream == &audioStream);
    assert(openedModes == std::vector<int>(modes));
}
int main() {
    NativeApi invalid{99, fakeHook, nullptr};
    native_init(&invalid); assert(!ready.load()); assert(hookCount == 0);
    NativeApi valid{2, fakeHook, nullptr};
    native_init(&valid); assert(!ready.load()); assert(hookCount == 0);
    property = "0"; native_init(&valid); assert(!ready.load()); assert(hookCount == 0);
    property = "1";
    assert(native_init(&valid) != nullptr); assert(observerReady.load()); assert(!ready.load()); assert(hookCount == 0);
    ready.store(true);
    // Oboe's explicit-handle queries are resolved exactly once before mapping.
    originalResolve = fakeResolve;
    void* concreteHandle = reinterpret_cast<void*>(0x1234);
    const std::array<const char*, 5> names{{"AAudio_createStreamBuilder", "AAudioStreamBuilder_setDirection",
        "AAudioStreamBuilder_setPerformanceMode", "AAudioStreamBuilder_openStream", "AAudioStreamBuilder_delete"}};
    const std::array<void*, 5> originals{{reinterpret_cast<void*>(originalCreate), reinterpret_cast<void*>(originalDirection),
        reinterpret_cast<void*>(originalPerformance), reinterpret_cast<void*>(originalOpen), reinterpret_cast<void*>(originalDelete)}};
    const std::array<void*, 5> replacements{{reinterpret_cast<void*>(createBuilder), reinterpret_cast<void*>(setDirection),
        reinterpret_cast<void*>(setPerformance), reinterpret_cast<void*>(openStream), reinterpret_cast<void*>(deleteBuilder)}};
    for (size_t i = 0; i < names.size(); ++i) {
        resolvedSymbol = originals[i];
        const auto before = resolutionCount;
        assert(resolveAppAudioSymbol(concreteHandle, names[i]) == replacements[i]);
        assert(resolutionCount == before + 1 && lastHandle == concreteHandle && lastSymbol == names[i]);
        // Same name but another implementation must remain untouched.
        resolvedSymbol = reinterpret_cast<void*>(0x4567);
        assert(resolveAppAudioSymbol(concreteHandle, names[i]) == resolvedSymbol);
        resolvedSymbol = originals[i];
        assert(resolveAppAudioSymbol(concreteHandle, "unrelated_symbol") == resolvedSymbol);
        assert(resolveAppAudioSymbol(RTLD_DEFAULT, names[i]) == resolvedSymbol);
        assert(lastHandle == RTLD_DEFAULT);
        assert(resolveAppAudioSymbol(RTLD_NEXT, names[i]) == resolvedSymbol);
        assert(lastHandle == RTLD_NEXT);
        property = "0"; assert(resolveAppAudioSymbol(concreteHandle, names[i]) == resolvedSymbol); property = "1";
        ready.store(false); assert(resolveAppAudioSymbol(concreteHandle, names[i]) == replacements[i]); ready.store(true);
        observerReady.store(false); assert(resolveAppAudioSymbol(concreteHandle, names[i]) == resolvedSymbol); observerReady.store(true);
        unsafeFailure.store(true); assert(resolveAppAudioSymbol(concreteHandle, names[i]) == resolvedSymbol); unsafeFailure.store(false);
    }
    // Interleaving: Oboe resolves and caches its pointers after GOT publication
    // but before the load callback publishes ready. Cached wrappers must survive
    // that window without modifying construction until compatibility is ready.
    ready.store(false);
    resolvedSymbol = originals[0];
    auto cachedCreate = reinterpret_cast<decltype(originalCreate)>(resolveAppAudioSymbol(concreteHandle, names[0]));
    resolvedSymbol = originals[1];
    auto cachedDirection = reinterpret_cast<decltype(originalDirection)>(resolveAppAudioSymbol(concreteHandle, names[1]));
    resolvedSymbol = originals[2];
    auto cachedPerformance = reinterpret_cast<decltype(originalPerformance)>(resolveAppAudioSymbol(concreteHandle, names[2]));
    resolvedSymbol = originals[3];
    auto cachedOpen = reinterpret_cast<decltype(originalOpen)>(resolveAppAudioSymbol(concreteHandle, names[3]));
    resolvedSymbol = originals[4];
    auto cachedDelete = reinterpret_cast<decltype(originalDelete)>(resolveAppAudioSymbol(concreteHandle, names[4]));
    AAudioStreamBuilder* earlyBuilder = nullptr;
    assert(cachedCreate(&earlyBuilder) == AAUDIO_OK && findBuilder(earlyBuilder) == nullptr);
    cachedDirection(earlyBuilder, AAUDIO_DIRECTION_OUTPUT);
    cachedPerformance(earlyBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    openedModes.clear(); AAudioStream* earlyStream = nullptr;
    assert(cachedOpen(earlyBuilder, &earlyStream) == AAUDIO_OK && openedModes == std::vector<int>{12});
    assert(cachedDelete(earlyBuilder) == AAUDIO_OK);
    ready.store(true);
    AAudioStreamBuilder* lateBuilder = nullptr;
    assert(cachedCreate(&lateBuilder) == AAUDIO_OK && findBuilder(lateBuilder));
    cachedDirection(lateBuilder, AAUDIO_DIRECTION_OUTPUT);
    cachedPerformance(lateBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    openedModes.clear();
    assert(cachedOpen(lateBuilder, &earlyStream) == AAUDIO_OK && openedModes == std::vector<int>{10});
    property = "0"; openedModes.clear();
    assert(cachedOpen(lateBuilder, &earlyStream) == AAUDIO_OK && openedModes == std::vector<int>{12});
    property = "1";
    // Unsafe publication disables readiness; previously cached wrappers retain
    // their forwarding behavior and cannot re-enable processing by themselves.
    unsafeFailure.store(true); openedModes.clear();
    assert(cachedOpen(lateBuilder, &earlyStream) == AAUDIO_OK && openedModes == std::vector<int>{12});
    AAudioStreamBuilder* unsafeBuilder = nullptr;
    assert(cachedCreate(&unsafeBuilder) == AAUDIO_OK && findBuilder(unsafeBuilder) == nullptr);
    assert(cachedDelete(unsafeBuilder) == AAUDIO_OK);
    ready.store(false); openedModes.clear();
    assert(cachedOpen(lateBuilder, &earlyStream) == AAUDIO_OK && openedModes == std::vector<int>{12});
    unsafeFailure.store(false); ready.store(true);
    assert(cachedDelete(lateBuilder) == AAUDIO_OK);
    resolvedSymbol = nullptr;
    assert(resolveAppAudioSymbol(concreteHandle, names[0]) == nullptr);
    assert(resolveAppAudioSymbol(concreteHandle, nullptr) == nullptr);
    assert(eligibleDynamicAAudioLibrary("/data/app/example/lib/arm64/liboboe.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/base.apk!/lib/arm64-v8a/liboboe.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/lib/arm64/libmmapi_tsf.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/base.apk!/lib/arm64-v8a/libmmapi_eas.so"));
    for (const char* path : {"/system/lib64/liboboe.so", "/system/lib64/libmmapi_tsf.so",
            "/data/app/example/liboboe.so.bak", "/data/app/example/libother.so",
            "/data/app/com.lightning.dsp-abc/liboboe.so", "/data/app/com.lightning.dsp-abc/libmmapi_tsf.so"})
        assert(!eligibleDynamicAAudioLibrary(path));
    assert(!eligibleDynamicAAudioLibrary(nullptr));
    // Actual resolver integration: preserve success, failures, and an unread
    // linker error instead of clearing or consuming it during address checks.
    originalResolve = dlsym;
#if defined(__APPLE__)
    void* process = dlopen("/usr/lib/libSystem.B.dylib", RTLD_NOW);
#else
    void* process = dlopen("libc.so.6", RTLD_NOW);
#endif
    assert(process && process != RTLD_DEFAULT && process != RTLD_NEXT);
    dlerror();
    void* mallocSymbol = dlsym(process, "malloc");
    assert(mallocSymbol && dlerror() == nullptr);
    assert(resolveAppAudioSymbol(process, "malloc") == mallocSymbol && dlerror() == nullptr);
    constexpr const char* missingSymbol = "ldsp_deliberately_missing_oboe_test_symbol";
    assert(resolveAppAudioSymbol(process, missingSymbol) == nullptr);
    const char* missingError = dlerror();
    assert(missingError && std::strstr(missingError, missingSymbol));
    assert(dlerror() == nullptr);
    dlsym(process, missingSymbol);
    dlsym(process, "malloc");
    const char* baselineError = dlerror();
    const std::string baseline = baselineError ? baselineError : "";
    dlsym(process, missingSymbol);
    resolveAppAudioSymbol(process, "malloc");
    const char* wrappedError = dlerror();
    assert(baseline == (wrappedError ? wrappedError : ""));
    dlclose(process);
    AAudioStreamBuilder* builder = nullptr; assert(createBuilder(&builder) == 0);
    setPerformance(builder, 12);
    expect(builder, {12}); // No observed direction: fail closed.
    setDirection(builder, 0);
    // Neither native loading, an absent property, nor any truthy string is opt-in.
    property = "1"; ready.store(false); expect(builder, {12});
    ready.store(true);
    for (const char* value : {"", "0", "true", "01"}) { property = value; expect(builder, {12}); }
    property = "1"; expect(builder, {10}); assert(builder->performance == 12);
    // Input stays untouched regardless of whether direction is set before/after performance.
    setDirection(builder, 1); expect(builder, {12});
    setPerformance(builder, 12); expect(builder, {12});
    setDirection(builder, 0); expect(builder, {10});
    // Creation through another DSO may recycle an address whose deletion was
    // never observed. Reset the record, not merely a second free table entry.
    reusedBuilder = builder;
    AAudioStreamBuilder* recycled = nullptr;
    assert(createBuilder(&recycled) == 0 && recycled == builder);
    assert(!findBuilder(builder)->directionObserved && !findBuilder(builder)->performanceObserved);
    expect(builder, {12});
    setDirection(builder, 0); setPerformance(builder, 12);
    normalFails = true; expect(builder, {10, 12}); assert(builder->performance == 12); assert(warnings == 1);
    normalFails = false;
    setPerformance(builder, 10); expect(builder, {10});
    setPerformance(builder, 11); expect(builder, {11});
    // Unknown builders retain their original behavior.
    AAudioStreamBuilder unknown; unknown.performance = 12; expect(&unknown, {12});
    assert(deleteBuilder(builder) == 0);
    std::vector<AAudioStreamBuilder*> allocated;
    for (int i = 0; i < 129; ++i) { AAudioStreamBuilder* b; createBuilder(&b); setPerformance(b, 12); setDirection(b, 0); allocated.push_back(b); }
    expect(allocated[0], {10}); expect(allocated[128], {12});
    for (auto* b : allocated) deleteBuilder(b);
    assert(std::all_of(builders.begin(), builders.end(), [](const auto& b) { return !b.builder; }));
    property = "1";
    ready.store(true);
    AAudioStreamBuilder* sessionBuilder = nullptr;
    createBuilder(&sessionBuilder);
    setDirection(sessionBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(sessionBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    setSession(sessionBuilder, AAUDIO_SESSION_ID_NONE);
    const auto beforePreparation = preparationsForTest;
    expect(sessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(sessionBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(audioStream.session == 101);
    assert(preparationsForTest == beforePreparation + 1);
    setSession(sessionBuilder, 777);
    expect(sessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(sessionBuilder->session == 777 && audioStream.session == 777);
    setSession(sessionBuilder, AAUDIO_SESSION_ID_NONE);
    normalFails = true;
    const auto failedPreparations = preparationsForTest;
    expect(sessionBuilder, {10, 12});
    assert(sessionBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(audioStream.session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == failedPreparations);
    normalFails = false;
    // Unobserved requests must not be overwritten even when compatibility is active.
    findBuilder(sessionBuilder)->sessionObserved = false;
    sessionBuilder->session = 999;
    expect(sessionBuilder, {10});
    assert(audioStream.session == 999 && sessionBuilder->session == 999);
    deleteBuilder(sessionBuilder);
    std::puts("AAudio compatibility construction tests passed");
}
