#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "deadaccurate/Biquad.h"
#include "deadaccurate/EnvelopeFollower.h"
#include "deadaccurate/LevelAnalyzer.h"
#include "deadaccurate/NoiseGate.h"
#include "deadaccurate/TickDetector.h"

namespace deadaccurate {

// The full M2 chain (docs/03-signal-processing.md):
//   band-pass -> envelope -> noise gate -> tick detector
// plus hop-rate level telemetry for the UI meter. Runs on the DSP thread;
// everything here is platform-free and deterministic.
class DspChain {
public:
    struct LevelFrame {
        float rmsDb;          // envelope RMS over the hop
        float peakDb;         // envelope peak over the hop
        float thresholdDb;    // current gate open threshold
        bool gateOpen;        // gate state at hop end
        bool calibrating;     // gate is measuring the noise floor
    };

    struct TickEvent {
        int64_t frameIndex;
        float peakDb;
    };

    struct Output {
        std::vector<LevelFrame> levels;
        std::vector<TickEvent> ticks;
    };

    explicit DspChain(int sampleRate);

    // Control surface, applied between blocks by the DSP thread.
    void SetGateTrimDb(float trimDb) { gate_.SetTrimDb(trimDb); }
    void RecalibrateGate() { gate_.StartCalibration(); }
    void SetBeatRateBph(int bph);

    // Clears and refills `out` from `count` input samples.
    void Process(const float* samples, size_t count, Output& out);

private:
    static constexpr double kBandLowHz = 2000.0;
    static constexpr double kBandHighHz = 12000.0;
    static constexpr double kAttackMs = 0.5;
    static constexpr double kReleaseMs = 5.0;
    static constexpr int kLevelFramesPerSecond = 30;

    const int sampleRate_;
    BandPassFilter bandPass_;
    EnvelopeFollower envelope_;
    NoiseGate gate_;
    TickDetector tickDetector_;
    LevelAnalyzer levelAnalyzer_;
    std::vector<LevelAnalyzer::Level> levelScratch_;
};

}  // namespace deadaccurate
