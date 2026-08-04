#include "deadaccurate/FoldingAnalyzer.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {
namespace {

constexpr std::array<int, 6> kStandardRatesBph = {18000, 19800, 21600,
                                                  25200, 28800, 36000};

double PeriodMsForBph(int bph) {
    return 3600.0 * 1000.0 / bph;
}

// Sub-slot peak refinement: parabola through the peak and its neighbors.
double ParabolicOffset(double left, double mid, double right) {
    const double denom = left - 2.0 * mid + right;
    if (std::fabs(denom) < 1e-12) {
        return 0.0;
    }
    return std::clamp(0.5 * (left - right) / denom, -0.5, 0.5);
}

double WrapHalf(double value, double range) {
    double v = std::fmod(value, range);
    if (v < -range / 2.0) v += range;
    if (v >= range / 2.0) v -= range;
    return v;
}

}  // namespace

FoldingAnalyzer::FoldingAnalyzer(int sampleRate)
    : binSize_(static_cast<int>(sampleRate * kBinMs / 1000.0)),
      meanAlpha_(static_cast<float>(1.0 / (kMeanSeconds * 1000.0 / kBinMs))),
      history_(kWindowBins, 0.0f) {}

void FoldingAnalyzer::SetBphOverride(int bph) {
    overrideBph_ = bph > 0 ? bph : 0;
    const int newActive = overrideBph_ > 0 ? overrideBph_ : lockedBph_;
    if (newActive != activeBph_) {
        activeBph_ = newActive;
        activePeriodMs_ = activeBph_ > 0 ? PeriodMsForBph(activeBph_) : 0.0;
        ResetProfiles();
    }
}

bool FoldingAnalyzer::Push(const float* envelope, size_t count, Snapshot* out) {
    bool ready = false;
    for (size_t i = 0; i < count; ++i) {
        binSum_ += envelope[i];
        if (++binFill_ < binSize_) {
            continue;
        }
        AddBin(binSum_ / static_cast<float>(binSize_));
        binSum_ = 0.0f;
        binFill_ = 0;
        if (++binsSinceSnapshot_ >= kSnapshotBins) {
            binsSinceSnapshot_ = 0;
            EvaluateLock();
            TakeSnapshot();
            ready = true;
        }
    }
    if (ready) {
        *out = snapshot_;
    }
    return ready;
}

void FoldingAnalyzer::AddBin(float meanEnvelope) {
    runningMean_ += meanAlpha_ * (meanEnvelope - runningMean_);
    const float centered = meanEnvelope - runningMean_;
    history_[static_cast<size_t>(binIndex_ % kWindowBins)] = centered;
    ++historyCount_;
    const auto binTimeMs = static_cast<double>(binIndex_) * kBinMs;
    ++binIndex_;
    if (activePeriodMs_ > 0.0) {
        UpdateProfiles(binTimeMs, centered);
    }
}

bool FoldingAnalyzer::FoldProfile(double periodMs,
                                  std::array<double, 64>& profile) const {
    const int64_t available = std::min<int64_t>(historyCount_, kScoreBins);
    if (available < 2000) {
        return false;
    }
    std::array<double, kProfileSlots> sum{};
    std::array<int, kProfileSlots> count{};
    const int64_t firstBin = binIndex_ - available;
    for (int64_t n = firstBin; n < binIndex_; ++n) {
        const double t = static_cast<double>(n) * kBinMs;
        const double phase = std::fmod(t, periodMs) / periodMs;
        const auto slot = std::min<int>(static_cast<int>(phase * kProfileSlots),
                                        kProfileSlots - 1);
        sum[static_cast<size_t>(slot)] +=
            history_[static_cast<size_t>(n % kWindowBins)];
        ++count[static_cast<size_t>(slot)];
    }
    for (int k = 0; k < kProfileSlots; ++k) {
        const auto ks = static_cast<size_t>(k);
        profile[ks] = count[ks] > 0 ? sum[ks] / count[ks] : 0.0;
    }
    return true;
}

double FoldingAnalyzer::ScorePeriod(double periodMs) const {
    std::array<double, kProfileSlots> profile{};
    if (!FoldProfile(periodMs, profile)) {
        return 0.0;
    }
    double mean = 0.0;
    for (const double f : profile) {
        mean += f;
    }
    mean /= kProfileSlots;
    double variance = 0.0;
    for (const double f : profile) {
        variance += (f - mean) * (f - mean);
    }
    const double sd = std::sqrt(variance / kProfileSlots);
    if (sd < 1e-12) {
        return 0.0;
    }

    int peak = 0;
    for (int k = 1; k < kProfileSlots; ++k) {
        if (profile[static_cast<size_t>(k)] > profile[static_cast<size_t>(peak)]) {
            peak = k;
        }
    }
    const double peakHeight = profile[static_cast<size_t>(peak)] - mean;
    double score = peakHeight / sd;

    // Double-peak penalty: a comparable second peak far from the first means
    // ticks arrive twice per candidate period — the true period is shorter.
    double secondHeight = 0.0;
    for (int k = 0; k < kProfileSlots; ++k) {
        const int distance = std::abs(k - peak);
        const int circular = std::min(distance, kProfileSlots - distance);
        if (circular >= kDoublePeakMinSlots) {
            secondHeight =
                std::max(secondHeight, profile[static_cast<size_t>(k)] - mean);
        }
    }
    if (secondHeight > kDoublePeakFraction * peakHeight) {
        score *= 0.5;
    }
    return score;
}

