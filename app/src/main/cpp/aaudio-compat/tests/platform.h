#pragma once
#include <cstdint>
#include <cstddef>
using aaudio_session_id_t = int32_t;
using aaudio_result_t = int32_t;
using aaudio_direction_t = int32_t;
using aaudio_performance_mode_t = int32_t;
struct AAudioStreamBuilder { int direction = 0; int performance = 10; int session = -1; };
struct AAudioStream { int session = -1; };
constexpr int AAUDIO_SESSION_ID_NONE = -1;
constexpr int AAUDIO_SESSION_ID_ALLOCATE = 0;
constexpr int AAUDIO_OK = 0;
constexpr int AAUDIO_DIRECTION_OUTPUT = 0;
constexpr int AAUDIO_DIRECTION_INPUT = 1;
constexpr int AAUDIO_PERFORMANCE_MODE_NONE = 10;
constexpr int AAUDIO_PERFORMANCE_MODE_POWER_SAVING = 11;
constexpr int AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12;
constexpr int PROP_VALUE_MAX = 92;
constexpr int ANDROID_LOG_INFO = 4;
constexpr int ANDROID_LOG_WARN = 5;
extern "C" {
int AAudio_createStreamBuilder(AAudioStreamBuilder**);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, int);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, int);
int AAudioStreamBuilder_openStream(AAudioStreamBuilder*, AAudioStream**);
void AAudioStreamBuilder_setSessionId(AAudioStreamBuilder*, int);
int AAudioStream_getSessionId(AAudioStream*);
int AAudioStreamBuilder_delete(AAudioStreamBuilder*);
int __system_property_get(const char*, char*);
int __android_log_print(int, const char*, const char*, ...);
}
