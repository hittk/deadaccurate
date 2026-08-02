#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "deadaccurate/BeatRateDetector.h"
#include "deadaccurate/Biquad.h"
#include "deadaccurate/EnvelopeFollower.h"
#include "deadaccurate/LevelAnalyzer.h"
#include "deadaccurate/NoiseGate.h"
#include "deadaccurate/RateEstimator.h"
#include "deadaccurate/TickDetector.h"

namespace deadaccurate {

// The full v1 chain (docs/03-signal-processing.md):
//   band-pass -> envelope -> noise gate -> tick detector
//   -> beat-rate identification -> rate estimation (s/day)
// plus hop-rate level telemetry. Runs on the DSP thread; everything here is
// platform-free and deterministic.
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
        bool accepted;  // false = outlier, excluded from rate estimation
    };

    struct RateFrame {
        int activeBph;      // 0 while searching with no override
        bool locked;        // detector currently locked
        bool overridden;    // user override pins the rate
        bool rateValid;     // enough clean window to trust secPerDay
        float secPerDay;    // + = fast
        int tickCount;      // accepted ticks in the estimation window
        float beatErrorMs;  // negative = not yet measurable
    };

    struct Output {
        std::vector<LevelFrame> levels;
        std::vector<TickEvent> ticks;
        std::vector<RateFrame> rates;
    };

    explicit DspChain(int sampleRate);

    // Control surface, applied between blocks by the DSP thread.
    void SetGateTrimDb(float trimDb) { gate_.SetTrimDb(trimDb); }
    void RecalibrateGate() { gate_.StartCalibration(); }
    // FR-4: a positive bph pins the rate; 0 returns to auto-detection.
    void SetBphOverride(int bph);

    // Clears and refills `out` from `count` input samples.
    void Process(const float* samples, size_t count, Output& out);

private:
    void ResolveActiveRate();
    void EmitRateFrame(Output& out);

    static constexpr double kBandLowHz = 2000.0;
    static constexpr double kBandHighHz = 12000.0;
    static constexpr double kAttackMs = 0.5;
    static constexpr double kReleaseMs = 5.0;
    static constexpr int kLevelFramesPerSecond = 30;
    // One rate frame per this many level hops (~2 Hz), plus every change.
    static constexpr int kLevelHopsPerRateFrame = 15;

    const int sampleRate_;
    BandPassFilter bandPass_;
    EnvelopeFollower envelope_;
    NoiseGate gate_;
    TickDetector tickDetector_;
    LevelAnalyzer levelAnalyzer_;
    BeatRateDetector rateDetector_;
    RateEstimator rateEstimator_;
    std::vector<LevelAnalyzer::Level> levelScratch_;

    int overrideBph_ = 0;
    int activeBph_ = 0;
    int levelHopCounter_ = 0;
    bool rateDirty_ = true;
};

}  // namespace deadaccurate
