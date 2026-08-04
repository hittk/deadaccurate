#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <vector>

#include "deadaccurate/DspChain.h"
#include "deadaccurate/Events.h"

// Offline analysis of recorded audio through the exact same DspChain as live
// capture. Single-threaded: Kotlin feeds chunks and receives the flat event
// records synchronously — no stream, no queues.

namespace {

struct ReplaySession {
    explicit ReplaySession(int sampleRate) : chain(sampleRate) {}

    deadaccurate::DspChain chain;
    deadaccurate::DspChain::Output output;
    std::vector<deadaccurate::Event> events;
    std::vector<float> input;
    int64_t previousTickFrame = -1;
};

ReplaySession* FromHandle(jlong handle) {
    return reinterpret_cast<ReplaySession*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_deadaccurate_engine_ReplayEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jint sampleRate) {
    return reinterpret_cast<jlong>(new ReplaySession(sampleRate));
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_ReplayEngine_nativeSetBphOverride(JNIEnv* /*env*/,
                                                               jobject /*thiz*/, jlong handle,
                                                               jint bph) {
    FromHandle(handle)->chain.SetBphOverride(bph);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_ReplayEngine_nativeSetGateTrimDb(JNIEnv* /*env*/,
                                                              jobject /*thiz*/, jlong handle,
                                                              jfloat trimDb) {
    FromHandle(handle)->chain.SetGateTrimDb(trimDb);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_deadaccurate_engine_ReplayEngine_nativeProcess(JNIEnv* env, jobject /*thiz*/,
                                                        jlong handle, jfloatArray samples,
                                                        jint count, jfloatArray outEvents) {
    auto* session = FromHandle(handle);
    session->input.resize(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, session->input.data());

    session->chain.Process(session->input.data(), static_cast<size_t>(count),
                           session->output);

    // Same event framing as CaptureEngine's DSP loop.
    auto& events = session->events;
    events.clear();
    for (const auto& level : session->output.levels) {
        events.push_back(deadaccurate::MakeLevelEvent(level.rmsDb, level.peakDb,
                                                      level.thresholdDb, level.gateOpen,
                                                      level.calibrating));
    }
    for (const auto& tick : session->output.ticks) {
        const float deltaFrames =
            session->previousTickFrame < 0
                ? 0.0f
                : static_cast<float>(tick.frameIndex - session->previousTickFrame);
        session->previousTickFrame = tick.frameIndex;
        events.push_back(deadaccurate::MakeTickEvent(deltaFrames, tick.peakDb, tick.accepted));
    }
    for (const auto& rate : session->output.rates) {
        events.push_back(deadaccurate::MakeRateEvent(rate.activeBph, rate.detectedBph,
                                                     rate.overridden, rate.rateValid,
                                                     rate.secPerDay, rate.tickCount,
                                                     rate.beatErrorMs));
    }

    const size_t capacity =
        static_cast<size_t>(env->GetArrayLength(outEvents)) / deadaccurate::kEventFloats;
    const size_t n = std::min(events.size(), capacity);
    if (n > 0) {
        env->SetFloatArrayRegion(outEvents, 0,
                                 static_cast<jsize>(n * deadaccurate::kEventFloats),
                                 &events[0].v[0]);
    }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_deadaccurate_engine_ReplayEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                        jlong handle) {
    delete FromHandle(handle);
}
