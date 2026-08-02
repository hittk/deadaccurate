#pragma once

#include <cstddef>

namespace deadaccurate {

// Events cross the JNI seam as flat float records of kEventFloats each
// (docs/02-architecture.md section 3.2/3.3). v[0] is the EventType; the
// meaning of v[1..] depends on the type. Kotlin-side decoding lives in
// EventDecoder.kt and must stay in sync with the layouts below.
inline constexpr size_t kEventFloats = 8;

enum class EventType : int {
    kLevel = 1,   // v[1]=rmsDb, v[2]=peakDb
    kStatus = 2,  // v[1]=EngineState, v[2]=sampleRate, v[3]=unprocessed,
                  // v[4]=exclusive, v[5]=deviceId, v[6]=errorCode
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

inline Event MakeLevelEvent(float rmsDb, float peakDb) {
    Event e{};
    e.v[0] = static_cast<float>(EventType::kLevel);
    e.v[1] = rmsDb;
    e.v[2] = peakDb;
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
