#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <thread>

#include "deadaccurate/Events.h"
#include "deadaccurate/SpscEventQueue.h"
#include "deadaccurate/SpscRingBuffer.h"

namespace deadaccurate {

// Owns the AAudio input stream, the DSP thread, and the outbound event
// queue (docs/02-architecture.md section 3). The AAudio data callback only
// writes into the ring buffer; all analysis happens on the DSP thread, and
// the Kotlin side drains `events` from its own polling thread.
class CaptureEngine {
public:
    CaptureEngine();
    ~CaptureEngine();

    CaptureEngine(const CaptureEngine&) = delete;
    CaptureEngine& operator=(const CaptureEngine&) = delete;

    // Opens and starts the stream. `deviceId` 0 means "let the system route";
    // `inputPreset` is an AAUDIO_INPUT_PRESET_* value chosen by the Kotlin
    // layer (which knows whether UNPROCESSED is supported). Returns 0 on
    // success or a negative AAudio result code.
    int32_t Start(int32_t deviceId, int32_t inputPreset);

    void Stop();

    size_t DrainEvents(Event* out, size_t maxCount);

private:
    static aaudio_data_callback_result_t DataCallback(AAudioStream* stream, void* userData,
                                                      void* audioData, int32_t numFrames);
    static void ErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

    int32_t OpenStream(int32_t deviceId, int32_t inputPreset,
                       aaudio_sharing_mode_t sharingMode);
    void DspLoop();

    // ~2.7 s of audio at 48 kHz: deep enough that a stalled DSP thread never
    // costs the callback samples under normal scheduling.
    SpscRingBuffer ringBuffer_{1u << 17};
    SpscEventQueue events_{256};

    AAudioStream* stream_ = nullptr;
    std::thread dspThread_;
    std::atomic<bool> running_{false};
    // Set by the AAudio error callback (its own thread); the DSP thread — the
    // queue's only producer — notices and emits the status event.
    std::atomic<bool> disconnected_{false};

    int32_t sampleRate_ = 0;
    int32_t deviceId_ = 0;
    bool exclusive_ = false;
    bool unprocessed_ = false;
};

}  // namespace deadaccurate
