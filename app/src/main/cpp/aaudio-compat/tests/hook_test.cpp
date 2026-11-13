#define LDSP_AAUDIO_COMPAT_HOST_TEST
#include "../aaudio_compat.cpp"
#include <cassert>
#include <algorithm>
#include <cstdio>
#include <vector>
#include <string>

static const char* property = "";
static const char* reuseSessionProperty = "0";
static std::vector<int> openedModes;
static bool normalFails = false;
static int warnings = 0;
static int nextAllocatedSession = 101;
static AAudioStream audioStream;
extern "C" int __system_property_get(const char* name, char* value) {
    const char* selected = name && std::strcmp(name, kReuseSessionProperty) == 0 ? reuseSessionProperty : property;
    std::strcpy(value, selected); return std::strlen(selected);
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
    audioStream.session = b->session == AAUDIO_SESSION_ID_ALLOCATE ? nextAllocatedSession++ : b->session;
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
    const std::vector<std::string> alreadyLoaded{
        "/data/app/com.example-1/lib/arm64/liboboe.so",
        "/data/app/com.example-1/lib/arm64/libmmapi_eas.so",
        "/data/app/com.armsx2/lib/arm64/libemucore_4k.so",
        "/system/lib64/libaaudio.so",
        "/data/app/com.lightning.dsp-1/lib/arm64/libldsp_aaudio_compat.so",
        "/data/app/com.example-1/lib/arm64/libunrelated.so"
    };
    std::vector<std::string> scanned;
    const auto visited = visitEligibleLibraries(alreadyLoaded, [&](const char* path) { scanned.emplace_back(path); });
    assert(visited == 4);
    assert(scanned.size() == 4);
    assert(scanned[0] == alreadyLoaded[0] && eligibleDynamicAAudioLibrary(scanned[0].c_str()));
    assert(scanned[1] == alreadyLoaded[1] && eligibleDynamicAAudioLibrary(scanned[1].c_str()));
    assert(scanned[2] == alreadyLoaded[2] && eligibleDynamicAAudioLibrary(scanned[2].c_str()));
    assert(scanned[3] == alreadyLoaded[5] && !eligibleDynamicAAudioLibrary(scanned[3].c_str()));

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
    assert(eligibleDynamicAAudioLibrary("/data/app/example/lib/arm64/libemucore_4k.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/base.apk!/lib/arm64-v8a/libemucore_16k.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/lib/arm64/libmmapi_tsf.so"));
    assert(eligibleDynamicAAudioLibrary("/data/app/example/base.apk!/lib/arm64-v8a/libmmapi_eas.so"));
    for (const char* path : {"/system/lib64/liboboe.so", "/system/lib64/libmmapi_tsf.so",
            "/system/lib64/libemucore_4k.so",
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
    // Session reuse can opt into the scoped hook independently of the optional
    // low-latency downgrade. Fusion Mode users can reuse AAudio IDs without enabling
    // the separate compatibility mode.
    property = "0"; reuseSessionProperty = "1";
    assert(hookRequested() && !requested() && reuseSessionRequested());
    AAudioStreamBuilder* reuseOnlyBuilder = nullptr;
    assert(createBuilder(&reuseOnlyBuilder) == AAUDIO_OK && findBuilder(reuseOnlyBuilder));
    setDirection(reuseOnlyBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(reuseOnlyBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    expect(reuseOnlyBuilder, {AAUDIO_PERFORMANCE_MODE_LOW_LATENCY});
    assert(reuseOnlyBuilder->performance == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    assert(audioStream.session > 0);
    deleteBuilder(reuseOnlyBuilder);
    reuseSessionProperty = "0"; property = "1";
    pinnedSession = AAUDIO_SESSION_ID_NONE;
    nextAllocatedSession = 101;
    preparationsForTest = 0;
    preparedReuseForTest = false;
    renewalsForTest = 0;
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
    assert(!preparedReuseForTest);
    setSession(sessionBuilder, AAUDIO_SESSION_ID_NONE);
    expect(sessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 102 && pinnedSession == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforePreparation + 2);
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

    // The experimental session pin is independently opt-in, applies only to
    // observed NONE requests, and prepares a shared process session only once.
    reuseSessionProperty = "1";
    pinnedSession = AAUDIO_SESSION_ID_NONE;
    const auto savedNextAllocatedSession = nextAllocatedSession;
    AAudioStreamBuilder* powerSavingBuilder = nullptr;
    createBuilder(&powerSavingBuilder);
    setDirection(powerSavingBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(powerSavingBuilder, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    setSession(powerSavingBuilder, AAUDIO_SESSION_ID_NONE);
    const auto beforePowerSavingPreparation = preparationsForTest;
    expect(powerSavingBuilder, {AAUDIO_PERFORMANCE_MODE_POWER_SAVING});
    const auto powerSavingSession = audioStream.session;
    assert(powerSavingSession > 0 && pinnedSession == powerSavingSession);
    assert(powerSavingBuilder->performance == AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    assert(powerSavingBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforePowerSavingPreparation + 1);
    AAudioStreamBuilder* nextPowerSavingBuilder = nullptr;
    createBuilder(&nextPowerSavingBuilder);
    setDirection(nextPowerSavingBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(nextPowerSavingBuilder, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    setSession(nextPowerSavingBuilder, AAUDIO_SESSION_ID_NONE);
    const auto powerSavingRenewals = renewalsForTest;
    expect(nextPowerSavingBuilder, {AAUDIO_PERFORMANCE_MODE_POWER_SAVING});
    assert(audioStream.session == powerSavingSession);
    assert(renewalsForTest == powerSavingRenewals + 1);
    assert(preparationsForTest == beforePowerSavingPreparation + 1);
    deleteBuilder(nextPowerSavingBuilder);
    deleteBuilder(powerSavingBuilder);
    pinnedSession = AAUDIO_SESSION_ID_NONE;
    nextAllocatedSession = savedNextAllocatedSession;

    AAudioStreamBuilder* firstReuseBuilder = nullptr;
    createBuilder(&firstReuseBuilder);
    setDirection(firstReuseBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(firstReuseBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    setSession(firstReuseBuilder, AAUDIO_SESSION_ID_NONE);
    const auto beforePinnedPreparation = preparationsForTest;
    expect(firstReuseBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 103);
    assert(pinnedSession == 103 && firstReuseBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforePinnedPreparation + 1);
    assert(preparedReuseForTest);

    AAudioStreamBuilder* nextReuseBuilder = nullptr;
    createBuilder(&nextReuseBuilder);
    setDirection(nextReuseBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(nextReuseBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    setSession(nextReuseBuilder, AAUDIO_SESSION_ID_NONE);
    const auto beforeRenewal = renewalsForTest;
    expect(nextReuseBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(renewalsForTest == beforeRenewal + 1);
    assert(audioStream.session == 103);
    assert(pinnedSession == 103 && nextReuseBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforePinnedPreparation + 1);
    assert(preparedReuseForTest);

    setSession(nextReuseBuilder, 777);
    expect(nextReuseBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 777 && pinnedSession == 103);
    deleteBuilder(nextReuseBuilder);
    deleteBuilder(firstReuseBuilder);

    // AAudio's documented builder default is NONE even when the caller never
    // invokes setSessionId. This is the common app path the reuse experiment
    // must recognize, pin, and reuse for later short streams.
    pinnedSession = AAUDIO_SESSION_ID_NONE;
    AAudioStreamBuilder* defaultSessionBuilder = nullptr;
    createBuilder(&defaultSessionBuilder);
    assert(findBuilder(defaultSessionBuilder)->sessionObserved);
    assert(findBuilder(defaultSessionBuilder)->session == AAUDIO_SESSION_ID_NONE);
    setDirection(defaultSessionBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(defaultSessionBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    const auto beforeDefaultSessionPreparation = preparationsForTest;
    expect(defaultSessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 104);
    assert(pinnedSession == 104 && defaultSessionBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforeDefaultSessionPreparation + 1);
    assert(preparedReuseForTest);

    AAudioStreamBuilder* nextDefaultSessionBuilder = nullptr;
    createBuilder(&nextDefaultSessionBuilder);
    setDirection(nextDefaultSessionBuilder, AAUDIO_DIRECTION_OUTPUT);
    setPerformance(nextDefaultSessionBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    expect(nextDefaultSessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 104);
    assert(pinnedSession == 104 && nextDefaultSessionBuilder->session == AAUDIO_SESSION_ID_NONE);
    assert(preparationsForTest == beforeDefaultSessionPreparation + 1);
    assert(preparedReuseForTest);
    // Turning reuse off/on does not require allocating a new pin. The next reused
    // open can restart the non-blocking Java lease worker, without a new prewarm.
    const auto renewalsBeforeToggle = renewalsForTest;
    const auto preparationsBeforeToggle = preparationsForTest;
    reuseSessionProperty = "0";
    expect(nextDefaultSessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(renewalsForTest == renewalsBeforeToggle);
    reuseSessionProperty = "1";
    expect(nextDefaultSessionBuilder, {AAUDIO_PERFORMANCE_MODE_NONE});
    assert(audioStream.session == 104);
    assert(renewalsForTest == renewalsBeforeToggle + 1);
    assert(preparationsForTest == preparationsBeforeToggle);
    deleteBuilder(nextDefaultSessionBuilder);
    deleteBuilder(defaultSessionBuilder);
    reuseSessionProperty = "0";
    std::puts("AAudio compatibility construction tests passed");
}
