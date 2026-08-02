#include "deadaccurate/RateEstimator.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace deadaccurate {
namespace {

struct Fit {
    bool valid;
    double slope;  // deviation frames per beat
};

template <typename GetX, typename GetY, typename Container>
Fit FitSlope(const Container& points, GetX getX, GetY getY) {
    const auto n = static_cast<double>(points.size());
    if (points.size() < 2) {
        return {false, 0.0};
    }
    double meanX = 0.0;
    double meanY = 0.0;
    for (const auto& p : points) {
        meanX += getX(p);
        meanY += getY(p);
    }
    meanX /= n;
    meanY /= n;
    double covXY = 0.0;
    double varX = 0.0;
    for (const auto& p : points) {
        const double dx = getX(p) - meanX;
        covXY += dx * (getY(p) - meanY);
        varX += dx * dx;
    }
    if (varX <= 0.0) {
        return {false, 0.0};
    }
    return {true, covXY / varX};
}

double Median(std::vector<double> values) {
    const size_t mid = values.size() / 2;
    std::nth_element(values.begin(), values.begin() + mid, values.end());
    return values[mid];
}

}  // namespace

RateEstimator::RateEstimator(int sampleRate) : sampleRate_(sampleRate) {}

void RateEstimator::Reset(double beatPeriodFrames) {
    periodFrames_ = beatPeriodFrames;
    started_ = false;
    window_.clear();
}

RateEstimator::TickResult RateEstimator::AddTick(int64_t frameIndex) {
    if (periodFrames_ <= 0.0) {
        return {true};
    }

    if (!started_) {
        started_ = true;
        lastFrame_ = frameIndex;
        lastBeatIndex_ = 0.0;
        lastDeviation_ = 0.0;
        window_.push_back({0.0, 0.0, frameIndex});
        return {true};
    }

    const auto interval = static_cast<double>(frameIndex - lastFrame_);
    const double multiple = std::round(interval / periodFrames_);
    const double residual = interval - multiple * periodFrames_;
    const bool accepted = multiple >= 1.0 && multiple <= kMaxMissedBeats &&
                          std::fabs(residual) <= kAcceptFraction * periodFrames_;

    // Unwrap continuity is maintained through rejected ticks too, so one
    // spurious onset can't shear the whole deviation series. A sub-period
    // spurious tick has multiple 0: it advances the grid by nothing, and the
    // residuals across it telescope so the next on-grid tick lands with the
    // correct (k, d).
    lastFrame_ = frameIndex;
    lastBeatIndex_ += multiple;
    lastDeviation_ += residual;

    if (accepted) {
        window_.push_back({lastBeatIndex_, lastDeviation_, frameIndex});
        const auto windowFrames = static_cast<int64_t>(kWindowSeconds * sampleRate_);
        while (!window_.empty() && frameIndex - window_.front().frameIndex > windowFrames) {
            window_.pop_front();
        }
    }
    return {accepted};
}

RateEstimator::Estimate RateEstimator::CurrentEstimate() const {
    if (periodFrames_ <= 0.0 || window_.size() < 2) {
        return {false, 0.0, static_cast<int>(window_.size()), -1.0};
    }

    const auto span =
        static_cast<double>(window_.back().frameIndex - window_.front().frameIndex);
    const bool enoughWindow = span >= kMinSeconds * sampleRate_ &&
                              static_cast<int>(window_.size()) >= kMinTicks;

    const auto getX = [](const Point& p) { return p.beatIndex; };
    const auto getY = [](const Point& p) { return p.deviationFrames; };
    const Fit first = FitSlope(window_, getX, getY);
    if (!first.valid) {
        return {false, 0.0, static_cast<int>(window_.size()), -1.0};
    }

    // One robust refit: drop points whose residual exceeds 3 sigma
    // (MAD-estimated), then fit again.
    double meanX = 0.0;
    double meanY = 0.0;
    for (const auto& p : window_) {
        meanX += p.beatIndex;
        meanY += p.deviationFrames;
    }
    meanX /= static_cast<double>(window_.size());
    meanY /= static_cast<double>(window_.size());
    const double intercept = meanY - first.slope * meanX;

    std::vector<double> absResiduals;
    absResiduals.reserve(window_.size());
    for (const auto& p : window_) {
        absResiduals.push_back(
            std::fabs(p.deviationFrames - (intercept + first.slope * p.beatIndex)));
    }
    const double sigma = Median(absResiduals) * kMadToSigma;

    std::vector<Point> kept;
    kept.reserve(window_.size());
    for (size_t i = 0; i < window_.size(); ++i) {
        if (sigma <= 0.0 || absResiduals[i] <= kMadFactor * sigma) {
            kept.push_back(window_[i]);
        }
    }

    const Fit refit = FitSlope(kept, getX, getY);
    const Fit used = refit.valid ? refit : first;

    // slope frames/beat over a period of periodFrames_ -> dimensionless rate
    // error; negative slope (ticks early) = fast watch = positive s/day.
    const double secPerDay = -used.slope / periodFrames_ * kSecondsPerDay;
    return {enoughWindow, secPerDay, static_cast<int>(kept.size()),
            BeatErrorMs(kept, used.slope)};
}

double RateEstimator::BeatErrorMs(const std::vector<Point>& kept, double slope) const {
    // The escapement's two pallets alternate, so deviations form two series
    // separated by the beat error. With unwrapped beat indices, parity is
    // stable even across missed beats (multiple 2 preserves it).
    double sum[2] = {0.0, 0.0};
    int count[2] = {0, 0};
    for (const auto& p : kept) {
        // Residual around the common slope; a shared intercept cancels in
        // the difference below, so slope*k alone is enough.
        const double detrended = p.deviationFrames - slope * p.beatIndex;
        const int parity = static_cast<int>(std::llround(p.beatIndex)) & 1;
        sum[parity] += detrended;
        ++count[parity];
    }
    if (count[0] < kMinTicksPerParity || count[1] < kMinTicksPerParity) {
        return -1.0;
    }
    const double offsetFrames = sum[0] / count[0] - sum[1] / count[1];
    return std::fabs(offsetFrames) / (sampleRate_ / 1000.0);
}

}  // namespace deadaccurate
