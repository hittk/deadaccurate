#include "deadaccurate/BeatRateDetector.h"

#include <cmath>

namespace deadaccurate {

BeatRateDetector::BeatRateDetector(int sampleRate)
    : sampleRate_(sampleRate),
      windowFrames_(static_cast<int64_t>(kWindowSeconds * sampleRate)) {}

void BeatRateDetector::AddOnset(int64_t frameIndex) {
    onsets_.push_back(frameIndex);
    while (!onsets_.empty() && frameIndex - onsets_.front() > windowFrames_) {
        onsets_.pop_front();
    }
    Evaluate();
}

double BeatRateDetector::ScoreRate(int bph) const {
    const double periodFrames = 3600.0 / bph * sampleRate_;
    double score = 0.0;
    size_t intervals = 0;
    for (size_t i = 1; i < onsets_.size(); ++i) {
        const auto interval = static_cast<double>(onsets_[i] - onsets_[i - 1]);
        ++intervals;
        const double multiple = std::round(interval / periodFrames);
        if (multiple < 1.0 || multiple > kMaxMultiple) {
            continue;
        }
        if (std::fabs(interval - multiple * periodFrames) <=
            kIntervalTolerance * periodFrames) {
            score += multiple == 1.0 ? 1.0 : kSkippedBeatWeight;
        }
    }
    return intervals == 0 ? 0.0 : score / static_cast<double>(intervals);
}

void BeatRateDetector::Evaluate() {
    if (onsets_.size() < 2) {
        return;
    }

    int bestBph = 0;
    double bestScore = 0.0;
    double runnerUpScore = 0.0;
    for (const int bph : kStandardRatesBph) {
        const double score = ScoreRate(bph);
        if (score > bestScore) {
            runnerUpScore = bestScore;
            bestScore = score;
            bestBph = bph;
        } else if (score > runnerUpScore) {
            runnerUpScore = score;
        }
    }

    if (lockedBph_ != 0) {
        if (ScoreRate(lockedBph_) < kUnlockScore) {
            lockedBph_ = 0;
            candidateBph_ = 0;
            candidateStreak_ = 0;
        }
        return;
    }

    if (bestBph != 0 && bestScore >= kLockScore &&
        bestScore - runnerUpScore >= kLockMargin) {
        candidateStreak_ = bestBph == candidateBph_ ? candidateStreak_ + 1 : 1;
        candidateBph_ = bestBph;
        if (candidateStreak_ >= kLockStreak) {
            lockedBph_ = bestBph;
        }
    } else {
        candidateBph_ = 0;
        candidateStreak_ = 0;
    }
}

}  // namespace deadaccurate