bool FoldingAnalyzer::HalfPeriodAlias(double periodMs) const {
    std::array<double, kProfileSlots> profile{};
    if (!FoldProfile(2.0 * periodMs, profile)) {
        return false;
    }
    double mean = 0.0;
    for (const double f : profile) {
        mean += f;
    }
    mean /= kProfileSlots;
    int peak = 0;
    for (int k = 1; k < kProfileSlots; ++k) {
        if (profile[static_cast<size_t>(k)] > profile[static_cast<size_t>(peak)]) {
            peak = k;
        }
    }
    double secondHeight = 0.0;
    for (int k = 0; k < kProfileSlots; ++k) {
        const int distance = std::abs(k - peak);
        const int circular = std::min(distance, kProfileSlots - distance);
        if (circular >= kDoublePeakMinSlots) {
            secondHeight =
                std::max(secondHeight, profile[static_cast<size_t>(k)] - mean);
        }
    }
    const double peakHeight = profile[static_cast<size_t>(peak)] - mean;
    // A true period shows the two alternating beats at the 2x fold; a
    // single peak means every beat lands together — the real period is 2x.
    return peakHeight > 0.0 && secondHeight < kAliasSecondPeakFraction * peakHeight;
}

void FoldingAnalyzer::EvaluateLock() {
    Score best;
    Score runnerUp;
    for (const int bph : kStandardRatesBph) {
        double score = ScorePeriod(PeriodMsForBph(bph));
        if (score > kAliasMinScore && HalfPeriodAlias(PeriodMsForBph(bph))) {
            score *= kAliasPenalty;
        }
        if (score > best.value) {
            runnerUp = best;
            best = {bph, score};
        } else if (score > runnerUp.value) {
            runnerUp = {bph, score};
        }
    }

    if (lockedBph_ != 0) {
        if (ScorePeriod(PeriodMsForBph(lockedBph_)) < kUnlockScore) {
            lockedBph_ = 0;
            candidateBph_ = 0;
            candidateStreak_ = 0;
        }
    } else if (best.bph != 0 && best.value >= kLockScore &&
               best.value >= kLockMargin * std::max(runnerUp.value, 1e-9)) {
        candidateStreak_ = best.bph == candidateBph_ ? candidateStreak_ + 1 : 1;
        candidateBph_ = best.bph;
        if (candidateStreak_ >= kLockStreak) {
            lockedBph_ = best.bph;
        }
    } else {
        candidateBph_ = 0;
        candidateStreak_ = 0;
    }

    const int newActive = overrideBph_ > 0 ? overrideBph_ : lockedBph_;
    if (newActive != activeBph_) {
        activeBph_ = newActive;
        activePeriodMs_ = activeBph_ > 0 ? PeriodMsForBph(activeBph_) : 0.0;
        ResetProfiles();
    }
}

void FoldingAnalyzer::ResetProfiles() {
    profileP_.fill(0.0);
    profile2P_.fill(0.0);
    profileBins_ = 0;
    phaseStarted_ = false;
    unwrappedPhaseMs_ = 0.0;
    phasePoints_.clear();
}

void FoldingAnalyzer::UpdateProfiles(double binTimeMs, float centered) {
    const double phaseP = std::fmod(binTimeMs, activePeriodMs_) / activePeriodMs_;
    const auto slotP = std::min<int>(static_cast<int>(phaseP * kProfileSlots),
                                     kProfileSlots - 1);
    profileP_[static_cast<size_t>(slotP)] +=
        kProfileBeta * (centered - profileP_[static_cast<size_t>(slotP)]);

    const double period2 = 2.0 * activePeriodMs_;
    const double phase2 = std::fmod(binTimeMs, period2) / period2;
    const auto slot2 = std::min<int>(static_cast<int>(phase2 * 2 * kProfileSlots),
                                     2 * kProfileSlots - 1);
    profile2P_[static_cast<size_t>(slot2)] +=
        kProfileBeta * (centered - profile2P_[static_cast<size_t>(slot2)]);

    ++profileBins_;
}

