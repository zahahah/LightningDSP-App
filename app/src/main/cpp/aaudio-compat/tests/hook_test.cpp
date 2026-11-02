#define LDSP_AAUDIO_COMPAT_HOST_TEST
#include "../aaudio_compat.cpp"
#include <cassert>
#include <algorithm>
#include <cstdio>
#include <vector>

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
    *out = &audioStream; return 0;
}
extern "C" int AAudio_createStreamBuilder(AAudioStreamBuilder** b) { return fakeCreate(b); }
extern "C" void AAudioStreamBuilder_setDirection(AAudioStreamBuilder* b, int d) { fakeDirection(b, d); }
extern "C" void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder* b, int p) { fakePerformance(b, p); }
extern "C" int AAudioStreamBuilder_openStream(AAudioStreamBuilder* b, AAudioStream** out) { return fakeOpen(b, out); }
extern "C" int AAudioStreamBuilder_delete(AAudioStreamBuilder* b) { return fakeDelete(b); }
static int hookCount = 0;
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
    std::puts("AAudio compatibility construction tests passed");
}
