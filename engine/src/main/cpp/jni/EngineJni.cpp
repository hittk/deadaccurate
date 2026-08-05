#include <jni.h>

#include <algorithm>
#include <array>

#include "../platform/CaptureEngine.h"
#include "deadaccurate/Events.h"
#include "deadaccurate/Version.h"

namespace {

deadaccurate::CaptureEngine* FromHandle(jlong handle) {
    return reinterpret_cast<deadaccurate::CaptureEngine*>(handle);
}

// Bounds one drain call; Kotlin polls frequently, so leftovers surface on
// the next call.
constexpr size_t kMaxEventsPerDrain = 64;

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeGetVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(deadaccurate::EngineVersion());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new deadaccurate::CaptureEngine());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jlong handle, jint deviceId,
                                                      jint inputPreset) {
    return FromHandle(handle)->Start(deviceId, inputPreset);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jlong handle) {
    FromHandle(handle)->Stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                        jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeSetLiftAngleDeg(JNIEnv* /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle,
                                                                jfloat degrees) {
    FromHandle(handle)->SetLiftAngleDeg(degrees);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeSetGateTrimDb(JNIEnv* /*env*/,
                                                              jobject /*thiz*/, jlong handle,
                                                              jfloat trimDb) {
    FromHandle(handle)->SetGateTrimDb(trimDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeRecalibrateGate(JNIEnv* /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle) {
    FromHandle(handle)->RecalibrateGate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeSetBphOverride(JNIEnv* /*env*/,
                                                               jobject /*thiz*/, jlong handle,
                                                               jint bph) {
    FromHandle(handle)->SetBphOverride(bph);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeSetAnalysisMode(JNIEnv* /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle, jint mode) {
    FromHandle(handle)->SetAnalysisMode(mode);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeDrainEvents(JNIEnv* env, jobject /*thiz*/,
                                                            jlong handle,
                                                            jfloatArray outArray) {
    const size_t arrayCapacity =
        static_cast<size_t>(env->GetArrayLength(outArray)) / deadaccurate::kEventFloats;

    std::array<deadaccurate::Event, kMaxEventsPerDrain> events;
    const size_t n = FromHandle(handle)->DrainEvents(
        events.data(), std::min(arrayCapacity, events.size()));
    if (n > 0) {
        static_assert(sizeof(deadaccurate::Event) ==
                      deadaccurate::kEventFloats * sizeof(float));
        env->SetFloatArrayRegion(outArray, 0,
                                 static_cast<jsize>(n * deadaccurate::kEventFloats),
                                 &events[0].v[0]);
    }
    return static_cast<jint>(n);
}