void FoldingAnalyzer::TakeSnapshot() {
    snapshot_ = Snapshot{};
    snapshot_.activeBph = activeBph_;
    snapshot_.detectedBph = lockedBph_;
    snapshot_.overridden = overrideBph_ > 0;
    snapshot_.periodMs = static_cast<float>(activePeriodMs_);
    if (activePeriodMs_ <= 0.0) {
        return;
    }
    snapshot_.beatCount =
        static_cast<int>(static_cast<double>(profileBins_) * kBinMs / activePeriodMs_);
    EstimatePhaseAndRate();
    EstimateBeatError();
}

void FoldingAnalyzer::EstimatePhaseAndRate() {
    if (snapshot_.beatCount < kMinBeatsForPhase) {
        return;
    }
    int peak = 0;
    for (int k = 1; k < kProfileSlots; ++k) {
        if (profileP_[static_cast<size_t>(k)] > profileP_[static_cast<size_t>(peak)]) {
            peak = k;
        }
    }
    const auto at = [&](int k) {
        return profileP_[static_cast<size_t>(((k % kProfileSlots) + kProfileSlots) %
                                             kProfileSlots)];
    };
    const double offset = ParabolicOffset(at(peak - 1), at(peak), at(peak + 1));
    const double slotMs = activePeriodMs_ / kProfileSlots;
    const double phaseMs = (peak + 0.5 + offset) * slotMs;

    if (!phaseStarted_) {
        phaseStarted_ = true;
        lastPhaseMs_ = phaseMs;
        unwrappedPhaseMs_ = 0.0;
    } else {
        unwrappedPhaseMs_ += WrapHalf(phaseMs - lastPhaseMs_, activePeriodMs_);
        lastPhaseMs_ = phaseMs;
    }
    const double tSec = static_cast<double>(binIndex_) * kBinMs / 1000.0;
    phasePoints_.emplace_back(tSec, unwrappedPhaseMs_);
    while (phasePoints_.size() > kMaxPhasePoints) {
        phasePoints_.pop_front();
    }

    snapshot_.phaseValid = true;
    snapshot_.phaseDeviationMs =
        static_cast<float>(WrapHalf(unwrappedPhaseMs_, activePeriodMs_));

    // Rate = drift of the folded peak: least squares of unwrapped phase (ms)
    // against time (s); a fast watch drifts its phase earlier.
    const double span = phasePoints_.back().first - phasePoints_.front().first;
    if (span < kMinRateSpanSeconds ||
        phasePoints_.size() < static_cast<size_t>(kMinRatePoints)) {
        return;
    }
    double meanT = 0.0;
    double meanPhi = 0.0;
    for (const auto& [t, phi] : phasePoints_) {
        meanT += t;
        meanPhi += phi;
    }
    const auto n = static_cast<double>(phasePoints_.size());
    meanT /= n;
    meanPhi /= n;
    double cov = 0.0;
    double var = 0.0;
    for (const auto& [t, phi] : phasePoints_) {
        cov += (t - meanT) * (phi - meanPhi);
        var += (t - meanT) * (t - meanT);
    }
    if (var <= 0.0) {
        return;
    }
    const double slopeMsPerSec = cov / var;
    snapshot_.rateValid = true;
    snapshot_.secPerDay = static_cast<float>(-slopeMsPerSec * kSecondsPerDayPerMsPerSec);
}

void FoldingAnalyzer::EstimateBeatError() {
    if (snapshot_.beatCount < kMinBeatsForBeatError) {
        return;
    }
    constexpr int kSlots2 = 2 * kProfileSlots;
    const auto at = [&](int k) {
        return profile2P_[static_cast<size_t>(((k % kSlots2) + kSlots2) % kSlots2)];
    };
    double mean = 0.0;
    for (const double f : profile2P_) {
        mean += f;
    }
    mean /= kSlots2;

    int first = 0;
    for (int k = 1; k < kSlots2; ++k) {
        if (at(k) > at(first)) {
            first = k;
        }
    }
    // The partner beat sits ~half the 2P profile away.
    int second = -1;
    for (int d = -kBeatErrorSearchSlots; d <= kBeatErrorSearchSlots; ++d) {
        const int k = first + kProfileSlots + d;
        if (second < 0 || at(k) > at(second)) {
            second = k;
        }
    }
    const double firstHeight = at(first) - mean;
    const double secondHeight = at(second) - mean;
    if (firstHeight <= 0.0 || secondHeight < kBeatErrorPeakFraction * firstHeight) {
        return;
    }
    const double slotMs = 2.0 * activePeriodMs_ / kSlots2;
    const double posFirst =
        (first + ParabolicOffset(at(first - 1), at(first), at(first + 1))) * slotMs;
    const double posSecond =
        (second + ParabolicOffset(at(second - 1), at(second), at(second + 1))) * slotMs;
    const double separation = std::fabs(posSecond - posFirst);
    snapshot_.beatErrorMs = static_cast<float>(std::fabs(separation - activePeriodMs_));
}

}  // namespace deadaccurate
