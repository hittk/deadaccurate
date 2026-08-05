#include "deadaccurate/AmplitudeAnalyzer.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {

namespace {
constexpr double kMsPerSecond = 1000.0;
constexpr double kMsPerHourBph = 3.6e6;  // beat period ms = this / bph

float OnePoleCoeff(double ms, int sampleRate) {
    return static_cast<float>(
        1.0 - std::exp(-1.0 / (ms / kMsPerSecond * sampleRate)));
}

double Median(std::vector<double>& values) {
    std::sort(values.begin(), values.end());
    const size_t n = values.size();
    return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
}
}  // namespace

AmplitudeAnalyzer::AmplitudeAnalyzer(int sampleRate)
    : sampleRate_(sampleRate),
      attackCoeff_(OnePoleCoeff(kAttackMs, sampleRate)),
      releaseCoeff_(OnePoleCoeff(kReleaseMs, sampleRate)),
      preFrames_(static_cast<int>(kPreMs / kMsPerSecond * sampleRate)),
      windowFrames_(static_cast<int>((kPreMs + kPostMs) / kMsPerSecond * sampleRate)),
      minSepFrames_(static_cast<int>(kMinPulseSepMs / kMsPerSecond * sampleRate)) {
    // Power of two comfortably above the window plus scheduling slack.
    size_t capacity = 1;
    while (capacity < static_cast<size_t>(windowFrames_) * 4) {
        capacity *= 2;
    }
    ring_.assign(capacity, 0.0f);
}

void AmplitudeAnalyzer::Reset() {
    env_ = 0.0f;
    std::fill(ring_.begin(), ring_.end(), 0.0f);
    frames_ = 0;
    pendingTicks_.clear();
    liftTimes_.clear();
}

void AmplitudeAnalyzer::Push(float bandPassed) {
    const float magnitude = std::fabs(bandPassed);
    const float coeff = magnitude > env_ ? attackCoeff_ : releaseCoeff_;
    env_ += coeff * (magnitude - env_);
    ring_[static_cast<size_t>(frames_) % ring_.size()] = env_;
    ++frames_;
    ProcessPending();
}

void AmplitudeAnalyzer::OnTick(int64_t frameIndex) {
    if (pendingTicks_.size() < 8) {
        pendingTicks_.push_back(frameIndex);
    }
}

void AmplitudeAnalyzer::ProcessPending() {
    while (!pendingTicks_.empty()) {
        const int64_t start = pendingTicks_.front() - preFrames_;
        if (frames_ < start + windowFrames_) {
            return;  // window still streaming in
        }
        pendingTicks_.pop_front();
        if (start >= 0 && frames_ - start < static_cast<int64_t>(ring_.size())) {
            AnalyzeWindow(start);
        }
    }
}

void AmplitudeAnalyzer::AnalyzeWindow(int64_t startFrame) {
    const auto at = [&](int i) {
        return ring_[static_cast<size_t>(startFrame + i) % ring_.size()];
    };
    float peak = 0.0f;
    for (int i = 0; i < windowFrames_; ++i) {
        peak = std::max(peak, at(i));
    }
    if (peak <= 0.0f) {
        return;
    }
    const float threshold = static_cast<float>(kPulseFraction) * peak;

    // Sub-pulse peaks: local maxima over a ±half-separation neighborhood,
    // above the threshold, kept at least the minimum separation apart.
    std::vector<int> pulses;
    const int half = minSepFrames_ / 2;
    for (int i = half; i < windowFrames_ - half; ++i) {
        const float v = at(i);
        if (v < threshold) {
            continue;
        }
        bool isMax = true;
        for (int d = -half; d <= half && isMax; ++d) {
            const float other = at(i + d);
            if (other > v || (other == v && d < 0)) {
                isMax = false;
            }
        }
        if (!isMax) {
            continue;
        }
        if (!pulses.empty() && i - pulses.back() < minSepFrames_) {
            continue;
        }
        if (static_cast<int>(pulses.size()) == kMaxPulses) {
            pulses.clear();  // too busy — noise, not tick structure
            return;
        }
        pulses.push_back(i);
    }
    if (static_cast<int>(pulses.size()) < kMinPulses) {
        return;
    }
    const double liftMs =
        static_cast<double>(pulses.back() - pulses.front()) / sampleRate_ * kMsPerSecond;
    if (liftMs < kMinLiftMs || liftMs > kMaxLiftMs) {
        return;
    }
    liftTimes_.emplace_back(startFrame, liftMs);
    while (liftTimes_.size() > kMaxSamples) {
        liftTimes_.pop_front();
    }
}

AmplitudeAnalyzer::Estimate AmplitudeAnalyzer::Current(int bph,
                                                       double liftAngleDeg) const {
    Estimate estimate;
    if (bph <= 0) {
        return estimate;
    }
    const auto freshLimit =
        frames_ - static_cast<int64_t>(kFreshSeconds * sampleRate_);
    std::vector<double> fresh;
    for (const auto& [frame, liftMs] : liftTimes_) {
        if (frame >= freshLimit) {
            fresh.push_back(liftMs);
        }
    }
    if (static_cast<int>(fresh.size()) < kMinSamplesForEstimate) {
        return estimate;
    }
    const double median = Median(fresh);
    // Spread gate: per-tick lift times of a resolvable signal agree to a
    // fraction of a millisecond; wide spread means the sub-pulses are not
    // really being seen and the "estimate" would be noise.
    std::vector<double> deviations;
    deviations.reserve(fresh.size());
    for (const double v : fresh) {
        deviations.push_back(std::fabs(v - median));
    }
    const double spread = Median(deviations);
    if (spread > std::max(kMaxSpreadFraction * median, kMinSpreadFloorMs)) {
        return estimate;
    }

    const double beatMs = kMsPerHourBph / bph;
    const double oscillationMs = 2.0 * beatMs;
    const double amplitude =
        liftAngleDeg / (2.0 * std::sin(M_PI * median / oscillationMs));
    if (amplitude < kMinAmplitudeDeg || amplitude > kMaxAmplitudeDeg) {
        return estimate;
    }
    estimate.valid = true;
    estimate.amplitudeDeg = static_cast<float>(amplitude);
    estimate.liftTimeMs = static_cast<float>(median);
    return estimate;
}

}  // namespace deadaccurate
