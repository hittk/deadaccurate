#pragma once

#include <cstdint>
#include <deque>
#include <vector>

namespace deadaccurate {

// Rate deviation in seconds/day from tick timestamps against the ideal beat
// grid (docs/03-signal-processing.md section 6). Phase deviation is tracked
// continuously (unwrapped) so a drifting watch never aliases; the fit is a
// least-squares regression of deviation vs beat index with one MAD-based
// outlier-rejection refit. All timing is audio-clock frames.
class RateEstimator {
public:
    struct TickResult {
        bool accepted;  // interval was plausible against the active grid
    };

    struct Estimate {
        bool valid;         // enough clean window to trust the number
        double secPerDay;   // + = fast
        int tickCount;      // accepted ticks in the window
        // Beat error (docs/03-signal-processing.md "tick/tock alternation"):
        // the offset between the two alternating beat series, from the
        // difference of their mean residuals around the common-slope fit.
        // Negative = not enough data in both parities yet.
        double beatErrorMs;
    };

    explicit RateEstimator(int sampleRate);

    // Clears history and sets the active beat period; 0 deactivates.
    void Reset(double beatPeriodFrames);

    TickResult AddTick(int64_t frameIndex);

    Estimate CurrentEstimate() const;

private:
    struct Point {
        double beatIndex;        // unwrapped grid index k
        double deviationFrames;  // unwrapped phase deviation d
        int64_t frameIndex;
    };

    double BeatErrorMs(const std::vector<Point>& kept, double slope) const;

    static constexpr double kWindowSeconds = 30.0;
    static constexpr double kMinSeconds = 10.0;
    static constexpr int kMinTicks = 40;
    // An interval further than this fraction of a period from the nearest
    // grid multiple marks the tick as an outlier.
    static constexpr double kAcceptFraction = 0.15;
    static constexpr int kMaxMissedBeats = 3;
    static constexpr double kMadFactor = 3.0;
    static constexpr double kMadToSigma = 1.4826;
    static constexpr double kSecondsPerDay = 86400.0;
    static constexpr int kMinTicksPerParity = 10;

    const int sampleRate_;

    double periodFrames_ = 0.0;
    bool started_ = false;
    int64_t lastFrame_ = 0;
    double lastBeatIndex_ = 0.0;
    double lastDeviation_ = 0.0;

    std::deque<Point> window_;
};

}  // namespace deadaccurate
