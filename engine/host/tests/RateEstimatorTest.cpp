#include <cmath>
#include <cstdint>

#include "TestFramework.h"
#include "deadaccurate/RateEstimator.h"

using deadaccurate::RateEstimator;

namespace {

constexpr int kSampleRate = 48000;
constexpr double kPeriodFrames = 6000.0;  // 28800 bph at 48 kHz

// Feeds `count` ticks whose true period is `actualPeriod` frames, with a
// deterministic jitter of up to +/-`jitterFrames`.
void FeedTicks(RateEstimator& estimator, int count, double actualPeriod,
               int64_t jitterFrames = 0) {
    uint32_t state = 42;
    for (int i = 0; i < count; ++i) {
        int64_t jitter = 0;
        if (jitterFrames > 0) {
            state = state * 1664525u + 1013904223u;
            jitter = static_cast<int64_t>(state % (2 * jitterFrames + 1)) - jitterFrames;
        }
        estimator.AddTick(static_cast<int64_t>(std::llround(i * actualPeriod)) + jitter);
    }
}

}  // namespace

TEST(RateEstimator, PerfectWatchReadsZero) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    FeedTicks(estimator, 120, kPeriodFrames);  // ~15 s
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 0.0, 0.01);
}

TEST(RateEstimator, FastWatchReadsPositive) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    // 1 frame short per 6000-frame beat -> 86400/6000 = +14.4 s/day.
    FeedTicks(estimator, 120, kPeriodFrames - 1.0);
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 14.4, 0.05);
}

TEST(RateEstimator, SlowWatchReadsNegative) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    FeedTicks(estimator, 120, kPeriodFrames + 0.5);
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, -7.2, 0.05);
}

TEST(RateEstimator, JitterAveragesOut) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    // +/-10 frames (~0.2 ms) of onset jitter on a +14.4 s/day watch.
    FeedTicks(estimator, 200, kPeriodFrames - 1.0, 10);
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 14.4, 1.0);
}

TEST(RateEstimator, NotValidBeforeMinimumWindow) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    FeedTicks(estimator, 30, kPeriodFrames);  // < 10 s and < 40 ticks
    EXPECT_TRUE(!estimator.CurrentEstimate().valid);
}

TEST(RateEstimator, SurvivesMissedBeats) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    const double actual = kPeriodFrames - 1.0;
    for (int i = 0; i < 150; ++i) {
        if (i % 7 == 3) {
            continue;  // dropped tick -> double interval, multiple = 2
        }
        estimator.AddTick(static_cast<int64_t>(std::llround(i * actual)));
    }
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 14.4, 0.1);
}

TEST(RateEstimator, RejectsSpuriousTicksAndRecovers) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    const double actual = kPeriodFrames - 1.0;
    int rejected = 0;
    for (int i = 0; i < 150; ++i) {
        const auto frame = static_cast<int64_t>(std::llround(i * actual));
        estimator.AddTick(frame);
        if (i % 10 == 5) {
            // A bump mid-beat: far off any grid multiple.
            if (!estimator.AddTick(frame + 2500).accepted) {
                ++rejected;
            }
        }
    }
    EXPECT_TRUE(rejected > 10);
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 14.4, 1.0);
}

TEST(RateEstimator, LargeDriftUnwrapsPastHalfAPeriod) {
    RateEstimator estimator(kSampleRate);
    estimator.Reset(kPeriodFrames);
    // 30 frames/beat fast (+432 s/day): total drift over 150 beats is 4500
    // frames, far beyond half a period — the unwrapped series must keep the
    // slope linear instead of aliasing.
    FeedTicks(estimator, 150, kPeriodFrames - 30.0);
    const auto estimate = estimator.CurrentEstimate();
    EXPECT_TRUE(estimate.valid);
    EXPECT_NEAR(estimate.secPerDay, 432.0, 1.0);
}

TEST(RateEstimator, InactiveWithoutABeatRate) {
    RateEstimator estimator(kSampleRate);
    FeedTicks(estimator, 100, kPeriodFrames);
    EXPECT_TRUE(!estimator.CurrentEstimate().valid);
}
