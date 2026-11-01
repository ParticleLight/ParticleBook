#include <jni.h>
#include <android/log.h>
#include <chrono>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "pbcore", __VA_ARGS__)

static long long now_ms() {
    using namespace std::chrono;
    return (long long)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_particlebook_android_NativeCore_ping(JNIEnv*, jclass) {
    return (jlong)now_ms();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_particlebook_android_NativeCore_version(JNIEnv* env, jclass) {
    return env->NewStringUTF("pbcore-p0-stub/1.0");
}

jint JNI_OnLoad(JavaVM*, void*) {
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}
