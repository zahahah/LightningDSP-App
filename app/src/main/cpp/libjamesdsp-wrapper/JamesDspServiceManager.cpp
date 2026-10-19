#include <android/binder_ibinder.h>
#include <android/binder_ibinder_jni.h>
#include <dlfcn.h>
#include <jni.h>

namespace {
using CheckServiceFn = AIBinder* (*)(const char*);
using ToJavaBinderFn = jobject (*)(JNIEnv*, AIBinder*);
using DecStrongFn = void (*)(AIBinder*);

struct BinderNdkApi {
    void* handle = nullptr;
    CheckServiceFn checkService = nullptr;
    ToJavaBinderFn toJavaBinder = nullptr;
    DecStrongFn decStrong = nullptr;

    BinderNdkApi() {
        // Binder NDK became a public app API in Android 10 (API 29). Load it
        // dynamically so older devices can still load the existing app JNI.
        handle = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) return;
        checkService = reinterpret_cast<CheckServiceFn>(dlsym(handle, "AServiceManager_checkService"));
        toJavaBinder = reinterpret_cast<ToJavaBinderFn>(dlsym(handle, "AIBinder_toJavaBinder"));
        decStrong = reinterpret_cast<DecStrongFn>(dlsym(handle, "AIBinder_decStrong"));
        if (checkService == nullptr || toJavaBinder == nullptr || decStrong == nullptr) {
            dlclose(handle);
            handle = nullptr;
            checkService = nullptr;
            toJavaBinder = nullptr;
            decStrong = nullptr;
        }
    }

    ~BinderNdkApi() {
        if (handle != nullptr) dlclose(handle);
    }
};

BinderNdkApi& binderNdkApi() {
    static BinderNdkApi api;
    return api;
}
}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspControlClient_checkServiceNative(
        JNIEnv* env, jobject /* receiver */, jstring serviceName) {
    if (env == nullptr || serviceName == nullptr) return nullptr;

    BinderNdkApi& api = binderNdkApi();
    if (api.handle == nullptr) return nullptr;

    const char* name = env->GetStringUTFChars(serviceName, nullptr);
    if (name == nullptr) return nullptr;

    AIBinder* binder = api.checkService(name);
    env->ReleaseStringUTFChars(serviceName, name);
    if (binder == nullptr) return nullptr;

    jobject result = api.toJavaBinder(env, binder);
    api.decStrong(binder);
    return result;
}
