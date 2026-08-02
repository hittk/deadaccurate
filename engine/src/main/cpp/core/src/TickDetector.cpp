#include "deadaccurate/TickDetector.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {
namespace {

constexpr double kRefractoryFraction = 0.6;
// Fallback refractory before a beat rate is chosen: 60% of the fastest
// standard beat period (36000 bph = 10 beats/s = 100 ms -> 60 ms).
constexpr double kFastestBph = 36000.0;
constexpr float kMinLevel = 1e-6f;

}  // namespace

TickDetector::TickDetector(int sampleRate) {
    const double fastestPeriodFrames = 3600.0 / kFastestBph * sampleRate;
    refractoryFrames_ = static_cast<int64_t>(kRefractoryFraction * fastestPeriodFrames);
    lastTickFrame_ = -refractoryFrames_;
}

void TickDetector::SetBeatPeriodFrames(double beatPeriodFrames) {
    refractoryFrames_ =
        std::max<int64_t>(1, static_cast<int64_t>(kRefractoryFraction * beatPeriodFrames));
}

std::optional<TickDetector::Tick> TickDetector::Process(float envelope, bool gateOpen) {
    std::optional<Tick> tick;
    const bool justOpened = gateOpen && !previousOpen_;
    if (justOpened && frameIndex_ - lastTickFrame_ >= refractoryFrames_) {
        lastTickFrame_ = frameIndex_;
        tick = Tick{
            frameIndex_,
            20.0f * std::log10(std::max(envelope, kMinLevel)),
        };
    }
    previousOpen_ = gateOpen;
    ++frameIndex_;
    return tick;
}

}  // namespace deadaccurate
