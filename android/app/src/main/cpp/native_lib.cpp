#include <jni.h>
#include <android/log.h>
#include "opensl_audio.h"

#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static OpenSLAudioPlayer* g_audio = nullptr;

// 与 MainActivity companion object 中的 static native 方法对应。
// 静态方法 -> JNI 第二参数为 jclass。

extern "C" JNIEXPORT jint JNICALL
Java_com_example_hicar_e01_demo_MainActivity_nativeInitAudio(JNIEnv* env, jclass clazz) {
    if (g_audio == nullptr) {
        g_audio = new OpenSLAudioPlayer();
    }
    bool ok = g_audio->init();
    LOGD("nativeInitAudio ok=%d", ok ? 1 : 0);
    return ok ? 0 : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_hicar_e01_demo_MainActivity_nativeWritePcm(JNIEnv* env, jclass clazz,
                                                            jbyteArray data, jlong ptsUs) {
    if (g_audio == nullptr || data == nullptr) return -1;
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    jsize len = env->GetArrayLength(data);
    int ret = g_audio->writePcm((const uint8_t*)buf, (uint32_t)len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return ret;
}

// cmd: 0/1 预留；2 = 暂停/恢复（value 0=暂停 1=恢复）
extern "C" JNIEXPORT jint JNICALL
Java_com_example_hicar_e01_demo_MainActivity_nativeAudioControl(JNIEnv* env, jclass clazz,
                                                                jint cmd, jint value) {
    if (g_audio == nullptr) return -1;
    if (cmd == 2) {
        if (value == 0) g_audio->pause();
        else g_audio->resume();
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_hicar_e01_demo_MainActivity_nativeReleaseAudio(JNIEnv* env, jclass clazz) {
    if (g_audio) {
        g_audio->destroy();
        delete g_audio;
        g_audio = nullptr;
    }
    LOGD("nativeReleaseAudio done");
    return 0;
}
