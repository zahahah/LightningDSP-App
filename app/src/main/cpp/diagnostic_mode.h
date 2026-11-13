#ifndef LIGHTNINGDSP_DIAGNOSTIC_MODE_H
#define LIGHTNINGDSP_DIAGNOSTIC_MODE_H
#if defined(__ANDROID__) && !defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
#include <sys/system_properties.h>
#endif
namespace ldsp_diagnostics {
inline bool enabled() {
#if defined(__ANDROID__) && !defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
    char value[PROP_VALUE_MAX] = {};
    return __system_property_get("debug.ldsp.diagnostics", value) == 1 && value[0] == '1';
#else
    return false;
#endif
}
}
#endif
