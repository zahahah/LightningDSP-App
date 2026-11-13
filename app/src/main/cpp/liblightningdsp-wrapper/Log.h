#ifndef LIGHTNINGDSP_LOG_H
#define LIGHTNINGDSP_LOG_H

#ifndef TAG
#define TAG "LDSP_JNI"
#endif

#include <android/log.h>
#include "../diagnostic_mode.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);
#define LOGD(...) do { if (ldsp_diagnostics::enabled()) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); } while (0);
#define LOGI(...) do { if (ldsp_diagnostics::enabled()) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); } while (0);
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__);
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__);
#define LOGV(...) do { if (ldsp_diagnostics::enabled()) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__); } while (0);

#endif // LIGHTNINGDSP_LOG_H
