#include <jni.h>

#include "deadaccurate/Version.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_deadaccurate_engine_NativeEngine_nativeGetVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(deadaccurate::EngineVersion());
}
