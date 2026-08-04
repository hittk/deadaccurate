#pragma once

#include <array>
#include <cstdint>
#include <deque>

namespace deadaccurate {

// Identifies the movement's beat rate from onset intervals
// (docs/03-signal-processing.md section 5): each standard rate is scored on
// how well observed intervals fit integer multiples of its beat period
// (missed ticks make intervals 2-3x the period), with lock/unlock
// hysteresis so a noisy signal doesn't flap between neighboring rates.
class BeatRateDetector {
public:
    static constexpr std::array<int, 8> kStandardRatesBph = {
        14400, 16200, 18000, 19800, 21600, 25200, 28800, 36000};

    explicit BeatRateDetector(int sampleRate);

    void AddOnset(int64_t frameIndex);

    bool locked() const { return lockedBph_ != 0; }
    int lockedBph() const { return lockedBph_; }

private:
    double ScoreRate(int bph) const;
    void Evaluate();

    static constexpr double kWindowSeconds = 8.0;
    // ±4% of the period: wide enough that a movement with several ms of
    // beat error (intervals alternating T+e / T-e) still matches its own
    // rate, narrow enough that neighboring standard rates (>=9% apart)
    // don't cross-match.
    static constexpr double kIntervalTolerance = 0.04;
    static constexpr int kMaxMultiple = 3;              // up to 2 missed ticks
    static constexpr double kSkippedBeatWeight = 0.5;
    static constexpr double kLockScore = 0.7;
    static constexpr double kLockMargin = 0.15;
    static constexpr int kLockStreak = 3;
    static constexpr double kUnlockScore = 0.4;

    const int sampleRate_;
    const int64_t windowFrames_;

    std::deque<int64_t> onsets_;
    int lockedBph_ = 0;
    int candidateBph_ = 0;
    int candidateStreak_ = 0;
};

}  // namespace deadaccurate
