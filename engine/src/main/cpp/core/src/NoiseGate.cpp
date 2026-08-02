#include "deadaccurate/NoiseGate.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {
namespace {

// A floor of true digital silence would put the threshold at zero and pin
// the gate open; clamp to -120 dBFS instead.
constexpr float kMinFloor = 1e-6f;

float DbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float LinearToDb(float linear) {
    return 20.0f * std::log10(std::max(linear, kMinFloor));
}

}  // namespace

NoiseGate::NoiseGate(int sampleRate) : NoiseGate(sampleRate, Config{}) {}

NoiseGate::NoiseGate(int sampleRate, const Config& config)
    : sampleRate_(sampleRate),
      config_(config),
      holdFrames_(static_cast<int>(config.holdMs / 1000.0 * sampleRate)),
      adaptAlpha_(static_cast<float>(
          1.0 - std::exp(-1.0 / (config.adaptSeconds * sampleRate)))),
      floor_(kMinFloor) {
    UpdateFactors();
    StartCalibration();
}

void NoiseGate::StartCalibration() {
    calibrating_ = true;
    open_ = false;
    holdRemaining_ = 0;
    calibrationSamples_.clear();
    calibrationTarget_ = static_cast<size_t>(config_.calibrationSeconds * sampleRate_ /
                                             config_.calibrationDecimation);
    calibrationSamples_.reserve(calibrationTarget_);
    decimationCounter_ = 0;
}

void NoiseGate::SetTrimDb(float trimDb) {
    trimDb_ = trimDb;
    UpdateFactors();
}

void NoiseGate::UpdateFactors() {
    openFactor_ = DbToLinear(config_.marginDb + trimDb_);
    closeFactor_ = openFactor_ * DbToLinear(-config_.hysteresisDb);
}

void NoiseGate::FinishCalibration() {
    const size_t index = std::min(
        static_cast<size_t>(config_.calibrationPercentile *
                            static_cast<double>(calibrationSamples_.size())),
        calibrationSamples_.size() - 1);
    std::nth_element(calibrationSamples_.begin(), calibrationSamples_.begin() + index,
                     calibrationSamples_.end());
    floor_ = std::max(calibrationSamples_[index], kMinFloor);
    calibrationSamples_.clear();
    calibrating_ = false;
}

bool NoiseGate::Process(float envelope) {
    if (calibrating_) {
        if (decimationCounter_++ % config_.calibrationDecimation == 0) {
            calibrationSamples_.push_back(envelope);
            if (calibrationSamples_.size() >= calibrationTarget_) {
                FinishCalibration();
            }
        }
        return false;
    }

    if (open_) {
        if (envelope >= floor_ * closeFactor_) {
            holdRemaining_ = holdFrames_;
        } else if (--holdRemaining_ <= 0) {
            open_ = false;
        }
    } else {
        // Adapt the floor toward gradual ambient changes only while closed;
        // adaptation would otherwise chase the ticks themselves.
        floor_ = std::max(floor_ + adaptAlpha_ * (envelope - floor_), kMinFloor);
        if (envelope >= floor_ * openFactor_) {
            open_ = true;
            holdRemaining_ = holdFrames_;
        }
    }
    return open_;
}

float NoiseGate::openThresholdDb() const {
    return LinearToDb(floor_) + config_.marginDb + trimDb_;
}

}  // namespace deadaccurate
