#ifndef LIGHTNINGDSP_LOG_H
#define LIGHTNINGDSP_LOG_H

#ifndef TAG
#define TAG "LDSP_JNI"
#endif

#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__);
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__);
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__);
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__);
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__);

#endif // LIGHTNINGDSP_LOG_H
