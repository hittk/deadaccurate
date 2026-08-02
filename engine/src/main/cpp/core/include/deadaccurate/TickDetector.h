#pragma once

#include <cstdint>
#include <optional>

namespace deadaccurate {

// Turns gate-opening transitions into tick onsets with a refractory period
// (docs/03-signal-processing.md section 4). M2 timestamps the onset at the
// threshold crossing; the leading-edge refinement lands with M3 where rate
// precision starts to matter.
class TickDetector {
public:
    struct Tick {
        int64_t frameIndex;  // frames since detector construction
        float peakDb;        // envelope level at the crossing, dBFS
    };

    explicit TickDetector(int sampleRate);

    // Refractory = 60% of the active beat period. Until a beat rate is
    // known, the constructor default covers the fastest standard rate.
    void SetBeatPeriodFrames(double beatPeriodFrames);

    // One sample step; `gateOpen` is this sample's gate output. Emits a tick
    // on a closed->open transition outside the refractory window.
    std::optional<Tick> Process(float envelope, bool gateOpen);

private:
    int64_t frameIndex_ = 0;
    int64_t lastTickFrame_;
    int64_t refractoryFrames_;
    bool previousOpen_ = false;
};

}  // namespace deadaccurate
