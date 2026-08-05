#include "deadaccurate/FoldingAnalyzer.h"

#include <algorithm>
#include <cmath>

namespace deadaccurate {
namespace {

// Includes vintage/pocket-watch rates; the 2x pairs (14400/28800 and
// 18000/36000) are resolved by the half-period-alias veto.
constexpr std::array<int, 8> kStandardRatesBph = {14400, 16200, 18000, 19800,
                                                  21600, 25200, 28800, 36000};

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

struct ProfileStats {
    double mean = 0.0;
    double sd = 0.0;
    int peak = 0;
    double peakHeight = 0.0;
    double secondHeight = 0.0;  // best peak >= minSlots away from `peak`
};

template <size_t N>
ProfileStats Analyze(const std::array<double, N>& profile, int minSlots) {
    ProfileStats stats;
    for (const double f : profile) {
        stats.mean += f;
    }
    stats.mean /= static_cast<double>(N);
    double variance = 0.0;
    for (const double f : profile) {
        variance += (f - stats.mean) * (f - stats.mean);
    }
    stats.sd = std::sqrt(variance / static_cast<double>(N));
    for (int k = 1; k < static_cast<int>(N); ++k) {
        if (profile[static_cast<size_t>(k)] >
            profile[static_cast<size_t>(stats.peak)]) {
            stats.peak = k;
        }
    }
    stats.peakHeight = profile[static_cast<size_t>(stats.peak)] - stats.mean;
    for (int k = 0; k < static_cast<int>(N); ++k) {
        const int distance = std::abs(k - stats.peak);
        const int circular = std::min(distance, static_cast<int>(N) - distance);
        if (circular >= minSlots) {
            stats.secondHeight = std::max(
                stats.secondHeight, profile[static_cast<size_t>(k)] - stats.mean);
        }
    }
    return stats;
}

}  // namespace

FoldingAnalyzer::FoldingAnalyzer(int sampleRate)
    : binSize_(static_cast<int>(sampleRate * kBinMs / 1000.0)),
      binDurationMs_(binSize_ * 1000.0 / sampleRate),
      meanAlpha_(static_cast<float>(binDurationMs_ / (kMeanSeconds * 1000.0))) {
    for (auto& channel : history_) {
        channel.assign(kWindowBins, 0.0f);
    }
}

void FoldingAnalyzer::SetBphOverride(int bph) {
    overrideBph_ = bph > 0 ? bph : 0;
    const int newActive = overrideBph_ > 0 ? overrideBph_ : lockedBph_;
    if (newActive != activeBph_) {
        activeBph_ = newActive;
        activePeriodMs_ = activeBph_ > 0 ? PeriodMsForBph(activeBph_) : 0.0;
        channelChosen_ = false;
        ResetProfiles();
    }
}

bool FoldingAnalyzer::Push(const float* channelEnvelopes, Snapshot* out) {
    for (int c = 0; c < kChannels; ++c) {
        binSum_[static_cast<size_t>(c)] += channelEnvelopes[c];
    }
    if (++binFill_ < binSize_) {
        return false;
    }
    binFill_ = 0;
    AddBin();
    if (++binsSinceSnapshot_ < kSnapshotBins) {
        return false;
    }
    binsSinceSnapshot_ = 0;
    EvaluateLock();
    TakeSnapshot();
    *out = snapshot_;
    return true;
}

void FoldingAnalyzer::AddBin() {
    const auto binTimeMs = static_cast<double>(binIndex_) * binDurationMs_;
    bool overloaded = false;
    for (int c = 0; c < kChannels; ++c) {
        const auto cs = static_cast<size_t>(c);
        const float value = binSum_[cs] / static_cast<float>(binSize_);
        binSum_[cs] = 0.0f;
        // Prime on the first bin: a mean ramping up from zero would leave a
        // large positive transient in the history for the whole 30 s
        // window, inflating every profile's sigma.
        if (binIndex_ == 0) {
            runningMean_[cs] = value;
        }
        // Winsorize against the running scale (see header): clip first,
        // then update mean and scale from the clipped value — a knock can
        // drag neither its own ceiling nor the mean (whose decay would
        // otherwise leave a long negative tail after the knock), while a
        // genuinely loud watch still adapts both within a few hundred bins.
        const float scale =
            std::max(absScale_[cs], kClipScaleFloor * runningMean_[cs]);
        const float limit = kClipScaleRatio * std::max(scale, 1e-9f);
        const float rawCentered = value - runningMean_[cs];
        const float centered = std::clamp(rawCentered, -limit, limit);
        runningMean_[cs] += meanAlpha_ * centered;
        absScale_[cs] += meanAlpha_ * (std::fabs(centered) - absScale_[cs]);
        overloaded = overloaded || rawCentered > kOverloadRatio * scale;
        lastCentered_[cs] = centered;
        history_[cs][static_cast<size_t>(binIndex_ % kWindowBins)] = centered;
    }
    if (overloaded) {
        // Solitary gross overload = the acoustic coupling changed (see
        // header): fold only what comes after it.
        if (binIndex_ - lastOverloadBin_ > kOverloadSpacingBins) {
            usableFloorBin_ = binIndex_ + kOverloadGuardBins;
        }
        lastOverloadBin_ = binIndex_;
    }
    ++historyCount_;
    ++binIndex_;
    if (activePeriodMs_ > 0.0 && channelChosen_) {
        UpdateProfiles(binTimeMs, lastCentered_[static_cast<size_t>(profileChannel_)]);
    }
}

int64_t FoldingAnalyzer::UsableBins() const {
    const int64_t available = std::min<int64_t>(historyCount_, kWindowBins);
    return binIndex_ - std::max(binIndex_ - available, usableFloorBin_);
}

bool FoldingAnalyzer::FoldProfile(int channel, double periodMs,
                                  std::array<double, 64>& profile) const {
    const int64_t available = UsableBins();
    if (available < kMinScoreBins) {
        return false;
    }
    const auto& history = history_[static_cast<size_t>(channel)];
    std::array<double, kScoreSlots> sum{};
    std::array<int, kScoreSlots> count{};
    const int64_t firstBin = binIndex_ - available;
    for (int64_t n = firstBin; n < binIndex_; ++n) {
        const double t = static_cast<double>(n) * binDurationMs_;
        const double phase = std::fmod(t, periodMs) / periodMs;
        const auto slot =
            std::min<int>(static_cast<int>(phase * kScoreSlots), kScoreSlots - 1);
        sum[static_cast<size_t>(slot)] += history[static_cast<size_t>(n % kWindowBins)];
        ++count[static_cast<size_t>(slot)];
    }
    for (int k = 0; k < kScoreSlots; ++k) {
        const auto ks = static_cast<size_t>(k);
        profile[ks] = count[ks] > 0 ? sum[ks] / count[ks] : 0.0;
    }
    return true;
}

double FoldingAnalyzer::ScoreChannel(int channel, double periodMs) const {
    double best = 0.0;
    for (const double rel : kScoreRateOffsets) {
        best = std::max(best, ScoreChannelAt(channel, periodMs * (1.0 + rel)));
    }
    return best;
}

double FoldingAnalyzer::ScoreChannelAt(int channel, double periodMs) const {
    std::array<double, kScoreSlots> profile{};
    if (!FoldProfile(channel, periodMs, profile)) {
        return 0.0;
    }
    const ProfileStats stats = Analyze(profile, kDoublePeakMinSlots);
    if (stats.sd < 1e-12) {
        return 0.0;
    }
    double score = stats.peakHeight / stats.sd;

    // Beat error splits the peak across two adjacent slots; the best
    // adjacent-pair mean recovers the full height (pair variance is sd²/2,
    // hence the sqrt(2) normalization). A compact peak keeps the higher
    // single-slot score.
    double bestPair = 0.0;
    for (int k = 0; k < kScoreSlots; ++k) {
        const double pair =
            (profile[static_cast<size_t>(k)] +
             profile[static_cast<size_t>((k + 1) % kScoreSlots)]) / 2.0 -
            stats.mean;
        bestPair = std::max(bestPair, pair);
    }
    score = std::max(score, bestPair * std::sqrt(2.0) / stats.sd);

    // Double-peak penalty: a comparable second peak far from the first means
    // ticks arrive twice per candidate period — the true period is shorter.
    if (stats.secondHeight > kDoublePeakFraction * stats.peakHeight) {
        score *= 0.5;
    }
    return score;
}

double FoldingAnalyzer::ScoreForDebug(int bph, int channel) const {
    return ScoreChannel(channel, PeriodMsForBph(bph));
}

FoldingAnalyzer::Score FoldingAnalyzer::ScorePeriod(int bph) const {
    const double periodMs = PeriodMsForBph(bph);
    Score best{bph, 0.0, 0};
    for (int c = 0; c < kChannels; ++c) {
        const double score = ScoreChannel(c, periodMs);
        if (score > best.value) {
            best.value = score;
            best.channel = c;
        }
    }
    if (best.value > kAliasMinScore && HalfPeriodAlias(best.channel, periodMs)) {
        best.value *= kAliasPenalty;
    }
    return best;
}

bool FoldingAnalyzer::HalfPeriodAlias(int channel, double periodMs) const {
    std::array<double, kScoreSlots> profile{};
    if (!FoldProfile(channel, 2.0 * periodMs, profile)) {
        return false;
    }
    const ProfileStats stats = Analyze(profile, kDoublePeakMinSlots);
    // A true period shows the two alternating beats at the 2x fold; a
    // single peak means every beat lands together — the real period is 2x.
    return stats.peakHeight > 0.0 &&
           stats.secondHeight < kAliasSecondPeakFraction * stats.peakHeight;
}

void FoldingAnalyzer::EvaluateLock() {
    // Right after an overload floored the history there is nothing to
    // score; freeze the lock state instead of reading silence as unlock.
    if (UsableBins() < kMinScoreBins) {
        return;
    }
    Score best;
    Score runnerUp;
    for (const int bph : kStandardRatesBph) {
        const Score score = ScorePeriod(bph);
        if (score.value > best.value) {
            runnerUp = best;
            best = score;
        } else if (score.value > runnerUp.value) {
            runnerUp = score;
        }
    }

    if (lockedBph_ != 0) {
        if (ScorePeriod(lockedBph_).value < kUnlockScore) {
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
        channelChosen_ = false;
        ResetProfiles();
    }

    // Pick (once per activation) the channel where the active period shows
    // best — profiles and phase tracking read that channel only.
    if (activeBph_ > 0 && !channelChosen_ && UsableBins() >= kMinScoreBins) {
        int bestChannel = 0;
        double bestScore = -1.0;
        for (int c = 0; c < kChannels; ++c) {
            const double score = ScoreChannel(c, activePeriodMs_);
            if (score > bestScore) {
                bestScore = score;
                bestChannel = c;
            }
        }
        profileChannel_ = bestChannel;
        channelChosen_ = true;
        ResetProfiles();
    }
}

void FoldingAnalyzer::ResetProfiles() {
    lastPeakSlot_ = -1;
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
        static_cast<int>(static_cast<double>(profileBins_) * binDurationMs_ / activePeriodMs_);
    EstimatePhaseAndRate();
    EstimateBeatError();
}

void FoldingAnalyzer::EstimatePhaseAndRate() {
    if (snapshot_.beatCount < kMinBeatsForPhase) {
        return;
    }
    const auto at = [&](int k) {
        return profileP_[static_cast<size_t>(((k % kProfileSlots) + kProfileSlots) %
                                             kProfileSlots)];
    };
    int globalPeak = 0;
    for (int k = 1; k < kProfileSlots; ++k) {
        if (at(k) > at(globalPeak)) {
            globalPeak = k;
        }
    }
    // Sticky peak: real drift moves the peak at most a slot or so per
    // snapshot, but twin impulse humps and amplitude fades can make the
    // global max hop across the profile, wrecking the phase series. Follow
    // the tracked peak unless a distant one clearly dominates.
    int peak = globalPeak;
    if (phaseStarted_ && lastPeakSlot_ >= 0) {
        int localPeak = lastPeakSlot_;
        for (int d = -3; d <= 3; ++d) {
            const int k = ((lastPeakSlot_ + d) % kProfileSlots + kProfileSlots) %
                          kProfileSlots;
            if (at(k) > at(localPeak)) {
                localPeak = k;
            }
        }
        const int dist = std::abs(globalPeak - localPeak);
        const int circular = std::min(dist, kProfileSlots - dist);
        if (circular > 3 && at(localPeak) > 0.0 &&
            at(globalPeak) < 1.3 * at(localPeak)) {
            peak = localPeak;
        }
    }
    lastPeakSlot_ = peak;
    const double offset = ParabolicOffset(at(peak - 1), at(peak), at(peak + 1));
    const double slotMs = activePeriodMs_ / kProfileSlots;
    const double phaseMs = (peak + 0.5 + offset) * slotMs;

    if (!phaseStarted_) {
        phaseStarted_ = true;
        lastPeakSlot_ = peak;
        lastPhaseMs_ = phaseMs;
        unwrappedPhaseMs_ = 0.0;
    } else {
        unwrappedPhaseMs_ += WrapHalf(phaseMs - lastPhaseMs_, activePeriodMs_);
        lastPhaseMs_ = phaseMs;
    }
    const double tSec = static_cast<double>(binIndex_) * binDurationMs_ / 1000.0;
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
    const double slope = cov / var;
    const double intercept = meanPhi - slope * meanT;

    // Robust refit: fades and residual peak hops leave outlier phase
    // points; drop residuals beyond 3 sigma (MAD-estimated) and refit.
    std::vector<double> absResiduals;
    absResiduals.reserve(phasePoints_.size());
    for (const auto& [t, phi] : phasePoints_) {
        absResiduals.push_back(std::fabs(phi - (intercept + slope * t)));
    }
    std::vector<double> sorted = absResiduals;
    const size_t mid = sorted.size() / 2;
    std::nth_element(sorted.begin(), sorted.begin() + mid, sorted.end());
    const double sigma = sorted[mid] * 1.4826;

    double kMeanT = 0.0;
    double kMeanPhi = 0.0;
    size_t kept = 0;
    {
        size_t i = 0;
        for (const auto& [t, phi] : phasePoints_) {
            if (sigma <= 0.0 || absResiduals[i] <= 3.0 * sigma) {
                kMeanT += t;
                kMeanPhi += phi;
                ++kept;
            }
            ++i;
        }
    }
    double usedSlope = slope;
    if (kept >= static_cast<size_t>(kMinRatePoints)) {
        kMeanT /= static_cast<double>(kept);
        kMeanPhi /= static_cast<double>(kept);
        double kCov = 0.0;
        double kVar = 0.0;
        size_t i = 0;
        for (const auto& [t, phi] : phasePoints_) {
            if (sigma <= 0.0 || absResiduals[i] <= 3.0 * sigma) {
                kCov += (t - kMeanT) * (phi - kMeanPhi);
                kVar += (t - kMeanT) * (t - kMeanT);
            }
            ++i;
        }
        if (kVar > 0.0) {
            usedSlope = kCov / kVar;
        }
    }

    snapshot_.rateValid = true;
    snapshot_.secPerDay = static_cast<float>(-usedSlope * kSecondsPerDayPerMsPerSec);
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
