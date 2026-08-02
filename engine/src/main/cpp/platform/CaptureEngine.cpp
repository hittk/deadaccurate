#include "CaptureEngine.h"

#include <chrono>
#include <vector>

#include "deadaccurate/DspChain.h"

namespace deadaccurate {
namespace {

constexpr auto kIdlePollInterval = std::chrono::milliseconds(2);
constexpr int32_t kErrorAlreadyRunning = -1000;

}  // namespace

CaptureEngine::CaptureEngine() = default;

CaptureEngine::~CaptureEngine() {
    Stop();
}

aaudio_data_callback_result_t CaptureEngine::DataCallback(AAudioStream* /*stream*/,
                                                          void* userData, void* audioData,
                                                          int32_t numFrames) {
    auto* self = static_cast<CaptureEngine*>(userData);
    // Mono float stream: frames == samples. Drops on overflow by design —
    // the callback must never wait (NFR-1).
    self->ringBuffer_.Write(static_cast<const float*>(audioData),
                            static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void CaptureEngine::ErrorCallback(AAudioStream* /*stream*/, void* userData,
                                  aaudio_result_t error) {
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        static_cast<CaptureEngine*>(userData)->disconnected_.store(true);
    }
}

int32_t CaptureEngine::OpenStream(int32_t deviceId, int32_t inputPreset,
                                  aaudio_sharing_mode_t sharingMode) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        return result;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setInputPreset(builder, inputPreset);
    AAudioStreamBuilder_setSharingMode(builder, sharingMode);
    if (deviceId != 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }
    AAudioStreamBuilder_setDataCallback(builder, DataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, ErrorCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    return result;
}

int32_t CaptureEngine::Start(int32_t deviceId, int32_t inputPreset) {
    if (running_.load()) {
        return kErrorAlreadyRunning;
    }

    // FR-1: exclusive first, transparent fallback to shared.
    int32_t result = OpenStream(deviceId, inputPreset, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = OpenStream(deviceId, inputPreset, AAUDIO_SHARING_MODE_SHARED);
    }
    if (result != AAUDIO_OK) {
        stream_ = nullptr;
        return result;
    }

    sampleRate_ = AAudioStream_getSampleRate(stream_);
    deviceId_ = AAudioStream_getDeviceId(stream_);
    exclusive_ = AAudioStream_getSharingMode(stream_) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    unprocessed_ = AAudioStream_getInputPreset(stream_) == AAUDIO_INPUT_PRESET_UNPROCESSED;

    result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return result;
    }

    disconnected_.store(false);
    running_.store(true);
    dspThread_ = std::thread(&CaptureEngine::DspLoop, this);
    return AAUDIO_OK;
}

void CaptureEngine::Stop() {
    if (!running_.exchange(false)) {
        return;
    }
    dspThread_.join();
    if (stream_ != nullptr) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
    // The DSP thread (the queue's producer) is joined, so this push cannot
    // race it.
    events_.Push(MakeStatusEvent(EngineState::kIdle, sampleRate_, unprocessed_,
                                 exclusive_, deviceId_, 0));
}

size_t CaptureEngine::DrainEvents(Event* out, size_t maxCount) {
    return events_.Drain(out, maxCount);
}

void CaptureEngine::DspLoop() {
    DspChain chain(sampleRate_);
    DspChain::Output output;
    std::vector<float> scratch(1024);

    float appliedTrimDb = gateTrimDb_.load();
    int appliedBph = bph_.load();
    chain.SetGateTrimDb(appliedTrimDb);
    chain.SetBeatRateBph(appliedBph);
    int64_t previousTickFrame = -1;

    events_.Push(MakeStatusEvent(EngineState::kRunning, sampleRate_, unprocessed_,
                                 exclusive_, deviceId_, 0));

    while (running_.load()) {
        if (disconnected_.exchange(false)) {
            events_.Push(MakeStatusEvent(EngineState::kDisconnected, sampleRate_,
                                         unprocessed_, exclusive_, deviceId_,
                                         AAUDIO_ERROR_DISCONNECTED));
        }

        const float trimDb = gateTrimDb_.load();
        if (trimDb != appliedTrimDb) {
            appliedTrimDb = trimDb;
            chain.SetGateTrimDb(trimDb);
        }
        const int bph = bph_.load();
        if (bph != appliedBph) {
            appliedBph = bph;
            chain.SetBeatRateBph(bph);
        }
        if (recalibrateRequested_.exchange(false)) {
            chain.RecalibrateGate();
        }

        const size_t n = ringBuffer_.Read(scratch.data(), scratch.size());
        if (n == 0) {
            std::this_thread::sleep_for(kIdlePollInterval);
            continue;
        }

        chain.Process(scratch.data(), n, output);
        for (const auto& level : output.levels) {
            events_.Push(MakeLevelEvent(level.rmsDb, level.peakDb, level.thresholdDb,
                                        level.gateOpen, level.calibrating));
        }
        for (const auto& tick : output.ticks) {
            const float deltaFrames =
                previousTickFrame < 0
                    ? 0.0f
                    : static_cast<float>(tick.frameIndex - previousTickFrame);
            previousTickFrame = tick.frameIndex;
            events_.Push(MakeTickEvent(deltaFrames, tick.peakDb));
        }
    }
}

}  // namespace deadaccurate
