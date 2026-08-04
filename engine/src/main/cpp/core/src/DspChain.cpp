#include "deadaccurate/DspChain.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {

DspChain::DspChain(int sampleRate)
    : sampleRate_(sampleRate),
      bandPass_(sampleRate, kBandLowHz, kBandHighHz),
      envelope_(sampleRate, kAttackMs, kReleaseMs),
      gate_(sampleRate),
      tickDetector_(sampleRate),
      levelAnalyzer_(static_cast<size_t>(sampleRate / kLevelFramesPerSecond)),
      rateDetector_(sampleRate),
      rateEstimator_(sampleRate),
      folding_(sampleRate) {
    for (int c = 0; c < FoldingAnalyzer::kChannels; ++c) {
        // Clamp band tops below Nyquist for low device sample rates.
        const double hi = std::min(kCorrBandEdgesHz[c + 1], sampleRate * 0.45);
        corrBands_.emplace_back(sampleRate, kCorrBandEdgesHz[c], hi);
    }
}

void DspChain::SetBphOverride(int bph) {
    overrideBph_ = bph > 0 ? bph : 0;
    folding_.SetBphOverride(overrideBph_);
    ResolveActiveRate();
}

void DspChain::SetAnalysisMode(AnalysisMode mode) {
    if (mode != mode_) {
        mode_ = mode;
        rateDirty_ = true;
    }
}

void DspChain::ResolveActiveRate() {
    const int newActive =
        overrideBph_ > 0 ? overrideBph_
                         : (rateDetector_.locked() ? rateDetector_.lockedBph() : 0);
    if (newActive == activeBph_) {
        return;
    }
    activeBph_ = newActive;
    rateDirty_ = true;
    if (activeBph_ > 0) {
        const double periodFrames = 3600.0 / activeBph_ * sampleRate_;
        tickDetector_.SetBeatPeriodFrames(periodFrames);
        rateEstimator_.Reset(periodFrames);
    } else {
        rateEstimator_.Reset(0.0);
    }
}

void DspChain::EmitRateFrame(Output& out) {
    const auto estimate = rateEstimator_.CurrentEstimate();
    out.rates.push_back({
        activeBph_,
        rateDetector_.lockedBph(),
        overrideBph_ > 0,
        estimate.valid,
        static_cast<float>(estimate.secPerDay),
        estimate.tickCount,
        static_cast<float>(estimate.beatErrorMs),
    });
    rateDirty_ = false;
}

void DspChain::Process(const float* samples, size_t count, Output& out) {
    out.levels.clear();
    out.ticks.clear();
    out.rates.clear();
    out.phases.clear();

    for (size_t i = 0; i < count; ++i) {
        const float filtered = bandPass_.Process(samples[i]);
        const float env = envelope_.Process(filtered);
        const bool gateOpen = gate_.Process(env);

        // Edge path: always runs (cheap, and keeps it warm across mode
        // switches); only emits in edge mode.
        if (auto tick = tickDetector_.Process(env, gateOpen)) {
            rateDetector_.AddOnset(tick->frameIndex);
            ResolveActiveRate();
            const auto result = rateEstimator_.AddTick(tick->frameIndex);
            if (mode_ == AnalysisMode::kEdge) {
                out.ticks.push_back({tick->frameIndex, tick->peakDb, result.accepted});
            }
        }

        // Correlation path: likewise always fed, one envelope per band.
        float corrEnv[FoldingAnalyzer::kChannels];
        for (int c = 0; c < FoldingAnalyzer::kChannels; ++c) {
            corrEnv[c] = std::fabs(corrBands_[static_cast<size_t>(c)].Process(samples[i]));
        }
        FoldingAnalyzer::Snapshot snapshot;
        if (folding_.Push(corrEnv, &snapshot) &&
            mode_ == AnalysisMode::kCorrelation) {
            out.rates.push_back({
                snapshot.activeBph,
                snapshot.detectedBph,
                snapshot.overridden,
                snapshot.rateValid,
                snapshot.secPerDay,
                snapshot.beatCount,
                snapshot.beatErrorMs,
            });
            if (snapshot.phaseValid) {
                out.phases.push_back({snapshot.phaseDeviationMs, snapshot.periodMs});
            }
            rateDirty_ = false;
        }

        // The meter reads the envelope so its bar and the gate threshold
        // overlay share one scale (FR-3).
        levelScratch_.clear();
        levelAnalyzer_.Push(&env, 1, levelScratch_);
        for (const auto& level : levelScratch_) {
            out.levels.push_back({
                level.rmsDb,
                level.peakDb,
                gate_.openThresholdDb(),
                gateOpen,
                gate_.calibrating(),
            });
            if (mode_ == AnalysisMode::kEdge &&
                ++levelHopCounter_ >= kLevelHopsPerRateFrame) {
                levelHopCounter_ = 0;
                rateDirty_ = true;
            }
        }

        if (mode_ == AnalysisMode::kEdge && rateDirty_) {
            EmitRateFrame(out);
        }
    }
}

}  // namespace deadaccurate
