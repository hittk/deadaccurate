#include "deadaccurate/DspChain.h"

namespace deadaccurate {

DspChain::DspChain(int sampleRate)
    : sampleRate_(sampleRate),
      bandPass_(sampleRate, kBandLowHz, kBandHighHz),
      envelope_(sampleRate, kAttackMs, kReleaseMs),
      gate_(sampleRate),
      tickDetector_(sampleRate),
      levelAnalyzer_(static_cast<size_t>(sampleRate / kLevelFramesPerSecond)) {}

void DspChain::SetBeatRateBph(int bph) {
    if (bph > 0) {
        tickDetector_.SetBeatPeriodFrames(3600.0 / bph * sampleRate_);
    }
}

void DspChain::Process(const float* samples, size_t count, Output& out) {
    out.levels.clear();
    out.ticks.clear();

    for (size_t i = 0; i < count; ++i) {
        const float filtered = bandPass_.Process(samples[i]);
        const float env = envelope_.Process(filtered);
        const bool gateOpen = gate_.Process(env);

        if (auto tick = tickDetector_.Process(env, gateOpen)) {
            out.ticks.push_back({tick->frameIndex, tick->peakDb});
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
        }
    }
}

}  // namespace deadaccurate
