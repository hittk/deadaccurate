#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "deadaccurate/BeatRateDetector.h"
#include "deadaccurate/Biquad.h"
#include "deadaccurate/EnvelopeFollower.h"
#include "deadaccurate/FoldingAnalyzer.h"
#include "deadaccurate/LevelAnalyzer.h"
#include "deadaccurate/NoiseGate.h"
#include "deadaccurate/RateEstimator.h"
#include "deadaccurate/TickDetector.h"

namespace deadaccurate {

// The full chain (docs/03-signal-processing.md):
//   band-pass -> envelope -> { edge path: gate -> tick detector -> rate
//   estimation | correlation path: energy folding }
// plus hop-rate level telemetry. Both analysis paths are always fed so the
// user can switch instantly; the mode selects which one drives the output
// events. Runs on the DSP thread; everything here is platform-free and
// deterministic.
class DspChain {
public:
    enum class AnalysisMode {
        kEdge = 0,         // per-tick gate edges: precise, needs piezo SNR
        kCorrelation = 1,  // energy folding: phone-mic SNR, slower to settle
    };
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
        int detectedBph;    // detector's lock (0 = searching); keeps
                            // reporting under an override for disagreement
        bool overridden;    // user override pins the rate
        bool rateValid;     // enough clean window to trust secPerDay
        float secPerDay;    // + = fast
        int tickCount;      // accepted ticks in the estimation window
        float beatErrorMs;  // negative = not yet measurable
    };

    // Correlation-mode trace feed: the folded peak's drift, ~2 Hz.
    struct PhaseFrame {
        float phaseDeviationMs;  // wrapped to ±period/2
        float periodMs;
    };

    struct Output {
        std::vector<LevelFrame> levels;
        std::vector<TickEvent> ticks;
        std::vector<RateFrame> rates;
        std::vector<PhaseFrame> phases;
    };

    explicit DspChain(int sampleRate);

    // Control surface, applied between blocks by the DSP thread.
    void SetGateTrimDb(float trimDb) { gate_.SetTrimDb(trimDb); }
    void RecalibrateGate() { gate_.StartCalibration(); }
    // FR-4: a positive bph pins the rate; 0 returns to auto-detection.
    void SetBphOverride(int bph);
    void SetAnalysisMode(AnalysisMode mode);

    // Clears and refills `out` from `count` input samples.
    void Process(const float* samples, size_t count, Output& out);

    // Diagnostic passthrough for offline tools: current fold score of a
    // candidate rate in one correlation band.
    double FoldingScoreForDebug(int bph, int channel) const {
        return folding_.ScoreForDebug(bph, channel);
    }

private:
    void ResolveActiveRate();
    void EmitRateFrame(Output& out);

    static constexpr double kBandLowHz = 2000.0;
    static constexpr double kBandHighHz = 12000.0;
    // The correlation path listens in bands: tick energy lands in different
    // ranges on different hardware (a real phone-mic recording put it at
    // 8-16 kHz with room noise below 3 kHz), and per-band folding keeps a
    // quiet band's ticks from being swamped by a loud band's noise.
    static constexpr double kCorrBandEdgesHz[FoldingAnalyzer::kChannels + 1] = {
        800.0, 3000.0, 8000.0, 16000.0};
    static constexpr double kAttackMs = 0.5;
    static constexpr double kReleaseMs = 5.0;
    static constexpr int kLevelFramesPerSecond = 30;
    // One rate frame per this many level hops (~2 Hz), plus every change.
    static constexpr int kLevelHopsPerRateFrame = 15;

    const int sampleRate_;
    BandPassFilter bandPass_;
    EnvelopeFollower envelope_;
    // No attack/release follower here: the analyzer's 1 ms bin-mean is the
    // smoother, and a 5 ms release smears weak ticks across fold slots.
    std::vector<SteepBandPassFilter> corrBands_;
    NoiseGate gate_;
    TickDetector tickDetector_;
    LevelAnalyzer levelAnalyzer_;
    BeatRateDetector rateDetector_;
    RateEstimator rateEstimator_;
    FoldingAnalyzer folding_;
    std::vector<LevelAnalyzer::Level> levelScratch_;

    AnalysisMode mode_ = AnalysisMode::kEdge;
    int overrideBph_ = 0;
    int activeBph_ = 0;
    int levelHopCounter_ = 0;
    bool rateDirty_ = true;
};

}  // namespace deadaccurate
