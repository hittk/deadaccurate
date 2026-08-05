#pragma once

#include <cstddef>

namespace deadaccurate {

// Events cross the JNI seam as flat float records of kEventFloats each
// (docs/02-architecture.md section 3.2/3.3). v[0] is the EventType; the
// meaning of v[1..] depends on the type. Kotlin-side decoding lives in
// EventDecoder.kt and must stay in sync with the layouts below.
inline constexpr size_t kEventFloats = 8;

enum class EventType : int {
    kLevel = 1,   // v[1]=rmsDb, v[2]=peakDb, v[3]=gateThresholdDb,
                  // v[4]=gateOpen, v[5]=calibrating
    kStatus = 2,  // v[1]=EngineState, v[2]=sampleRate, v[3]=unprocessed,
                  // v[4]=exclusive, v[5]=deviceId, v[6]=errorCode
    kTick = 3,    // v[1]=deltaFrames since previous tick (0 for the first),
                  // v[2]=peakDb, v[3]=accepted
    kRate = 4,    // v[1]=activeBph, v[2]=detectedBph (0 = not locked),
                  // v[3]=overridden, v[4]=rateValid, v[5]=secPerDay,
                  // v[6]=tickCount, v[7]=beatErrorMs (negative = not
                  // measurable). detectedBph keeps reporting while an
                  // override is active so the UI can flag disagreement.
    kPhase = 5,   // correlation-mode trace feed: v[1]=phaseDeviationMs
                  // (wrapped to ±period/2), v[2]=periodMs
    kSignature = 6,  // acoustic signature: v[1]=bph, v[2..5]=fold score of
                     // that rate in each analysis band (movement
                     // recognition raw material)
    kAmplitude = 7,  // v[1]=valid, v[2]=amplitudeDeg, v[3]=liftTimeMs
};

enum class EngineState : int {
    kIdle = 0,
    kRunning = 1,
    kDisconnected = 2,
    kError = 3,
};

struct Event {
    float v[kEventFloats];
};

inline Event MakeLevelEvent(float rmsDb, float peakDb, float gateThresholdDb,
                            bool gateOpen, bool calibrating) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kLevel);
    e.v[1] = rmsDb;
    e.v[2] = peakDb;
    e.v[3] = gateThresholdDb;
    e.v[4] = gateOpen ? 1.0f : 0.0f;
    e.v[5] = calibrating ? 1.0f : 0.0f;
    return e;
}

// Tick timestamps cross the seam as deltas because a float cannot hold large
// absolute frame indices exactly; the Kotlin side accumulates them in a
// Double.
inline Event MakeTickEvent(float deltaFrames, float peakDb, bool accepted) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kTick);
    e.v[1] = deltaFrames;
    e.v[2] = peakDb;
    e.v[3] = accepted ? 1.0f : 0.0f;
    return e;
}

inline Event MakePhaseEvent(float phaseDeviationMs, float periodMs) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kPhase);
    e.v[1] = phaseDeviationMs;
    e.v[2] = periodMs;
    return e;
}

inline Event MakeAmplitudeEvent(bool valid, float amplitudeDeg, float liftTimeMs) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kAmplitude);
    e.v[1] = valid ? 1.0f : 0.0f;
    e.v[2] = amplitudeDeg;
    e.v[3] = liftTimeMs;
    return e;
}

inline Event MakeSignatureEvent(int bph, const float* bandScores, size_t count) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kSignature);
    e.v[1] = static_cast<float>(bph);
    for (size_t c = 0; c < count && c + 2 < kEventFloats; ++c) {
        e.v[c + 2] = bandScores[c];
    }
    return e;
}

inline Event MakeRateEvent(int activeBph, int detectedBph, bool overridden, bool rateValid,
                           float secPerDay, int tickCount, float beatErrorMs) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kRate);
    e.v[1] = static_cast<float>(activeBph);
    e.v[2] = static_cast<float>(detectedBph);
    e.v[3] = overridden ? 1.0f : 0.0f;
    e.v[4] = rateValid ? 1.0f : 0.0f;
    e.v[5] = secPerDay;
    e.v[6] = static_cast<float>(tickCount);
    e.v[7] = beatErrorMs;
    return e;
}

inline Event MakeStatusEvent(EngineState state, int sampleRate, bool unprocessed,
                             bool exclusive, int deviceId, int errorCode) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kStatus);
    e.v[1] = static_cast<float>(state);
    e.v[2] = static_cast<float>(sampleRate);
    e.v[3] = unprocessed ? 1.0f : 0.0f;
    e.v[4] = exclusive ? 1.0f : 0.0f;
    e.v[5] = static_cast<float>(deviceId);
    e.v[6] = static_cast<float>(errorCode);
    return e;
}

}  // namespace deadaccurate
