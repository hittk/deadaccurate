#include "deadaccurate/LevelAnalyzer.h"

#include <cmath>

namespace deadaccurate {
namespace {

// Silence floor for the dB conversion; a true digital-zero hop reads as this.
constexpr float kFloorDb = -120.0f;

float ToDb(float linear) {
    if (linear <= 0.0f) {
        return kFloorDb;
    }
    const float db = 20.0f * std::log10(linear);
    return db < kFloorDb ? kFloorDb : db;
}

}  // namespace

LevelAnalyzer::LevelAnalyzer(size_t hopSize) : hopSize_(hopSize == 0 ? 1 : hopSize) {}

void LevelAnalyzer::Push(const float* samples, size_t count, std::vector<Level>& out) {
    for (size_t i = 0; i < count; ++i) {
        const float s = samples[i];
        sumSquares_ += static_cast<double>(s) * static_cast<double>(s);
        const float magnitude = std::fabs(s);
        if (magnitude > peak_) {
            peak_ = magnitude;
        }
        if (++samplesInHop_ == hopSize_) {
            const auto rms = static_cast<float>(
                std::sqrt(sumSquares_ / static_cast<double>(hopSize_)));
            out.push_back({ToDb(rms), ToDb(peak_)});
            samplesInHop_ = 0;
            sumSquares_ = 0.0;
            peak_ = 0.0f;
        }
    }
}

}  // namespace deadaccurate
